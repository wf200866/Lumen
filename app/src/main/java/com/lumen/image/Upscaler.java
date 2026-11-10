package com.lumen.image;

import android.graphics.Bitmap;

/**
 * 纯 CPU 图像超分（Lanczos3 重采样）。
 *
 * 为什么不用引擎自带的 upscaler：
 *   日志明确写着 "this backend has no QNN runtime, so it cannot upscale"，
 *   自带超分依赖 QNN，而本机 NPU 是 Hexagon v69，跑不了模型需要的 v75。
 *
 * 所以这里做纯 CPU 的高质量重采样：
 *   - Lanczos3 是公认的高质量插值核（比 bilinear 锐利得多，比 bicubic 少振铃）
 *   - 多线程并行（按行切分）
 *   - 超过 2 倍时先放大到 2 倍再继续，避免一次跳太多
 *
 * 注意：重采样不等于 AI 超分，它不能"无中生有"补细节，
 *      但能把 512 的图放到 1024 而不糊，边缘保持干净。
 */
public final class Upscaler {

    private static final int LOBES = 3;          // Lanczos3
    private static final float A = 3.0f;

    private Upscaler() {}

    /** 2 倍放大 */
    public static Bitmap upscale2x(Bitmap src) {
        return scale(src, src.getWidth() * 2, src.getHeight() * 2);
    }

    /**
     * 任意尺寸缩放。
     * 实现：先水平、再垂直（可分离滤波，复杂度 O(w*h*(支持宽度)) 而不是 O(w*h*核面积)）
     */
    public static Bitmap scale(Bitmap src, int dstW, int dstH) {
        if (src == null || dstW <= 0 || dstH <= 0) return src;

        int sw = src.getWidth(), sh = src.getHeight();
        if (sw == dstW && sh == dstH) return src;

        // 取出源像素（int 数组，ARGB_8888）
        int[] srcPx = new int[sw * sh];
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh);

        // ── 1. 水平缩放：sw -> dstW，高度保持 sh ──
        float[] hm = buildWeights(sw, dstW);
        int[] hTab = buildIndexTable(sw, dstW, hm);
        int[] tmpPx = new int[dstW * sh];

