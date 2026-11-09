package com.lumen.image;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private static final int[] SIZES = {384, 448, 512, 640, 768, 1024};

    private Config cfg;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvBackendStatus, tvModelStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        cfg = new Config(this);

        EditText etModel  = findViewById(R.id.et_model_dir);
        RadioButton rbCpu = findViewById(R.id.rb_cpu);
        RadioButton rbGpu = findViewById(R.id.rb_opencl);
        Switch swLora     = findViewById(R.id.sw_lora);
        SeekBar sbLora    = findViewById(R.id.sb_lora);
        TextView tvLora   = findViewById(R.id.tv_lora);
        SeekBar sbSize    = findViewById(R.id.sb_size);
        TextView tvSize   = findViewById(R.id.tv_size);
        SeekBar sbCfg     = findViewById(R.id.sb_cfg);
        TextView tvCfg    = findViewById(R.id.tv_cfg);
        Switch swListen   = findViewById(R.id.sw_listen);
        Button btnSave    = findViewById(R.id.btn_save_settings);
        Button btnStart   = findViewById(R.id.btn_be_start);
        Button btnStop    = findViewById(R.id.btn_be_stop);
        Button btnLog     = findViewById(R.id.btn_be_log);

        tvModelStatus   = findViewById(R.id.tv_model_status);
        tvBackendStatus = findViewById(R.id.tv_backend_status);

        etModel.setText(cfg.modelDir());
        (cfg.isGpu() ? rbGpu : rbCpu).setChecked(true);
        swLora.setChecked(cfg.useLora());
        swListen.setChecked(cfg.listenAll());
        swListen.setEnabled(false);   // 暂未接

        sbLora.setMax(200);
        sbLora.setProgress((int) (cfg.loraStrength() * 100));
        tvLora.setText(String.format(java.util.Locale.US, "%.2f", cfg.loraStrength()));
        sbLora.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvLora.setText(String.format(java.util.Locale.US, "%.2f", p / 100f));
            }
        });

        sbSize.setMax(SIZES.length - 1);
        int idx = 2;
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == cfg.size()) idx = i;
        sbSize.setProgress(idx);
        tvSize.setText(cfg.size() + " × " + cfg.size());
        sbSize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = SIZES[p];
                tvSize.setText(v + " × " + v);
            }
        });

        sbCfg.setMax(300);
        sbCfg.setProgress((int) (cfg.cfg() * 10));
        tvCfg.setText(String.format(java.util.Locale.US, "%.1f", cfg.cfg()));
        sbCfg.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvCfg.setText(String.format(java.util.Locale.US, "%.1f", p / 10f));
            }
        });

        checkModel(etModel.getText().toString().trim());

        btnSave.setOnClickListener(v -> {
            cfg.setModelDir(etModel.getText().toString().trim());
            cfg.setBackend(rbGpu.isChecked() ? "opencl" : "cpu");
            cfg.setUseLora(swLora.isChecked());
            cfg.setLoraStrength(sbLora.getProgress() / 100f);
            cfg.setSize(SIZES[sbSize.getProgress()]);
            cfg.setCfg(sbCfg.getProgress() / 10f);
            checkModel(cfg.modelDir());
            Toast.makeText(this, "已保存（后端参数下次启动生效）", Toast.LENGTH_LONG).show();
        });

        btnStart.setOnClickListener(v -> runRoot("启动后端", () -> {
            if (!RootBackend.isDeployed()) {
                String r = RootBackend.deploy(this);
                if (!"ok".equals(r)) return "部署失败: " + r;
            }
            return RootBackend.start();
        }));

        btnStop.setOnClickListener(v -> runRoot("停止后端", RootBackend::stop));

        btnLog.setOnClickListener(v -> runRoot("读取日志", () -> RootBackend.log(20)));

        refreshBackend();
    }

    private interface RootTask { String run(); }

    private void runRoot(String label, RootTask task) {
        tvBackendStatus.setText(label + "…");
        pool.execute(() -> {
            String r = task.run();
            ui.post(() -> {
                tvBackendStatus.setText(r == null || r.isEmpty() ? label + "完成" : r);
                refreshBackend();
            });
        });
    }

    private void refreshBackend() {
        pool.execute(() -> {
            boolean port = RootBackend.pingPort();
            String st = RootBackend.status();
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("端口: ").append(port ? "通 ✓" : "不通");
                sb.append("\n状态: ").append(st);
                if (st.startsWith("RUNNING")) {
                    int rss = RootBackend.parseRss(st);
                    if (rss > 0) sb.append("\n内存: ").append(rss).append(" MB");
                }
                tvBackendStatus.setText(sb.toString());
            });
        });
    }

    private void checkModel(String dir) {
        String[] need = {"dit.gguf", "llm.gguf", "vae.safetensors", "tokenizer.json", "dmd8.safetensors"};
        pool.execute(() -> {
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for (String n : need) {
                File f = new File(dir, n);
                boolean ok = f.exists() && f.length() > 1024;
                if (!ok) allOk = false;
                sb.append(ok ? "✓ " : "✗ ").append(n);
                if (ok) sb.append("  ").append(f.length() / 1024 / 1024).append(" MB");
                sb.append('\n');
            }
            sb.append(allOk ? "\n模型完整" : "\n模型缺失或为空");
            final String txt = sb.toString();
            ui.post(() -> tvModelStatus.setText(txt));
        });
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}