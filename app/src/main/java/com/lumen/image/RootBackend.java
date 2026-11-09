package com.lumen.image;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * root 模式的后端管理。
 * 后端跑在 root 下，oom_score_adj=-1000，不受 App 生命周期和 LMK 影响。
 */
public class RootBackend {

    private static final String TAG = "LumenRoot";
    public static final String BIN_DIR = "/data/adb/lumen";
    public static final String SCRIPT = BIN_DIR + "/lumen_be.sh";
    public static final int PORT = 8189;

    /** 执行一条 root shell 命令，返回 [exitCode, output] */
    public static String sh(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(p.getOutputStream())) {
                os.writeBytes(cmd + "\n");
                os.writeBytes("echo __EXIT__$?\n");
                os.writeBytes("exit\n");
                os.flush();
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("__EXIT__")) break;
                    sb.append(line).append('\n');
                }
            }
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "[timeout] " + sb; }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "sh 失败: " + cmd, e);
            return "[error] " + e.getMessage();
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /** 检查 root 可用 */
    public static boolean hasRoot() {
        String out = sh("id -u", 8);
        return out.trim().equals("0");
    }

    /** 后端是否已部署 */
    public static boolean isDeployed() {
        String out = sh("[ -x " + BIN_DIR + "/libstable_diffusion_core.so ] && " +
                        "[ -f " + BIN_DIR + "/libdit_engine.so ] && echo YES || echo NO", 10);
        return out.contains("YES");
    }

    /** 部署：从 APK 的 assets 或 nativeLibraryDir 拷过去 */
    public static String deploy(Context ctx) {
        StringBuilder log = new StringBuilder();

        // 1. 先从 nativeLibraryDir 拷（APK 里已解压的那份）
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File core = new File(nativeDir, "libsd_core.so");
        File dit  = new File(nativeDir, "libdit_engine.so");

        if (!core.exists() || !dit.exists()) {
            return "APK 内缺少 native 库";
        }

        // 2. root 下执行拷贝（含启动脚本）
        File tmpScript = new File(ctx.getCacheDir(), "lumen_be.sh");
        try (java.io.InputStream in = ctx.getAssets().open("lumen_be.sh");
             FileOutputStream fo = new FileOutputStream(tmpScript)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }

        String cmd = String.join(" && ",
                "mkdir -p " + BIN_DIR,
                "cp -f " + core.getAbsolutePath() + " " + BIN_DIR + "/libstable_diffusion_core.so",
                "cp -f " + dit.getAbsolutePath()  + " " + BIN_DIR + "/libdit_engine.so",
                "cp -f " + tmpScript.getAbsolutePath() + " " + SCRIPT,
                "chmod 755 " + BIN_DIR + "/*.so " + SCRIPT,
                "echo DEPLOY_OK");
        String out = sh(cmd, 180);
        log.append(out);
        return out.contains("DEPLOY_OK") ? "ok" : ("失败: " + out);
    }

    /** 启动后端 */
    public static String start() {
        return sh("sh " + SCRIPT + " start", 30);
    }

    /** 停止 */
    public static String stop() {
        return sh("sh " + SCRIPT + " stop", 20);
    }

    /** 状态，返回 "RUNNING pid=... rss=... oom_adj=... port=..." 或 "STOPPED" */
    public static String status() {
        return sh("sh " + SCRIPT + " status", 15);
    }

    public static boolean isRunning() {
        return status().startsWith("RUNNING");
    }

    /** 取最近日志 */
    public static String log(int lines) {
        return sh("tail -n " + lines + " " + BIN_DIR + "/backend.log", 15);
    }

    /** 确认端口通 */
    public static boolean pingPort() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}