        int threads = Config.defaultThreads();
        parallelFor(sh, threads, y -> {
            int rowIn = y * sw;
            int rowOut = y * dstW;
            for (int x = 0; x < dstW; x++) {
                int base = x * (2 * LOBES + 2);
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 2 * LOBES; k++) {
                    int idx = hTab[base + k];
                    float w = hm[base + k];
                    if (idx < 0) idx = 0;
                    else if (idx >= sw) idx = sw - 1;
                    int c = srcPx[rowIn + idx];
                    float aa = ((c >>> 24) & 0xFF) * w;
                    r += ((c >> 16) & 0xFF) * w;
                    g += ((c >> 8) & 0xFF) * w;
                    b += (c & 0xFF) * w;
                    a += aa;
                }
                tmpPx[rowOut + x] = pack(a, r, g, b);
            }
        });

        // ── 2. 垂直缩放：sh -> dstH，宽度 dstW ──
        float[] vm = buildWeights(sh, dstH);
        int[] vTab = buildIndexTable(sh, dstH, vm);
        int[] dstPx = new int[dstW * dstH];

        parallelFor(dstH, threads, y -> {
            int rowOut = y * dstW;
            int base = y * (2 * LOBES + 2);
            for (int x = 0; x < dstW; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 2 * LOBES; k++) {
                    int idx = vTab[base + k];
                    float w = vm[base + k];
                    if (idx < 0) idx = 0;
                    else if (idx >= sh) idx = sh - 1;
                    int c = tmpPx[idx * dstW + x];
                    a += ((c >>> 24) & 0xFF) * w;
                    r += ((c >> 16) & 0xFF) * w;
                    g += ((c >> 8) & 0xFF) * w;
                    b += (c & 0xFF) * w;
                }
                dstPx[rowOut + x] = pack(a, r, g, b);
            }
        });

        Bitmap out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        out.setPixels(dstPx, 0, dstW, 0, 0, dstW, dstH);
        return out;
    }

    // ──────────────────────────────────────────────────────────

    /** Lanczos3 核函数 */
    private static float lanczos(float x) {
        x = Math.abs(x);
        if (x < 1e-6f) return 1f;
        if (x >= A) return 0f;
        double px = Math.PI * x;
        return (float) (A * Math.sin(px) * Math.sin(px / A) / (px * px));
    }

    /**
     * 为每个输出像素算 (2*LOBES) 个权重，归一化。
     *
     * 坐标约定：源图像像素 i 的中心在浮点坐标 i，
     * 输出像素 j 的中心在 (j + 0.5) * scale - 0.5，
     * 采样点 k 对应源像素 first + k，first = floor(center) - LOBES + 1。
     *
     * 【修复记录】原实现：
     *     xx = center + (k - LOBES + 0.5f) * max(1, scale)
     *     w  = lanczos((center - xx) / max(1, scale))
     * 2 倍放大时 scale=0.5、max(1,scale)=1，代入得 w = lanczos(k - 2.5),
     * 与 i 完全无关 —— 每个输出像素都用同一组权重，等于没做位置相关的重采样。
     * 现在改为以 center 为基准算每个采样点的距离，并把 first 存进表尾供索引表复用。
     */
    private static float[] buildWeights(int srcLen, int dstLen) {
        float scale = (float) srcLen / dstLen;
        float fscale = Math.max(1f, scale);   // 缩小时才扩散支持域
        int taps = 2 * LOBES;

        float[] out = new float[dstLen * (taps + 2)];
        for (int i = 0; i < dstLen; i++) {
            float center = (i + 0.5f) * scale - 0.5f;
            int base = i * (taps + 2);
            int first = (int) Math.floor(center) - LOBES + 1;
            float sum = 0f;

            for (int k = 0; k < taps; k++) {
                int sx = first + k;
                float d = (center - sx) / fscale;
                float w = lanczos(d);
                out[base + k] = w;
                sum += w;
            }

            if (Math.abs(sum) < 1e-6f) {
                out[base] = 1f;
                for (int k = 1; k < taps; k++) out[base + k] = 0f;
            } else {
                for (int k = 0; k < taps; k++) out[base + k] /= sum;
            }

            out[base + taps]     = first;   // 采样起点
            out[base + taps + 1] = scale;   // 备用
        }
        return out;
    }

    /**
     * 为每个输出像素生成源索引表。
     * 起点直接从 buildWeights 写进表尾的 first 读，保证权重和索引永远对齐。
     */
    private static int[] buildIndexTable(int srcLen, int dstLen, float[] weights) {
        int taps = 2 * LOBES;
        int stride = taps + 2;
        int[] tab = new int[dstLen * taps];
        for (int i = 0; i < dstLen; i++) {
            int wbase = i * stride;
            int first = (int) weights[wbase + taps];
            int tbase = i * taps;
            for (int k = 0; k < taps; k++) {
                tab[tbase + k] = first + k;
            }
        }
        return tab;
    }

    private static int pack(float a, float r, float g, float b) {
        int ai = clamp255(Math.round(a));
        int ri = clamp255(Math.round(r));
        int gi = clamp255(Math.round(g));
        int bi = clamp255(Math.round(b));
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ── 简易并行 for ──
    private interface IntTask { void run(int i); }

    private static void parallelFor(int count, int threads, IntTask task) {
        if (threads <= 1 || count < 64) {
            for (int i = 0; i < count; i++) task.run(i);
            return;
        }
        final int tn = Math.min(threads, count);
        Thread[] ts = new Thread[tn];
        final int chunk = (count + tn - 1) / tn;
        for (int t = 0; t < tn; t++) {
            final int start = t * chunk;
            final int end = Math.min(count, start + chunk);
            ts[t] = new Thread(() -> {
                for (int i = start; i < end; i++) task.run(i);
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
    }
}