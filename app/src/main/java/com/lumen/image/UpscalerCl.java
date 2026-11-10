package com.lumen.image;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * OpenCL 超分桥。
 *
 * 底层是 Lanczos3 分离滤波，两趟 kernel 跑在 Adreno GPU 上：
 *   水平 W→W*2 → 中间态 → 垂直 H→H*2
 * 权重在 C 侧预计算后作为只读 buffer 传进去，kernel 里只做乘加。
 *
 * 为什么用 OpenCL 而不是纯 Java：
 *   纯 Java 版（Upscaler.java）在 512→1024 上实测要 800ms 以上，
 *   而且原始实现的权重计算有 bug（每像素用同一组权重）。
 *   GPU 版同样尺寸在 Adreno 730 上是 30-60ms，差一个数量级。
 *
 * 加载策略：
 *   1. 优先用 libOpenCL.so（dlopen 方式，见 C 侧注释）
 *   2. 任何一步失败都返回 null，调用方自动回退到 CPU 实现
 *   3. 永不抛异常——超分是可选后处理，不能因为它拖垮主流程
 */
public final class UpscalerCl {

    private static final String TAG = "LumenUpCL";

    /** 加载状态：null=未探测, TRUE=可用, FALSE=不可用 */
    private static Boolean sAvailable = null;
    private static String  sReason    = "";

    private UpscalerCl() {}

    static {
        try {
            System.loadLibrary("lumen_up");
        } catch (Throwable t) {
            Log.w(TAG, "liblumen_up.so 加载失败: " + t.getMessage());
        }
    }

    // ── native ────────────────────────────────────────────────
    private static native int nativeInit(String[] errOut);
    private static native int nativeScale(int[] src, int srcW, int srcH,
                                         int[] dst, int dstW, int dstH,
                                         String[] errOut);

    /** OpenCL 是否可用。第一次调用会真正初始化，之后走缓存。 */
    public static synchronized boolean available() {
        if (sAvailable != null) return sAvailable;
        try {
            String[] err = new String[1];
            int rc = nativeInit(err);
            sAvailable = (rc == 1);
            sReason = (err[0] != null) ? err[0] : "";
            if (sAvailable) {
                Log.i(TAG, "OpenCL 超分可用");
            } else {
                Log.w(TAG, "OpenCL 超分不可用: " + sReason);
            }
        } catch (Throwable t) {
            // UnsatisfiedLinkError 等：库没编进来 / 架构不对
            sAvailable = false;
            sReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "OpenCL 超分不可用（native 层）: " + sReason);
        }
        return sAvailable;
    }

    /** 上次初始化失败的原因，用于界面提示 */
    public static String reason() {
        return sReason == null ? "" : sReason;
    }

    /**
     * 2 倍放大。失败返回 null，调用方应回退到 CPU 实现。
     *
     * 注意：输入必须是 ARGB_8888，其它格式先转。
     */
    public static Bitmap upscale2x(Bitmap src) {
        if (src == null) return null;
        if (!available()) return null;

        final int sw = src.getWidth();
        final int sh = src.getHeight();
        final int dw = sw * 2;
        final int dh = sh * 2;
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return null;

        Bitmap in = src;
        if (in.getConfig() != Bitmap.Config.ARGB_8888) {
            try {
                in = src.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                return null;
            }
            if (in == null) return null;
        }

        int[] srcPx = new int[sw * sh];
        int[] dstPx = new int[dw * dh];

        try {
            in.getPixels(srcPx, 0, sw, 0, 0, sw, sh);

            String[] err = new String[1];
            int rc = nativeScale(srcPx, sw, sh, dstPx, dw, dh, err);
            if (rc != 0) {
                Log.w(TAG, "nativeScale 失败: " + err[0]);
                return null;
            }

            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            out.setPixels(dstPx, 0, dw, 0, 0, dw, dh);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "超分异常", t);
            return null;
        } finally {
            // in 是临时副本才回收；src 由调用方负责
            if (in != src) {
                try { in.recycle(); } catch (Throwable ignored) {}
            }
        }
    }
}
