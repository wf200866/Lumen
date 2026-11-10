package com.lumen.image;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全部可调参数。所有值都有合法范围校验。
 */
public class Config {

    private static final String PREF = "lumen";

    private static final String K_MODEL_DIR   = "model_dir";
    private static final String K_BACKEND     = "backend";   // cpu | opencl
    private static final String K_STEPS       = "steps";
    private static final String K_CFG         = "cfg";
    private static final String K_SIZE        = "size";
    private static final String K_LORA        = "lora_strength";
    private static final String K_USE_LORA    = "use_lora";
    private static final String K_LISTEN_ALL  = "listen_all";
    private static final String K_THREADS     = "threads";
    private static final String K_UPSCALE     = "upscale_2x";
    private static final String K_GPU_RISK    = "gpu_risk_accepted";

    /** 默认模型目录：优先 /data 快路径（f2fs 直读 455MB/s），回退到原路径 */
    public static final String FAST_MODEL_DIR = "/data/local/lumen_models";
    public static final String DEFAULT_MODEL_DIR =
            "/storage/emulated/0/Download/Nightmare/models/moss_image_4step";

    public static final int STEPS_MIN = 1, STEPS_MAX = 12;
    public static final int SIZE_MIN = 384, SIZE_MAX = 1024;
    public static final float CFG_MIN = 1.0f, CFG_MAX = 30.0f;

    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 模型目录：自动选可用的那个，不写死 */
    public String modelDir() {
        String v = sp.getString(K_MODEL_DIR, "");
        if (v != null && !v.trim().isEmpty()) return v.trim();
        if (new java.io.File(FAST_MODEL_DIR + "/dit.gguf").exists()) return FAST_MODEL_DIR;
        return DEFAULT_MODEL_DIR;
    }
    public void setModelDir(String v) {
        sp.edit().putString(K_MODEL_DIR, v == null ? "" : v.trim()).apply();
    }

    public String backend() {
        String v = sp.getString(K_BACKEND, "cpu");
        return "opencl".equals(v) ? "opencl" : "cpu";
    }
    public void setBackend(String v) {
        sp.edit().putString(K_BACKEND, "opencl".equals(v) ? "opencl" : "cpu").apply();
    }
    public boolean isGpu() { return "opencl".equals(backend()); }

    public boolean gpuRiskAccepted() { return sp.getBoolean(K_GPU_RISK, false); }
    public void setGpuRiskAccepted(boolean v) { sp.edit().putBoolean(K_GPU_RISK, v).apply(); }

    public int steps() {
        int v = sp.getInt(K_STEPS, 4);
        return clamp(v, STEPS_MIN, STEPS_MAX);
    }
    public void setSteps(int v) {
        sp.edit().putInt(K_STEPS, clamp(v, STEPS_MIN, STEPS_MAX)).apply();
    }

    public float cfg() {
        float v = sp.getFloat(K_CFG, 1.0f);
        return Math.max(CFG_MIN, Math.min(v, CFG_MAX));
    }
    public void setCfg(float v) {
        sp.edit().putFloat(K_CFG, Math.max(CFG_MIN, Math.min(v, CFG_MAX))).apply();
    }

    public int size() {
        int v = sp.getInt(K_SIZE, 512);
        return clamp(v, SIZE_MIN, SIZE_MAX);
    }
    public void setSize(int v) {
        sp.edit().putInt(K_SIZE, clamp(v, SIZE_MIN, SIZE_MAX)).apply();
    }

    public boolean useLora() { return sp.getBoolean(K_USE_LORA, true); }
    public void setUseLora(boolean v) { sp.edit().putBoolean(K_USE_LORA, v).apply(); }

    public float loraStrength() {
        float v = sp.getFloat(K_LORA, 1.0f);
        return Math.max(0f, Math.min(v, 2.0f));
    }
    public void setLoraStrength(float v) {
        sp.edit().putFloat(K_LORA, Math.max(0f, Math.min(v, 2.0f))).apply();
    }

    /** 超分 2×：纯 CPU 后处理，默认关 */
    public boolean upscale2x() { return sp.getBoolean(K_UPSCALE, false); }
    public void setUpscale2x(boolean v) { sp.edit().putBoolean(K_UPSCALE, v).apply(); }

    public boolean listenAll() { return sp.getBoolean(K_LISTEN_ALL, false); }
    public void setListenAll(boolean v) { sp.edit().putBoolean(K_LISTEN_ALL, v).apply(); }

    public int threads() {
        int v = sp.getInt(K_THREADS, 0);
        if (v <= 0) return defaultThreads();
        return clamp(v, 1, 8);
    }
    public void setThreads(int v) {
        sp.edit().putInt(K_THREADS, clamp(v, 0, 8)).apply();
    }

    /** 默认线程数：8 核留 2 个给系统 */
    public static int defaultThreads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(2, n - 2);
    }

    public String loraPath() {
        return modelDir() + "/dmd8.safetensors";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }
}