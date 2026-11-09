package com.lumen.image;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * root 模式后端管理。
 *
 * 设计要点：
 * - 后端是 su 拉起的独立进程，App 杀掉也不影响
 * - oom_score_adj=-1000，LMK 永不回收
 * - renice -20，优先调度
 * - 放到 /data/adb/lumen（KSU/Magisk 的持久区）
 */
public class RootBackend {

    private static final String TAG = "LumenRoot";

    public static final String BIN_DIR = "/data/adb/lumen";
    public static final String SCRIPT  = BIN_DIR + "/lumen_be.sh";
    public static final int    PORT    = EngineClient.PORT;

    /** 部署标记：版本号变了就重新部署 */
    public static final String DEPLOY_VER = "3";

    // ─────────────────────────────────────────────────────────
    // 底层：跑一条 root 命令
    // ─────────────────────────────────────────────────────────
    public static String sh(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(p.getOutputStream())) {
                os.writeBytes(cmd + "\n");
                os.writeBytes("echo __RC__$?\n");
                os.writeBytes("exit\n");
                os.flush();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("__RC__")) break;
                    sb.append(line).append('\n');
                }
            }
            // 吞掉 stderr 避免管道塞满
            drain(p.getErrorStream());

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "[timeout] " + sb.toString().trim();
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "sh 失败: " + cmd, e);
            return "[error] " + e.getMessage();
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    private static void drain(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String l;
            int n = 0;
            while ((l = r.readLine()) != null && n++ < 200) { /* 丢弃 */ }
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────────────────
    public static boolean hasRoot() {
        return "0".equals(sh("id -u", 8).trim());
    }

    /** 端口是否在监听（不依赖 root，纯 socket） */
    public static boolean pingPort() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "RUNNING pid=1 rss=4400MB oom_adj=-1000 port=1" / "STOPPED" */
    public static String status() {
        return sh("[ -f " + SCRIPT + " ] && sh " + SCRIPT + " status || echo NOTDEPLOYED", 15);
    }

    public static boolean isRunning() {
        return status().startsWith("RUNNING");
    }

    /** 从 status 串里抠出 rss 数字 */
    public static int parseRss(String status) {
        int i = status.indexOf("rss=");
        if (i < 0) return -1;
        int j = status.indexOf("MB", i);
        if (j < 0) return -1;
        try { return Integer.parseInt(status.substring(i + 4, j)); }
        catch (Exception e) { return -1; }
    }

    // ─────────────────────────────────────────────────────────
    // 部署
    // ─────────────────────────────────────────────────────────

    /** 已部署且版本正确？ */
    public static boolean isDeployed() {
        String out = sh(
                "[ -x " + BIN_DIR + "/libstable_diffusion_core.so ] && " +
                "[ -f " + BIN_DIR + "/libdit_engine.so ] && " +
                "[ -f " + SCRIPT + " ] && " +
                "cat " + BIN_DIR + "/.ver 2>/dev/null", 12);
        return out.contains(DEPLOY_VER);
    }

    /**
     * 把 native 库和启动脚本复制到 /data/adb/lumen。
     * 返回 "ok" 或错误说明。
     */
    public static String deploy(Context ctx) {
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File core = new File(nativeDir, "libsd_core.so");
        File dit  = new File(nativeDir, "libdit_engine.so");

        if (!core.exists())     return "APK 内找不到 libsd_core.so";
        if (!dit.exists())      return "APK 内找不到 libdit_engine.so";

        // 启动脚本先释放到 App 私有目录（App 可写），再用 root 拷过去
        File tmpScript;
        try {
            tmpScript = new File(ctx.getCacheDir(), "lumen_be.sh");
            try (InputStream in = ctx.getAssets().open("lumen_be.sh");
                 FileOutputStream fo = new FileOutputStream(tmpScript)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        } catch (Exception e) {
            return "释放脚本失败: " + e.getMessage();
        }

        // 分步执行，每步单独判断，免得一个失败全挂
        String mk = sh("mkdir -p " + BIN_DIR, 10);
        if (mk.contains("[error]") || mk.contains("[timeout]")) return "建目录失败: " + mk;

        String cp1 = sh("cp -f " + core.getAbsolutePath() + " " + BIN_DIR + "/libstable_diffusion_core.so", 120);
        if (cp1.contains("[error]")) return "拷贝 core 失败: " + cp1;

        String cp2 = sh("cp -f " + dit.getAbsolutePath() + " " + BIN_DIR + "/libdit_engine.so", 120);
        if (cp2.contains("[error]")) return "拷贝 engine 失败: " + cp2;

        String cp3 = sh("cp -f " + tmpScript.getAbsolutePath() + " " + SCRIPT, 30);
        if (cp3.contains("[error]")) return "拷贝脚本失败: " + cp3;

        String fin = sh(
                "chmod 755 " + BIN_DIR + "/*.so && " +
                "chmod 755 " + SCRIPT + " && " +
                "echo " + DEPLOY_VER + " > " + BIN_DIR + "/.ver && " +
                "echo DONE", 30);

        return fin.contains("DONE") ? "ok" : ("收尾失败: " + fin);
    }

    // ─────────────────────────────────────────────────────────
    // 控制
    // ─────────────────────────────────────────────────────────
    public static String start() {
        return sh("sh " + SCRIPT + " start", 40);
    }

    public static String stop() {
        return sh("sh " + SCRIPT + " stop", 25);
    }

    public static String restart() {
        return sh("sh " + SCRIPT + " restart", 50);
    }

    public static String log(int lines) {
        return sh("tail -n " + lines + " " + BIN_DIR + "/backend.log 2>/dev/null", 15);
    }

    /** 清日志（每次生成前调，方便看本轮输出） */
    public static void clearLog() {
        sh(": > " + BIN_DIR + "/backend.log", 10);
    }
}