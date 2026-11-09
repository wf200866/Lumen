package com.lumen.image;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LumenMain";
    private static final int REQ_PERM = 20;

    // ── 跨 Activity 实例保活的状态（static）────────────────
    static final AtomicBoolean BUSY       = new AtomicBoolean(false);
    static final AtomicLong    T_START    = new AtomicLong(0);
    static volatile String     PHASE      = "";
    static volatile int        PROGRESS   = 0;
    static volatile boolean    INDETERMINATE = true;
    static volatile Bitmap     CURRENT_BMP = null;
    static volatile boolean    CANCEL_FLAG = false;

    private Config cfg;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etPrompt, etNeg, etSeed;
    private Button btnStart, btnStop, btnSave, btnSettings;
    private TextView tvStatus, tvInfo, tvSteps, tvPlaceholder;
    private ImageView ivImage;
    private ProgressBar bar;
    private SeekBar sbSteps;

    /** 每秒刷新已用时间，不依赖后端事件 */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!BUSY.get()) return;
            long el = (System.currentTimeMillis() - T_START.get()) / 1000;
            String base = PHASE.isEmpty() ? "生成中" : PHASE;
            tvStatus.setText(base + " · " + fmt(el));
            ui.postDelayed(this, 1000);
        }
    };

    private static String fmt(long sec) {
        if (sec < 60) return sec + "s";
        return String.format(Locale.US, "%d分%02d秒", sec / 60, sec % 60);
    }

    // ────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cfg = new Config(this);

        etPrompt      = findViewById(R.id.et_prompt);
        etNeg         = findViewById(R.id.et_neg);
        etSeed        = findViewById(R.id.et_seed);
        btnStart      = findViewById(R.id.btn_start);
        btnStop       = findViewById(R.id.btn_stop);
        btnSave       = findViewById(R.id.btn_save);
        btnSettings   = findViewById(R.id.btn_settings);
        tvStatus      = findViewById(R.id.tv_status);
        tvInfo        = findViewById(R.id.tv_info);
        tvSteps       = findViewById(R.id.tv_steps);
        tvPlaceholder = findViewById(R.id.tv_placeholder);
        ivImage       = findViewById(R.id.iv_image);
        bar           = findViewById(R.id.pbar);
        sbSteps       = findViewById(R.id.sb_steps);

        sbSteps.setMax(12);
        sbSteps.setProgress(Math.min(Math.max(cfg.steps(), 1), 12));
        tvSteps.setText(cfg.steps() + " 步");
        sbSteps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = Math.max(1, p);
                tvSteps.setText(v + " 步");
                cfg.setSteps(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnStart.setOnClickListener(v -> doGenerate());
        btnStop.setOnClickListener(v -> {
            CANCEL_FLAG = true;
            tvStatus.setText("正在取消…");
        });
        btnSave.setOnClickListener(v -> saveImage());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ivImage.setOnClickListener(v -> { if (CURRENT_BMP != null) openFullscreen(); });

        requestPerms();
        restoreState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvInfo.setText((cfg.isGpu() ? "GPU" : "CPU") + " · " + cfg.size() + "×" + cfg.size());
        if (BUSY.get()) startTicker();
        restoreState();
        if (!BUSY.get()) refreshStatus();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }

    // ────────────────────────────────────────────────────────
    private void restoreState() {
        if (CURRENT_BMP != null) {
            ivImage.setImageBitmap(CURRENT_BMP);
            if (tvPlaceholder != null) tvPlaceholder.setVisibility(View.GONE);
        }
        long start = T_START.get();
        if (BUSY.get() && start > 0) {
            btnStart.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(INDETERMINATE);
            bar.setProgress(PROGRESS);
            long el = (System.currentTimeMillis() - start) / 1000;
            String base = PHASE.isEmpty() ? "生成中" : PHASE;
            tvStatus.setText(base + " · " + fmt(el));
            startTicker();
        } else {
            btnStart.setEnabled(true);
            bar.setVisibility(View.GONE);
        }
    }

    private void refreshStatus() {
        pool.execute(() -> {
            boolean port = RootBackend.pingPort();
            String st = RootBackend.status();
            ui.post(() -> {
                if (BUSY.get()) return;
                if (port) {
                    int rss = RootBackend.parseRss(st);
                    tvStatus.setText("后端就绪 ✓" + (rss > 0 ? " · " + rss + " MB" : ""));
                } else if (st.startsWith("RUNNING")) {
                    tvStatus.setText("后端启动中…");
                } else if ("NOTDEPLOYED".equals(st)) {
                    tvStatus.setText("后端未部署，点生成自动部署");
                } else {
                    tvStatus.setText("后端未启动，点生成自动拉起");
                }
            });
        });
    }

    // ────────────────────────────────────────────────────────
    private void doGenerate() {
        if (BUSY.get()) { toast("正在生成中"); return; }

        final String prompt = etPrompt.getText().toString().trim();
        if (TextUtils.isEmpty(prompt)) { toast("先写 prompt"); return; }

        final int size = cfg.size();
        final EngineClient.Request req = new EngineClient.Request();
        req.prompt = prompt;
        req.negative = etNeg.getText().toString().trim();
        req.steps = cfg.steps();
        req.cfg = cfg.cfg();
        req.width = size;
        req.height = size;
        req.loraPath = cfg.useLora() ? cfg.loraPath() : null;
        req.loraStrength = cfg.loraStrength();

        String s = etSeed.getText().toString().trim();
        req.seed = s.isEmpty() ? -1 : parseLong(s, -1);

        BUSY.set(true);
        CANCEL_FLAG = false;
        INDETERMINATE = true;
        PROGRESS = 0;
        T_START.set(System.currentTimeMillis());
        PHASE = "准备中";

        btnStart.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        tvStatus.setText(PHASE);
        startTicker();

        pool.execute(() -> {
            // ── 1. 确保后端在跑 ──────────────────────
            if (!RootBackend.pingPort()) {
                RootBackend.stop();          // 清理僵尸 pid

                setPhase("检查 root");
                if (!RootBackend.hasRoot()) { fail("没有 root 权限，需要 KSU / Magisk"); return; }

                if (!RootBackend.isDeployed()) {
                    setPhase("首次部署（65 MB，约 30 秒）");
                    String r = RootBackend.deploy(this);
                    if (!"ok".equals(r)) { fail("部署失败：" + r); return; }
                }

                RootBackend.clearLog();
                setPhase("启动后端");
                RootBackend.start();

                int waited = 0;
                while (waited < 3 * 60 * 1000) {
                    if (CANCEL_FLAG) { cancelUi(); return; }
                    if (RootBackend.pingPort()) break;
                    if (waited > 5000 && waited % 5000 < 1200) {
                        if (RootBackend.status().startsWith("STOPPED")) {
                            fail("后端启动后立刻退出\n" + shorten(RootBackend.log(8), 300));
                            return;
                        }
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    waited += 1000;
                    PHASE = "加载模型";
                }
                if (!RootBackend.pingPort()) {
                    fail("后端启动超时（3 分钟）\n" + shorten(RootBackend.log(8), 300));
                    return;
                }
            }

            // ── 2. 提交生成 ──────────────────────────
            PHASE = "等待首步";
            INDETERMINATE = false;
            ui.post(() -> {
                bar.setIndeterminate(false);
                bar.setMax(100);
                bar.setProgress(0);
            });

            final AtomicBoolean done = new AtomicBoolean(false);
            final AtomicBoolean died = new AtomicBoolean(false);

            // 看门狗：后端死了要报出来，不能一直转圈
            Thread wd = new Thread(() -> {
                int miss = 0;
                while (!done.get()) {
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                    if (done.get()) return;
                    if (!RootBackend.pingPort()) {
                        if (++miss >= 3) {
                            died.set(true);
                            fail("后端进程意外退出（被系统杀掉或崩溃）\n"
                                    + shorten(RootBackend.log(10), 400));
                            return;
                        }
                    } else miss = 0;
                }
            }, "watchdog");
            wd.setDaemon(true);
            wd.start();

            EngineClient.generate(req, new EngineClient.Callback() {
                @Override public void onProgress(int step, int total) {
                    if (died.get()) return;
                    PROGRESS = total > 0 ? step * 100 / total : 0;
                    PHASE = "第 " + step + "/" + total + " 步";
                    ui.post(() -> bar.setProgress(PROGRESS));
                }

                @Override public void onPreview(Bitmap bmp) {
                    CURRENT_BMP = bmp;
                    ui.post(() -> {
                        ivImage.setImageBitmap(bmp);
                        if (tvPlaceholder != null) tvPlaceholder.setVisibility(View.GONE);
                    });
                }

                @Override public void onImage(Bitmap bmp, long totalMs, long firstMs) {
                    done.set(true);
                    CURRENT_BMP = bmp;
                    PHASE = String.format(Locale.US,
                            "完成 · 共 %d 秒（首步 %.1f 秒）",
                            totalMs / 1000, firstMs / 1000.0);
                    ui.post(() -> {
                        ivImage.setImageBitmap(bmp);
                        if (tvPlaceholder != null) tvPlaceholder.setVisibility(View.GONE);
                        bar.setProgress(100);
                        finishRun();
                        tvStatus.setText(PHASE);
                    });
                }

                @Override public void onError(String msg) {
                    done.set(true);
                    if (!died.get()) fail(msg);
                }
            }, () -> CANCEL_FLAG);

            done.set(true);
        });
    }

    // ────────────────────────────────────────────────────────
    private void setPhase(String s) {
        PHASE = s;
        ui.post(() -> {
            long el = (System.currentTimeMillis() - T_START.get()) / 1000;
            tvStatus.setText(s + " · " + fmt(el));
        });
    }

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void stopTicker() {
        ui.removeCallbacks(ticker);
    }

    private void fail(String msg) {
        PHASE = "";
        Log.e(TAG, "生成失败: " + msg);
        ui.post(() -> {
            stopTicker();
            tvStatus.setText("失败：" + shorten(msg, 260));
            finishRunInner();
            toast("生成失败，详情见状态栏");
        });
    }

    private void cancelUi() {
        ui.post(() -> {
            stopTicker();
            tvStatus.setText("已取消");
            finishRunInner();
        });
    }

    private void finishRun() {
        stopTicker();
        finishRunInner();
    }

    private void finishRunInner() {
        BUSY.set(false);
        btnStart.setEnabled(true);
        bar.setVisibility(View.GONE);
    }

    // ────────────────────────────────────────────────────────
    private void saveImage() {
        if (CURRENT_BMP == null) { toast("还没有图"); return; }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Lumen");
            if (!dir.exists() && !dir.mkdirs()) { toast("无法创建目录"); return; }
            String name = "lumen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".png";
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                CURRENT_BMP.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            toast("已保存 " + f.getName());
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void openFullscreen() {
        try {
            File f = new File(getCacheDir(), "view.png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                CURRENT_BMP.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), "image/png");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { toast("打不开"); }
    }

    // ────────────────────────────────────────────────────────
    private void requestPerms() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty())
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("p", etPrompt.getText().toString());
        out.putString("n", etNeg.getText().toString());
        out.putString("s", etSeed.getText().toString());
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        if (in == null) return;
        etPrompt.setText(in.getString("p", ""));
        etNeg.setText(in.getString("n", ""));
        etSeed.setText(in.getString("s", ""));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }
}