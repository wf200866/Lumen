package com.lumen.image;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全部可调参数。
 * 注意：所有值都有合法范围校验，避免写进后端不认识的参数。
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

    public static final String DEFAULT_MODEL_DIR =
            "/storage/emulated/0/Download/Nightmare/models/moss_image_4step";

    // 合法范围
    public static final int STEPS_MIN = 1, STEPS_MAX = 12;
    public static final int SIZE_MIN = 384, SIZE_MAX = 1024;
    public static final float CFG_MIN = 1.0f, CFG_MAX = 30.0f;

    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String modelDir() {
        String v = sp.getString(K_MODEL_DIR, DEFAULT_MODEL_DIR);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_MODEL_DIR : v.trim();
    }
    public void setModelDir(String v) {
        sp.edit().putString(K_MODEL_DIR, v == null ? DEFAULT_MODEL_DIR : v.trim()).apply();
    }

    /** "cpu" 或 "opencl"（只有这两个合法） */
    public String backend() {
        String v = sp.getString(K_BACKEND, "cpu");
        return "opencl".equals(v) ? "opencl" : "cpu";
    }
    public void setBackend(String v) {
        sp.edit().putString(K_BACKEND, "opencl".equals(v) ? "opencl" : "cpu").apply();
    }
    public boolean isGpu() { return "opencl".equals(backend()); }

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

    public boolean listenAll() { return sp.getBoolean(K_LISTEN_ALL, false); }
    public void setListenAll(boolean v) { sp.edit().putBoolean(K_LISTEN_ALL, v).apply(); }

    public int threads() {
        int def = Math.max(4, Runtime.getRuntime().availableProcessors());
        int v = sp.getInt(K_THREADS, def);
        return clamp(v, 1, 16);
    }
    public void setThreads(int v) {
        sp.edit().putInt(K_THREADS, clamp(v, 1, 16)).apply();
    }

    public String loraPath() {
        return modelDir() + "/dmd8.safetensors";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }
}