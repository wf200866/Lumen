package com.lumen.image;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    private Config cfg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        cfg = new Config(this);

        EditText etModel   = findViewById(R.id.et_model_dir);
        RadioGroup rgBack  = findViewById(R.id.rg_backend);
        RadioButton rbCpu  = findViewById(R.id.rb_cpu);
        RadioButton rbGpu  = findViewById(R.id.rb_opencl);
        RadioGroup rgParam = findViewById(R.id.rg_params);
        RadioButton rbDisk = findViewById(R.id.rb_disk);
        RadioButton rbRam  = findViewById(R.id.rb_ram);
        Switch swLora      = findViewById(R.id.sw_lora);
        SeekBar sbLora     = findViewById(R.id.sb_lora);
        TextView tvLora    = findViewById(R.id.tv_lora);
        SeekBar sbSize     = findViewById(R.id.sb_size);
        TextView tvSize    = findViewById(R.id.tv_size);
        SeekBar sbCfg      = findViewById(R.id.sb_cfg);
        TextView tvCfg     = findViewById(R.id.tv_cfg);
        Switch swListen    = findViewById(R.id.sw_listen);
        Button btnSave     = findViewById(R.id.btn_save_settings);
        TextView tvStatus  = findViewById(R.id.tv_model_status);

        etModel.setText(cfg.modelDir());

        (cfg.isGpu() ? rbGpu : rbCpu).setChecked(true);
        ("ram".equals(cfg.paramsMode()) ? rbRam : rbDisk).setChecked(true);
        swLora.setChecked(cfg.useLora());
        swListen.setChecked(cfg.listenAll());

        sbLora.setMax(200);                       // 0 - 2.0
        sbLora.setProgress((int) (cfg.loraStrength() * 100));
        tvLora.setText(String.format("%.2f", cfg.loraStrength()));
        sbLora.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvLora.setText(String.format("%.2f", p / 100f));
            }
        });

        sbSize.setMax(5);                         // 索引
        final int[] sizes = {384, 448, 512, 640, 768, 1024};
        int idx = 2;
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == cfg.size()) idx = i;
        sbSize.setProgress(idx);
        tvSize.setText(cfg.size() + " × " + cfg.size());
        sbSize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = sizes[p];
                tvSize.setText(v + " × " + v);
            }
        });

        sbCfg.setMax(200);                        // 0 - 20.0
        sbCfg.setProgress((int) (cfg.cfg() * 10));
        tvCfg.setText(String.format("%.1f", cfg.cfg()));
        sbCfg.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvCfg.setText(String.format("%.1f", p / 10f));
            }
        });

        // 更新模型状态
        Runnable checkModel = () -> {
            String dir = etModel.getText().toString().trim();
            String[] need = {"dit.gguf", "llm.gguf", "vae.safetensors", "tokenizer.json"};
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (String n : need) {
                File f = new File(dir, n);
                boolean has = f.exists() && f.length() > 0;
                if (!has) ok = false;
                sb.append(has ? "✓ " : "✗ ").append(n);
                if (has) sb.append(" (").append(f.length() / 1024 / 1024).append("MB)");
                sb.append("\n");
            }
            File lora = new File(dir, "dmd8.safetensors");
            sb.append(lora.exists() ? "✓ " : "✗ ").append("dmd8.safetensors\n");
            sb.append(ok ? "\n模型完整" : "\n模型缺失");
            tvStatus.setText(sb.toString());
        };
        checkModel.run();

        btnSave.setOnClickListener(v -> {
            cfg.setModelDir(etModel.getText().toString().trim());
            cfg.setBackend(rbGpu.isChecked() ? "opencl" : "cpu");
            cfg.setParamsMode(rbRam.isChecked() ? "ram" : "disk");
            cfg.setUseLora(swLora.isChecked());
            cfg.setLoraStrength(sbLora.getProgress() / 100f);
            cfg.setSize(sizes[sbSize.getProgress()]);
            cfg.setCfg(sbCfg.getProgress() / 10f);
            cfg.setListenAll(swListen.isChecked());
            checkModel.run();
            Toast.makeText(this, "已保存，重启引擎后生效", Toast.LENGTH_LONG).show();
        });
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}