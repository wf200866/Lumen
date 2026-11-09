package com.lumen.image;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责把 libsd_core.so 作为子进程拉起来，并保持它活着。
 * 所有参数沿用已验证可用的那一套（纯 CPU，绕开 NPU）。
 */
public class BackendService extends Service {

    private static final String TAG = "LumenBackend";
    private static final String CHANNEL_ID = "lumen_backend";
    private static final int NOTIF_ID = 1001;

    public static final int PORT = 8189;

    private Process proc;
    private Thread reader;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;

    /** 日志回调，MainActivity 挂上来就能实时看 */
    public interface LogSink { void onLine(String line); }
    private static volatile LogSink sink;
    public static void setLogSink(LogSink s) { sink = s; }

    /** 状态广播 */
    public static final String ACTION_STATE = "com.lumen.image.BACKEND_STATE";
    public static final String EXTRA_STATE = "state";   // starting / running / stopped / error

    private static volatile String lastState = "stopped";
    public static String getState() { return lastState; }
    private static volatile boolean portReady = false;
    public static boolean isPortReady() { return portReady; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running.get()) {
            Log.i(TAG, "已运行，忽略重复启动");
            return START_STICKY;
        }
        startForegroundCompat();
        acquireWake();
        start();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Lumen 引擎", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("Lumen")
                .setContentText("图像引擎运行中")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lumen::backend");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    /** 把 APK 里的 assets 释放到 files 目录（只做一次，用大小/mtime 判断） */
    public static File ensureAsset(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), "assets");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        try {
            long assetSize = 0;
            try (android.content.res.AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
                assetSize = fd.getLength();
            }
            if (out.exists() && out.length() == assetSize && assetSize > 0) return out;

            Log.i(TAG, "释放 asset " + name + " (" + assetSize + " 字节)");
            try (java.io.InputStream in = ctx.getAssets().open(name);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "释放 asset 失败: " + name, e);
            return null;
        }
    }

    private void start() {
        running.set(true);
        portReady = false;
        publish("starting");

        File logFile = new File(getExternalFilesDir(null), "backend.log");

        new Thread(() -> {
            try {
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                File core = new File(nativeDir, "libsd_core.so");
                if (!core.exists()) {
                    finish("error", "找不到 " + core.getAbsolutePath());
                    return;
                }
                core.setExecutable(true);

                // 模型目录：优先用共享存储里已有的那份
                File modelDir = new File("/storage/emulated/0/Download/Nightmare/models/moss_image_4step");
                if (!new File(modelDir, "dit.gguf").exists()) {
                    modelDir = new File(getExternalFilesDir(null), "models/moss_image_4step");
                }
                if (!new File(modelDir, "dit.gguf").exists()) {
                    finish("error", "模型缺失：" + modelDir.getAbsolutePath());
                    return;
                }

                // tokenizer 指向模型目录里的那份
                File tok = new File(modelDir, "tokenizer.json");

                List<String> cmd = new ArrayList<>();
                cmd.add(core.getAbsolutePath());
                cmd.add("--type");              cmd.add("krea2");
                cmd.add("--model_dir");         cmd.add(modelDir.getAbsolutePath());
                cmd.add("--port");              cmd.add(String.valueOf(PORT));
                cmd.add("--dit_params_backend"); cmd.add("all=disk");
                cmd.add("--listen_all");
                cmd.add("--log_level");         cmd.add("2");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.directory(new File(nativeDir));
                pb.environment().put("LD_LIBRARY_PATH", nativeDir);
                pb.environment().put("ANDROID_DATA", "/data");
                pb.environment().put("ANDROID_ROOT", "/system");
                pb.environment().put("NM_DIT_BACKEND", "diffusion=CPU,te=CPU,vae=CPU");
                pb.environment().put("SD_QWEN35_TOKENIZER", tok.getAbsolutePath());
                // 线程数：大核全用
                int cores = Runtime.getRuntime().availableProcessors();
                pb.environment().put("NM_DIT_THREADS", String.valueOf(Math.max(4, cores)));

                Log.i(TAG, "启动: " + String.join(" ", cmd));
                proc = pb.start();

                reader = new Thread(() -> pump(proc, logFile));
                reader.setDaemon(true);
                reader.start();

                // 等端口
                int waited = 0;
                while (waited < 600_000) {
                    if (!running.get()) return;
                    if (portUp()) { portReady = true; publish("running"); break; }
                    if (!proc.isAlive()) {
                        finish("error", "进程提前退出，看日志");
                        return;
                    }
                    Thread.sleep(800);
                    waited += 800;
                }
                if (!portReady) finish("error", "等待端口超时");

            } catch (Exception e) {
                Log.e(TAG, "启动失败", e);
                finish("error", String.valueOf(e.getMessage()));
            }
        }, "backend-launcher").start();
    }

    private void pump(Process p, File logFile) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
             OutputStream os = new FileOutputStream(logFile, false)) {
            String line;
            while ((line = r.readLine()) != null) {
                Log.i(TAG, line);
                try { os.write((line + "\n").getBytes()); } catch (Exception ignored) {}
                LogSink s = sink;
                if (s != null) s.onLine(line);
            }
        } catch (Exception ignored) {
        } finally {
            if (running.get()) finish("stopped", "后端退出");
        }
    }

    private boolean portUp() {
        try (java.net.Socket sk = new java.net.Socket()) {
            sk.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void finish(String state, String msg) {
        running.set(false);
        portReady = false;
        publish(state);
        if (msg != null) {
            LogSink s = sink;
            if (s != null) s.onLine("[Lumen] " + msg);
        }
        stopSelf();
    }

    private void publish(String state) {
        lastState = state;
        Intent i = new Intent(ACTION_STATE);
        i.putExtra(EXTRA_STATE, state);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BackendService.class));
    }

    @Override
    public void onDestroy() {
        running.set(false);
        portReady = false;
        if (proc != null) { try { proc.destroy(); } catch (Exception ignored) {} }
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        publish("stopped");
        super.onDestroy();
    }
}