package com.lumen.image;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部可调参数集中在这里，UI 和 Service 共用 */
public class Config {

    private static final String PREF = "lumen";
    private static final String K_MODEL_DIR   = "model_dir";
    private static final String K_BACKEND     = "backend";      // cpu / opencl
    private static final String K_STEPS       = "steps";
    private static final String K_CFG         = "cfg";
    private static final String K_SIZE        = "size";
    private static final String K_LORA        = "lora_strength";
    private static final String K_USE_LORA    = "use_lora";
    private static final String K_PARAMS_MODE = "params_mode";  // disk / ram
    private static final String K_LISTEN_ALL  = "listen_all";
    private static final String K_THREADS     = "threads";

    public static final String DEFAULT_MODEL_DIR =
            "/storage/emulated/0/Download/Nightmare/models/moss_image_4step";

    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String modelDir() {
        return sp.getString(K_MODEL_DIR, DEFAULT_MODEL_DIR);
    }
    public void setModelDir(String v) { sp.edit().putString(K_MODEL_DIR, v).apply(); }

    /** "cpu" 或 "opencl" */
    public String backend() {
        return sp.getString(K_BACKEND, "cpu");
    }
    public void setBackend(String v) { sp.edit().putString(K_BACKEND, v).apply(); }
    public boolean isGpu() { return "opencl".equals(backend()); }

    public int steps() { return sp.getInt(K_STEPS, 4); }
    public void setSteps(int v) { sp.edit().putInt(K_STEPS, v).apply(); }

    public float cfg() { return sp.getFloat(K_CFG, 1.0f); }
    public void setCfg(float v) { sp.edit().putFloat(K_CFG, v).apply(); }

    public int size() { return sp.getInt(K_SIZE, 512); }
    public void setSize(int v) { sp.edit().putInt(K_SIZE, v).apply(); }

    public boolean useLora() { return sp.getBoolean(K_USE_LORA, true); }
    public void setUseLora(boolean v) { sp.edit().putBoolean(K_USE_LORA, v).apply(); }

    public float loraStrength() { return sp.getFloat(K_LORA, 1.0f); }
    public void setLoraStrength(float v) { sp.edit().putFloat(K_LORA, v).apply(); }

    /** "disk" 省内存，"ram" 可能更快但吃 4 GB */
    public String paramsMode() { return sp.getString(K_PARAMS_MODE, "disk"); }
    public void setParamsMode(String v) { sp.edit().putString(K_PARAMS_MODE, v).apply(); }

    public boolean listenAll() { return sp.getBoolean(K_LISTEN_ALL, false); }
    public void setListenAll(boolean v) { sp.edit().putBoolean(K_LISTEN_ALL, v).apply(); }

    public int threads() {
        int def = Math.max(4, Runtime.getRuntime().availableProcessors());
        return sp.getInt(K_THREADS, def);
    }
    public void setThreads(int v) { sp.edit().putInt(K_THREADS, v).apply(); }

    public String loraPath() {
        return modelDir() + "/dmd8.safetensors";
    }
}