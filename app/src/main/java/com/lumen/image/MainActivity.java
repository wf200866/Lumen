package com.lumen.image;

import android.Manifest;
import android.content.Context;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LumenMain";
    private static final int REQ_PERM = 20;

    // 跨 Activity 重建保留
    private static final String K_PROMPT = "v_prompt";
    private static final String K_NEG    = "v_neg";
    private static final String K_SEED   = "v_seed";
    private static final String K_STATUS = "v_status";
    private static final String K_BUSY   = "v_busy";

    private Config cfg;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private EditText etPrompt, etNeg, etSeed;
    private Button btnStart, btnStop, btnSave, btnSettings;
    private TextView tvStatus, tvInfo, tvSteps;
    private ImageView ivImage;
    private ProgressBar bar;
    private SeekBar sbSteps;

    private Bitmap lastBitmap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cfg = new Config(this);

        etPrompt    = findViewById(R.id.et_prompt);
        etNeg       = findViewById(R.id.et_neg);
        etSeed      = findViewById(R.id.et_seed);
        btnStart    = findViewById(R.id.btn_start);
        btnStop     = findViewById(R.id.btn_stop);
        btnSave     = findViewById(R.id.btn_save);
        btnSettings = findViewById(R.id.btn_settings);
        tvStatus    = findViewById(R.id.tv_status);
        tvInfo      = findViewById(R.id.tv_info);
        tvSteps     = findViewById(R.id.tv_steps);
        ivImage     = findViewById(R.id.iv_image);
        bar         = findViewById(R.id.pbar);
        sbSteps     = findViewById(R.id.sb_steps);

        sbSteps.setMax(20);
        sbSteps.setProgress(cfg.steps());
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
            busy.set(false);
            tvStatus.setText("已取消等待（后端会跑完当前任务）");
            resetUi();
        });
        btnSave.setOnClickListener(v -> saveImage());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ivImage.setOnClickListener(v -> { if (lastBitmap != null) openFullscreen(); });

        requestPerms();
        refreshStatusAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvInfo.setText((cfg.isGpu() ? "GPU (OpenCL)" : "CPU") + " · "
                + cfg.size() + "×" + cfg.size());
        if (!busy.get()) btnStart.setEnabled(true);
        refreshStatusAsync();
    }

    /** 后台查后端状态，不阻塞 UI */
    private void refreshStatusAsync() {
        pool.execute(() -> {
            String st = RootBackend.status();
            boolean port = RootBackend.pingPort();
            ui.post(() -> {
                if (busy.get()) return;
                if (st.startsWith("RUNNING") && port) {
                    // 从 "RUNNING pid=123 rss=4400MB oom_adj=-1000 port=1" 取 rss
                    String extra = "";
                    int i = st.indexOf("rss=");
                    if (i >= 0) {
                        int j = st.indexOf("MB", i);
                        if (j > i) extra = " · " + st.substring(i + 4, j) + " MB";
                    }
                    tvStatus.setText("后端运行中 ✓" + extra);
                } else if (st.startsWith("RUNNING")) {
                    tvStatus.setText("后端启动中…（加载模型）");
                } else {
                    tvStatus.setText("后端未启动，点生成自动拉起");
                }
            });
        });
    }

    private void doGenerate() {
        if (busy.get()) { toast("正在生成中"); return; }

        String prompt = etPrompt.getText().toString().trim();
        if (TextUtils.isEmpty(prompt)) { toast("先写 prompt"); return; }

        final int size = cfg.size();
        EngineClient.Request req = new EngineClient.Request();
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

        busy.set(true);
        btnStart.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        tvStatus.setText("准备中…");
        final long t0 = System.currentTimeMillis();

        pool.execute(() -> {
            // ── 1. 确保后端在跑 ──────────────────────────
            if (!RootBackend.pingPort()) {
                ui.post(() -> tvStatus.setText("启动后端…"));

                if (!RootBackend.hasRoot()) {
                    ui.post(() -> { tvStatus.setText("没有 root 权限"); resetUi(); });
                    return;
                }

                if (!RootBackend.isDeployed()) {
                    ui.post(() -> tvStatus.setText("首次部署后端（约 65 MB）…"));
                    String r = RootBackend.deploy(this);
                    if (!"ok".equals(r)) {
                        ui.post(() -> { tvStatus.setText("部署失败：" + shorten(r, 70)); resetUi(); });
                        return;
                    }
                }

                RootBackend.start();

                int waited = 0;
                while (waited < 5 * 60 * 1000) {
                    if (!busy.get()) { ui.post(this::resetUi); return; }
                    if (RootBackend.pingPort()) break;
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                    waited += 1200;
                    final int sec = waited / 1000;
                    ui.post(() -> tvStatus.setText("后端加载中… " + sec + "s"));
                }
                if (!RootBackend.pingPort()) {
                    String lg = RootBackend.log(5);
                    ui.post(() -> { tvStatus.setText("启动超时：" + shorten(lg, 70)); resetUi(); });
                    return;
                }
            }

            // ── 2. 提交生成 ──────────────────────────────
            ui.post(() -> {
                tvStatus.setText("已提交，等待第一步…");
                bar.setIndeterminate(false);
                bar.setMax(100);
                bar.setProgress(0);
            });

            EngineClient.generate(req, new EngineClient.Callback() {
                @Override public void onProgress(int step, int total) {
                    ui.post(() -> {
                        bar.setProgress(total > 0 ? step * 100 / total : 0);
                        long el = (System.currentTimeMillis() - t0) / 1000;
                        tvStatus.setText("第 " + step + "/" + total + " 步 · " + el + "s");
                    });
                }
                @Override public void onPreview(Bitmap bmp) {
                    ui.post(() -> ivImage.setImageBitmap(bmp));
                }
                @Override public void onImage(Bitmap bmp, long totalMs, long firstMs) {
                    lastBitmap = bmp;
                    ui.post(() -> {
                        ivImage.setImageBitmap(bmp);
                        tvStatus.setText(String.format(Locale.US,
                                "完成 · 总 %d 秒 · 首步 %.1f 秒",
                                totalMs / 1000, firstMs / 1000.0));
                        bar.setProgress(100);
                        resetUi();
                    });
                }
                @Override public void onError(String msg) {
                    ui.post(() -> {
                        tvStatus.setText("失败：" + shorten(msg, 90));
                        resetUi();
                    });
                }
            });
        });
    }

    private void resetUi() {
        busy.set(false);
        btnStart.setEnabled(true);
        bar.setVisibility(View.GONE);
    }

    private void saveImage() {
        if (lastBitmap == null) { toast("还没有图"); return; }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Lumen");
            if (!dir.exists()) dir.mkdirs();
            String name = "lumen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".png";
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                lastBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            toast("已保存 " + f.getAbsolutePath());
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void openFullscreen() {
        try {
            File f = new File(getCacheDir(), "view.png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                lastBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), "image/png");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { toast("打不开"); }
    }

    private void requestPerms() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);

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
        out.putString(K_PROMPT, etPrompt.getText().toString());
        out.putString(K_NEG, etNeg.getText().toString());
        out.putString(K_SEED, etSeed.getText().toString());
        out.putString(K_STATUS, tvStatus.getText().toString());
        out.putBoolean(K_BUSY, busy.get());
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        if (in == null) return;
        etPrompt.setText(in.getString(K_PROMPT, ""));
        etNeg.setText(in.getString(K_NEG, ""));
        etSeed.setText(in.getString(K_SEED, ""));
        tvStatus.setText(in.getString(K_STATUS, ""));
        if (in.getBoolean(K_BUSY, false)) {
            busy.set(true);
            btnStart.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
        }
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