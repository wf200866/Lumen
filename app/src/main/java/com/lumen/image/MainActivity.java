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

/**
 * Lumen 主界面。
 *
 * 关键设计：
 * - 后端是常驻 root 进程，UI 只负责发请求和显示
 * - 生成跑在独立线程，Activity 重建不中断（用静态 holder 保住回调）
 * - 首步慢（100-160s）后面快，进度条按步走
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LumenMain";
    private static final int REQ_PERM = 20;

    private static final String K_PROMPT = "s_prompt";
    private static final String K_NEG    = "s_neg";
    private static final String K_SEED   = "s_seed";

    // ── 跨实例保活的生成状态 ──────────────────────────
    static final AtomicBoolean BUSY = new AtomicBoolean(false);
    static volatile String  STATUS_TEXT = "";
    static volatile int     PROGRESS = 0;
    static volatile boolean INDETERMINATE = true;
    static volatile Bitmap  CURRENT_BMP = null;
    static volatile boolean CANCEL_FLAG = false;

    private Config cfg;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etPrompt, etNeg, etSeed;
    private Button btnStart, btnStop, btnSave, btnSettings;
    private TextView tvStatus, tvInfo, tvSteps;
    private ImageView ivImage;
    private TextView ivPlaceholder;
    private ProgressBar bar;
    private SeekBar sbSteps;

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

        ivPlaceholder = findViewById(R.id.tv_placeholder);

        sbSteps.setMax(12);
        sbSteps.setProgress(Math.min(cfg.steps(), 12));
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
            tvStatus.setText("正在断开…（后端会跑完当前步）");
        });
        btnSave.setOnClickListener(v -> saveImage());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ivImage.setOnClickListener(v -> { if (CURRENT_BMP != null) openFullscreen(); });

        requestPerms();

        // 恢复跨实例状态
        restoreState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvInfo.setText((cfg.isGpu() ? "GPU" : "CPU") + " · " + cfg.size() + "×" + cfg.size());
        restoreState();
        refreshStatus();
    }

    /** 从静态 holder 恢复 UI（Activity 重建 / 切回来时用） */
    private void restoreState() {
        if (CURRENT_BMP != null) {
            ivImage.setImageBitmap(CURRENT_BMP);
            if (ivPlaceholder != null) ivPlaceholder.setVisibility(View.GONE);
        }
        if (!STATUS_TEXT.isEmpty()) tvStatus.setText(STATUS_TEXT);
        if (BUSY.get()) {
            btnStart.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(INDETERMINATE);
            bar.setProgress(PROGRESS);
        } else {
            btnStart.setEnabled(true);
            bar.setVisibility(View.GONE);
        }
    }

    /** 查后端状态（不阻塞 UI） */
    private void refreshStatus() {
        if (BUSY.get()) return;
        pool.execute(() -> {
            boolean port = RootBackend.pingPort();
            String st = RootBackend.status();
            ui.post(() -> {
                if (BUSY.get()) return;
                if (port) {
                    int rss = RootBackend.parseRss(st);
                    STATUS_TEXT = "后端就绪 ✓" + (rss > 0 ? " · " + rss + " MB" : "");
                } else if (st.startsWith("RUNNING")) {
                    STATUS_TEXT = "后端启动中…";
                } else if ("NOTDEPLOYED".equals(st)) {
                    STATUS_TEXT = "后端未部署，点生成自动部署";
                } else {
                    STATUS_TEXT = "后端未启动，点生成自动拉起";
                }
                tvStatus.setText(STATUS_TEXT);
            });
        });
    }

    // ────────────────────────────────────────────────────────
    // 生成
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
        STATUS_TEXT = "准备中…";

        btnStart.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        tvStatus.setText(STATUS_TEXT);

        final long t0 = System.currentTimeMillis();

        pool.execute(() -> {
            // ── 1. 确保后端在跑 ──────────────────────
            if (!RootBackend.pingPort()) {
                setStatus("检查 root…");
                if (!RootBackend.hasRoot()) {
                    fail("没有 root 权限，Lumen 需要 KSU/Magisk");
                    return;
                }

                if (!RootBackend.isDeployed()) {
                    setStatus("首次部署后端（约 65 MB，20-40 秒）…");
                    String r = RootBackend.deploy(this);
                    if (!"ok".equals(r)) { fail("部署失败：" + r); return; }
                    setStatus("部署完成，启动中…");
                }

                RootBackend.clearLog();
                RootBackend.start();

                int waited = 0;
                while (waited < 5 * 60 * 1000) {
                    if (CANCEL_FLAG) { cancelUi(); return; }
                    if (RootBackend.pingPort()) break;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    waited += 1000;
                    final int sec = waited / 1000;
                    setStatus("后端加载模型… " + sec + "s");
                }
                if (!RootBackend.pingPort()) {
                    fail("后端启动超时\n" + shorten(RootBackend.log(6), 200));
                    return;
                }
            }

            // ── 2. 提交生成 ──────────────────────────
            INDETERMINATE = false;
            ui.post(() -> {
                bar.setIndeterminate(false);
                bar.setMax(100);
                bar.setProgress(0);
            });
            setStatus("已提交，首步约 100-160 秒…");

            EngineClient.generate(req, new EngineClient.Callback() {
                @Override public void onProgress(int step, int total) {
                    PROGRESS = total > 0 ? step * 100 / total : 0;
                    long el = (System.currentTimeMillis() - t0) / 1000;
                    STATUS_TEXT = "第 " + step + "/" + total + " 步 · " + el + "s";
                    ui.post(() -> {
                        bar.setProgress(PROGRESS);
                        tvStatus.setText(STATUS_TEXT);
                    });
                }

                @Override public void onPreview(Bitmap bmp) {
                    CURRENT_BMP = bmp;
                    ui.post(() -> {
                        ivImage.setImageBitmap(bmp);
                        if (ivPlaceholder != null) ivPlaceholder.setVisibility(View.GONE);
                    });
                }

                @Override public void onImage(Bitmap bmp, long totalMs, long firstMs) {
                    CURRENT_BMP = bmp;
                    STATUS_TEXT = String.format(Locale.US,
                            "完成 · %d 秒（首步 %.1f 秒）",
                            totalMs / 1000, firstMs / 1000.0);
                    ui.post(() -> {
                        ivImage.setImageBitmap(bmp);
                        if (ivPlaceholder != null) ivPlaceholder.setVisibility(View.GONE);
                        tvStatus.setText(STATUS_TEXT);
                        bar.setProgress(100);
                        finishRun();
                    });
                }

                @Override public void onError(String msg) {
                    fail(msg);
                }
            }, () -> CANCEL_FLAG);
        });
    }

    private void setStatus(String s) {
        STATUS_TEXT = s;
        ui.post(() -> tvStatus.setText(s));
    }

    private void fail(String msg) {
        STATUS_TEXT = "失败：" + shorten(msg, 160);
        Log.e(TAG, "生成失败: " + msg);
        ui.post(() -> {
            tvStatus.setText(STATUS_TEXT);
            finishRun();
            toast("生成失败，详情看状态栏");
        });
    }

    private void cancelUi() {
        STATUS_TEXT = "已取消";
        ui.post(() -> {
            tvStatus.setText("已取消");
            finishRun();
        });
    }

    private void finishRun() {
        BUSY.set(false);
        ui.post(() -> {
            btnStart.setEnabled(true);
            bar.setVisibility(View.GONE);
        });
    }

    // ────────────────────────────────────────────────────────
    // 图片
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
                    != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
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
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        if (in == null) return;
        etPrompt.setText(in.getString(K_PROMPT, ""));
        etNeg.setText(in.getString(K_NEG, ""));
        etSeed.setText(in.getString(K_SEED, ""));
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