package com.lumen.image;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 跟 root 后端（127.0.0.1:8189）说话。
 * /generate 走 SSE：progress 若干 + 最后一次 complete，complete 里带 base64 原始 RGB。
 */
public class EngineClient {

    private static final String TAG = "LumenEngine";
    /** CPU 后端端口 */
    public static final int PORT = 8189;
    /** GPU 后端端口 */
    public static final int PORT_GPU = 8191;

    public static final String BASE = "http://127.0.0.1:" + PORT;

    /** 取消标志 —— 停止按钮置 true，读流时就会中断 */
    public interface Ctl { boolean cancelled(); }

    public interface Callback {
        void onProgress(int step, int total);
        void onImage(Bitmap bmp, long totalMs, long firstStepMs);
        void onPreview(Bitmap bmp);
        void onError(String msg);
    }

    // ─── 请求体 ──────────────────────────────────────────────
    public static class Request {
        public String prompt = "";
        public String negative = "";
        public int steps = 4;
        public float cfg = 1.0f;
        public long seed = -1;
        public int width = 512;
        public int height = 512;
        public String loraPath;
        public float loraStrength = 1.0f;

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("prompt", prompt);
                o.put("neg", negative);
                o.put("steps", steps);
                o.put("cfg", cfg);
                o.put("seed", seed);
                o.put("width", width);
                o.put("height", height);
                if (loraPath != null && !loraPath.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    JSONObject l = new JSONObject();
                    l.put("path", loraPath);
                    l.put("strength", loraStrength);
                    arr.put(l);
                    o.put("loras", arr);   // 复数！单数会被静默忽略
                }
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    // ─── 主流程（阻塞，必须放子线程）───────────────────────────
    public static void generate(Request req, Callback cb, Ctl ctl) {
        generate(req, PORT, cb, ctl);
    }

    /** 指定端口生成：CPU=8189，GPU=8191 */
    public static void generate(Request req, int port, Callback cb, Ctl ctl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/generate").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20 * 60 * 1000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");

            byte[] body = req.toJson().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                cb.onError("HTTP " + code + " " + readAll(conn.getErrorStream()));
                return;
            }

            String event = null;
            StringBuilder data = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (ctl != null && ctl.cancelled()) {
                        Log.i(TAG, "用户取消，断开");
                        return;
                    }
                    if (line.isEmpty()) {
                        if (event != null && data.length() > 0) {
                            if (handleEvent(event, data.toString(), cb)) return;
                        }
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
                if (event != null && data.length() > 0) handleEvent(event, data.toString(), cb);
            }
        } catch (Exception e) {
            Log.e(TAG, "generate 失败", e);
            cb.onError(String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** @return true = 收到 complete，可以停 */
    private static boolean handleEvent(String event, String json, Callback cb) {
        try {
            JSONObject o = new JSONObject(json);

            if ("progress".equals(event)) {
                int step = o.optInt("step", 0);
                int total = o.optInt("total_steps", 5);

                String pv = o.optString("image", "");
                if (pv != null && !pv.isEmpty()) {
                    Bitmap pb = decodeRaw(pv,
                            o.optInt("width", 0), o.optInt("height", 0),
                            o.optInt("channels", 0));
                    if (pb != null) cb.onPreview(pb);
                }
                cb.onProgress(step, total);
                return false;
            }

            if ("complete".equals(event)) {
                String b64 = o.optString("image", "");
                int w = o.optInt("width", 512);
                int h = o.optInt("height", 512);
                int ch = o.optInt("channels", 3);
                long total = o.optLong("generation_time_ms", 0);
                long first = o.optLong("first_step_time_ms", 0);

                Bitmap bmp = decodeRaw(b64, w, h, ch);
                if (bmp == null) {
                    cb.onError("解码失败 w=" + w + " h=" + h + " ch=" + ch
                            + " b64=" + b64.length());
                } else {
                    cb.onImage(bmp, total, first);
                }
                return true;
            }

            if ("error".equals(event)) {
                cb.onError(o.optString("message", json));
                return true;
            }

            Log.w(TAG, "未知事件: " + event);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "解析失败", e);
            return false;
        }
    }

    /**
     * base64 → 原始像素 → Bitmap。
     * 后端给的是 RGB 交织（format=raw），无行填充。已验证不需要换通道。
     */
    public static Bitmap decodeRaw(String b64, int w, int h, int ch) {
        if (b64 == null || b64.isEmpty() || w <= 0 || h <= 0) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            long e3 = (long) w * h * 3, e4 = (long) w * h * 4;
            if (ch <= 0) {
                if (raw.length == e3) ch = 3;
                else if (raw.length == e4) ch = 4;
                else return null;
            }
            if (raw.length < (long) w * h * ch) {
                Log.e(TAG, "像素不足 " + raw.length + " < " + (long) w * h * ch);
                return null;
            }
            int[] px = new int[w * h];
            int p = 0;
            for (int i = 0; i < px.length; i++) {
                int r = raw[p] & 0xFF;
                int g = raw[p + 1] & 0xFF;
                int b = raw[p + 2] & 0xFF;
                p += ch;
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.e(TAG, "decodeRaw 异常", e);
            return null;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}