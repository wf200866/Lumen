package com.lumen.image;

import android.app.AlertDialog;
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

    private TextView tvBackendStatus, tvModelStatus, tvGpuWarn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        cfg = new Config(this);

        EditText etModel  = findViewById(R.id.et_model_dir);
        RadioButton rbCpu = findViewById(R.id.rb_cpu);
        RadioButton rbGpu = findViewById(R.id.rb_opencl);
        Switch swLora     = findViewById(R.id.sw_lora);
        Switch swUpscale  = findViewById(R.id.sw_upscale);
        SeekBar sbLora    = findViewById(R.id.sb_lora);
        TextView tvLora   = findViewById(R.id.tv_lora);
        SeekBar sbSize    = findViewById(R.id.sb_size);
        TextView tvSize   = findViewById(R.id.tv_size);
        SeekBar sbCfg     = findViewById(R.id.sb_cfg);
        TextView tvCfg    = findViewById(R.id.tv_cfg);
        SeekBar sbThreads = findViewById(R.id.sb_threads);
        TextView tvThreads= findViewById(R.id.tv_threads);
        Button btnSave    = findViewById(R.id.btn_save_settings);
        Button btnStart   = findViewById(R.id.btn_be_start);
        Button btnStop    = findViewById(R.id.btn_be_stop);
        Button btnLog     = findViewById(R.id.btn_be_log);
        Button btnGpuStop = findViewById(R.id.btn_gpu_stop);

        tvModelStatus   = findViewById(R.id.tv_model_status);
        tvBackendStatus = findViewById(R.id.tv_backend_status);
        tvGpuWarn       = findViewById(R.id.tv_gpu_warn);

        etModel.setText(cfg.modelDir());
        (cfg.isGpu() ? rbGpu : rbCpu).setChecked(true);
        swLora.setChecked(cfg.useLora());
        swUpscale.setChecked(cfg.upscale2x());
        tvGpuWarn.setVisibility(cfg.isGpu() ? TextView.VISIBLE : TextView.GONE);

        // ── 后端切换：选 GPU 时弹风险确认 ──
        rbGpu.setOnCheckedChangeListener((v, checked) -> {
            tvGpuWarn.setVisibility(checked ? TextView.VISIBLE : TextView.GONE);
            if (checked && !cfg.gpuRiskAccepted()) askGpuRisk();
        });

        // ── LoRA ──
        sbLora.setMax(200);
        sbLora.setProgress((int) (cfg.loraStrength() * 100));
        tvLora.setText(String.format(java.util.Locale.US, "%.2f", cfg.loraStrength()));
        sbLora.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvLora.setText(String.format(java.util.Locale.US, "%.2f", p / 100f));
            }
        });

        // ── 尺寸 ──
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

        // ── CFG ──
        sbCfg.setMax(300);
        sbCfg.setProgress((int) (cfg.cfg() * 10));
        tvCfg.setText(String.format(java.util.Locale.US, "%.1f", cfg.cfg()));
        sbCfg.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvCfg.setText(String.format(java.util.Locale.US, "%.1f", p / 10f));
            }
        });

        // ── 线程 ──
        sbThreads.setMax(8);   // 0=自动，1~8 手动
        sbThreads.setProgress(cfg.threads());
        tvThreads.setText("CPU 线程 " + cfg.threads() + "（共 " + Runtime.getRuntime().availableProcessors() + " 核）");
        sbThreads.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = (p <= 0) ? Config.defaultThreads() : p;
                tvThreads.setText("CPU 线程 " + v + "（共 " + Runtime.getRuntime().availableProcessors() + " 核）");
            }
        });

        checkModel(etModel.getText().toString().trim());

        // ── 保存 ──
        btnSave.setOnClickListener(v -> {
            cfg.setModelDir(etModel.getText().toString().trim());
            cfg.setBackend(rbGpu.isChecked() ? "opencl" : "cpu");
            cfg.setUseLora(swLora.isChecked());
            cfg.setUpscale2x(swUpscale.isChecked());
            cfg.setLoraStrength(sbLora.getProgress() / 100f);
            cfg.setSize(SIZES[sbSize.getProgress()]);
            cfg.setCfg(sbCfg.getProgress() / 10f);
            cfg.setThreads(sbThreads.getProgress());
            checkModel(cfg.modelDir());
            Toast.makeText(this, "已保存（后端参数下次启动生效）", Toast.LENGTH_LONG).show();
        });

        // ── 启动：按当前选择走 CPU 或 GPU 路径 ──
        btnStart.setOnClickListener(v -> runRoot("启动后端", () -> {
            boolean gpu = cfg.isGpu();

            if (gpu) {
                if (!cfg.gpuRiskAccepted()) return "请先在设置里确认 GPU 风险";
                if (!GpuBackend.isDeployed()) {
                    String r = GpuBackend.deploy(this);
                    if (!"ok".equals(r)) return "GPU 部署失败: " + r;
                }
                // GPU 侧参数：线程≤6，带宽限 550，显存 2.5G
                return GpuBackend.start(cfg.modelDir(),
                        Math.min(6, cfg.threads()), 550, 2.5f);
            }

            // CPU 路径
            if (!RootBackend.isDeployed()) {
                String r = RootBackend.deploy(this);
                if (!"ok".equals(r)) return "部署失败: " + r;
            }
            return RootBackend.start();
        }));

        btnStop.setOnClickListener(v -> runRoot("停止后端", () -> {
            if (cfg.isGpu()) { GpuBackend.stop(); }
            return RootBackend.stop();
        }));

        btnLog.setOnClickListener(v -> runRoot("读取日志", () -> {
            String a = RootBackend.log(15);
            if (cfg.isGpu()) {
                String c = GpuBackend.log(15);
                if (c != null && !c.isEmpty()) a = a + "\n──── GPU ────\n" + c;
            }
            return a;
        }));

        // ── GPU 紧急停止 ──
        btnGpuStop.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("紧急停止")
                .setMessage("杀掉所有后端进程，并把 GPU 频率/带宽/唤醒优先级恢复出厂值。")
                .setPositiveButton("执行", (d, w) -> runRoot("紧急停止", () -> {
                    String r = GpuBackend.stop();
                    GpuBackend.restoreHardware();
                    return r + "\n已恢复硬件默认值";
                }))
                .setNegativeButton("取消", null)
                .show());

        refreshBackend();
    }

    /** GPU 风险确认弹窗 —— 一次确认，之后不再弹 */
    private void askGpuRisk() {
        new AlertDialog.Builder(this)
                .setTitle("启用 GPU 加速？")
                .setMessage(
                        "实测情况（你的设备 SM8475 / Adreno 730）：\n\n" +
                        "· GPU 与 CPU 共享内存总线，跑图时会抢占带宽\n" +
                        "· 严重时会导致界面卡顿、触摸延迟\n" +
                        "· 已自动限制带宽 + 降低调度优先级，大幅降低风险，\n" +
                        "  但无法 100% 消除\n" +
                        "· 速度并不比 CPU 快（两者都在 7~9 分钟）\n\n" +
                        "建议：只在手机闲置、不需要操作时使用。\n" +
                        "随时可以点「GPU 紧急停止 + 恢复系统」回滚。")
                .setPositiveButton("我知道了，启用", (d, w) -> cfg.setGpuRiskAccepted(true))
                .setNegativeButton("还是用 CPU", (d, w) -> {
                    cfg.setGpuRiskAccepted(false);
                    cfg.setBackend("cpu");
                    recreate();
                })
                .setCancelable(false)
                .show();
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
            boolean cpuPort = RootBackend.pingPort();
            boolean gpuPort = GpuBackend.ping();
            String st = RootBackend.status();
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("CPU 后端 ").append(cpuPort ? "通 ✓" : "不通");
                sb.append("　GPU 后端 ").append(gpuPort ? "通 ✓" : "不通");
                sb.append("\nCPU 状态: ").append(st);
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