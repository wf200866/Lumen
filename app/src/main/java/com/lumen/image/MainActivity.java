package com.lumen.image;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final int REQ_NOTIF = 20;

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
    private volatile long lastMs = 0, lastFirstMs = 0;

    private final BroadcastReceiver stateRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String s = i.getStringExtra(BackendService.EXTRA_STATE);
            updateBackendUi(s);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cfg = new Config(this);

        etPrompt  = findViewById(R.id.et_prompt);
        etNeg     = findViewById(R.id.et_neg);
        etSeed    = findViewById(R.id.et_seed);
        btnStart  = findViewById(R.id.btn_start);
        btnStop   = findViewById(R.id.btn_stop);
        btnSave   = findViewById(R.id.btn_save);
        btnSettings = findViewById(R.id.btn_settings);
        tvStatus  = findViewById(R.id.tv_status);
        tvInfo    = findViewById(R.id.tv_info);
        tvSteps   = findViewById(R.id.tv_steps);
        ivImage   = findViewById(R.id.iv_image);
        bar       = findViewById(R.id.pbar);
        sbSteps   = findViewById(R.id.sb_steps);

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
            tvStatus.setText("已取消（后端仍在跑完当前步）");
        });
        btnSave.setOnClickListener(v -> saveImage());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        ivImage.setOnClickListener(v -> {
            if (lastBitmap != null) openFullscreen();
        });

        requestPerms();
        registerReceiver(stateRx, new IntentFilter(BackendService.ACTION_STATE),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);

        BackendService.setLogSink(line -> ui.post(() -> {
            // 只挑关键行显示，别刷屏
            if (line.contains("Initializing backend") || line.contains("Version:")
                    || line.contains("listening") || line.contains("error")
                    || line.contains("ERROR") || line.contains("loading diffusion")) {
                tvStatus.setText(shorten(line, 90));
            }
        }));

        if (!cfg.isGpu()) {
            tvInfo.setText("CPU 模式 · " + cfg.size() + "×" + cfg.size());
        } else {
            tvInfo.setText("GPU (OpenCL) · " + cfg.size() + "×" + cfg.size());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvInfo.setText((cfg.isGpu() ? "GPU (OpenCL)" : "CPU") + " · "
                + cfg.size() + "×" + cfg.size());
        if (EngineClient.ping()) {
            updateBackendUi("running");
        } else {
            updateBackendUi(BackendService.getState());
        }
        // 回到前台时恢复按钮（防止卡在禁用态）
        if (!busy.get()) btnStart.setEnabled(true);
    }

    private void updateBackendUi(String state) {
        switch (state == null ? "stopped" : state) {
            case "starting":
                tvStatus.setText("引擎启动中…（首次加载约 2-3 分钟）");
                break;
            case "running":
                tvStatus.setText("引擎就绪 ✓ 可以生成");
                break;
            case "error":
                tvStatus.setText("引擎启动失败，看日志");
                break;
            default:
                tvStatus.setText("引擎未启动");
        }
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
        tvStatus.setText("生成中…");
        final long t0 = System.currentTimeMillis();

        pool.execute(() -> {
            // 引擎没起来就先拉起来
            if (!EngineClient.ping()) {
                ui.post(() -> tvStatus.setText("引擎未运行，正在启动…"));
                Intent svc = new Intent(this, BackendService.class);
                ContextCompat.startForegroundService(this, svc);

                int waited = 0;
                while (waited < 10 * 60 * 1000 && !EngineClient.ping()) {
                    if (!busy.get()) { ui.post(this::resetUi); return; }
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    waited += 1500;
                    final int sec = waited / 1000;
                    ui.post(() -> tvStatus.setText("引擎加载中… " + sec + "s"));
                }
                if (!EngineClient.ping()) {
                    ui.post(() -> { tvStatus.setText("引擎启动超时"); resetUi(); });
                    return;
                }
            }

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
                    lastMs = totalMs;
                    lastFirstMs = firstMs;
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
                        tvStatus.setText("失败：" + shorten(msg, 80));
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

    /** 启动阶段：只禁用按钮，不动 busy */
    private void lockGenerate() {
        btnStart.setEnabled(false);
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
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(f)));
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private void openFullscreen() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("file://" + dumpTemp()), "image/png");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) { toast("没有能看图的 App"); }
    }

    private String dumpTemp() {
        try {
            File f = new File(getCacheDir(), "view.png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                lastBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (!need.isEmpty())
                ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_NOTIF);
        }
        // Android 11+ 需要「所有文件访问」才能读写 Download 里的模型
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {}
            }
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

    @Override protected void onDestroy() {
        try { unregisterReceiver(stateRx); } catch (Exception ignored) {}
        BackendService.setLogSink(null);
        super.onDestroy();
    }
}