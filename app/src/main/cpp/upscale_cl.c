/*
 * Lumen 图像超分 —— OpenCL 实现（Adreno 730 / OpenCL 3.0）
 *
 * 设计要点：
 *   1. libOpenCL.so 用 dlopen 拿，绝不直接链接。
 *      App 进程通过 vendor namespace 能拿到 /vendor/lib64/libOpenCL.so，
 *      但若直接 link，某些设备上会在启动时因为找不到符号而崩。
 *   2. kernel 源码内嵌成字符串，不依赖 assets 解压。
 *   3. Lanczos3 分离滤波：先水平一趟 → 中间态 → 垂直一趟。
 *      复杂度 O(W*H*taps) 而不是 O(W*H*taps^2)。
 *   4. 权重在 CPU 侧预计算（每维 (dstLen * 2*LOBES) 个 float），
 *      作为只读 buffer 传给 GPU，kernel 里只做乘加。
 *   5. 用 RGBA8 图像/buffer 直传，避免格式转换。
 *
 * 坐标约定（跟 CPU 版保持一致）：
 *   源像素 i 的中心在浮点坐标 i
 *   输出像素 j 的中心在 (j + 0.5) * scale - 0.5
 *   采样起点 first = floor(center) - LOBES + 1
 *   距离 d = (center - (first + k)) / max(1, scale)
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

/* NDK 的 math.h 在严格模式下不给 M_PI */
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define TAG "LumenUpCL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ══════════════════════════════════════════════════════════
 * OpenCL 最小声明集
 * 不走 <CL/cl.h>，避免 NDK 版本差异导致头文件缺失。
 * 只声明我们真正用到的函数和类型，运行期从 dlopen 的句柄里取地址。
 * ══════════════════════════════════════════════════════════ */

typedef int32_t  cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef void*    cl_handle;   /* 所有 cl_* 对象统一当句柄 */

/* 常量（值取自 OpenCL 1.2/3.0 规范，必须完全一致） */
#define CL_SUCCESS                          0
#define CL_DEVICE_TYPE_GPU                  (1 << 2)
#define CL_DEVICE_TYPE_ALL                  0xFFFFFFFF
#define CL_MEM_READ_ONLY                    (1 << 2)
#define CL_MEM_WRITE_ONLY                   (1 << 1)
#define CL_MEM_READ_WRITE                   (1 << 0)
#define CL_MEM_COPY_HOST_PTR                (1 << 5)
#define CL_QUEUE_PROFILING_ENABLE           (1 << 1)
#define CL_TRUE                             1
#define CL_FALSE                            0

/* 设备信息常量 */
#define CL_DEVICE_MAX_COMPUTE_UNITS         0x1002
#define CL_DEVICE_GLOBAL_MEM_SIZE           0x101F
#define CL_DEVICE_MAX_WORK_GROUP_SIZE       0x1004
#define CL_DEVICE_NAME                      0x102B

/* 函数指针类型 */
typedef cl_int   (*PFN_clGetPlatformIDs)(cl_uint, cl_handle*, cl_uint*);
typedef cl_int   (*PFN_clGetDeviceIDs)(cl_handle, cl_ulong, cl_uint, cl_handle*, cl_uint*);
typedef cl_handle(*PFN_clCreateContext)(const intptr_t*, cl_uint, const cl_handle*, void*, void*, cl_int*);
typedef cl_handle(*PFN_clCreateCommandQueue)(cl_handle, cl_handle, cl_ulong, cl_int*);
typedef cl_handle(*PFN_clCreateProgramWithSource)(cl_handle, cl_uint, const char**, const size_t*, cl_int*);
typedef cl_int   (*PFN_clBuildProgram)(cl_handle, cl_uint, const cl_handle*, const char*, void*, void*);
typedef cl_handle(*PFN_clCreateKernel)(cl_handle, const char*, cl_int*);
typedef cl_handle(*PFN_clCreateBuffer)(cl_handle, cl_ulong, const void*, cl_int*);
typedef cl_int   (*PFN_clSetKernelArg)(cl_handle, cl_uint, size_t, const void*);
typedef cl_int   (*PFN_clEnqueueNDRangeKernel)(cl_handle, cl_handle, cl_uint, const size_t*,
                                               const size_t*, const size_t*,
                                               cl_uint, const cl_handle*, cl_handle);
typedef cl_int   (*PFN_clEnqueueReadBuffer)(cl_handle, cl_handle, cl_uint, size_t, size_t,
                                            void*, cl_uint, const cl_handle*, cl_handle);
typedef cl_int   (*PFN_clFinish)(cl_handle);
typedef cl_int   (*PFN_clReleaseMemObject)(cl_handle);
typedef cl_int   (*PFN_clReleaseKernel)(cl_handle);
typedef cl_int   (*PFN_clReleaseProgram)(cl_handle);
typedef cl_int   (*PFN_clReleaseCommandQueue)(cl_handle);
typedef cl_int   (*PFN_clReleaseContext)(cl_handle);
typedef cl_int   (*PFN_clGetDeviceInfo)(cl_handle, cl_uint, size_t, void*, size_t*);
typedef cl_int   (*PFN_clGetProgramBuildInfo)(cl_handle, cl_handle, cl_uint, size_t, void*, size_t*);

typedef struct {
    void* lib;
    PFN_clGetPlatformIDs             GetPlatformIDs;
    PFN_clGetDeviceIDs               GetDeviceIDs;
    PFN_clCreateContext              CreateContext;
    PFN_clCreateCommandQueue         CreateCommandQueue;
    PFN_clCreateProgramWithSource    CreateProgramWithSource;
    PFN_clBuildProgram               BuildProgram;
    PFN_clCreateKernel               CreateKernel;
    PFN_clCreateBuffer               CreateBuffer;
    PFN_clSetKernelArg               SetKernelArg;
    PFN_clEnqueueNDRangeKernel       EnqueueNDRangeKernel;
    PFN_clEnqueueReadBuffer          EnqueueReadBuffer;
    PFN_clFinish                     Finish;
    PFN_clReleaseMemObject           ReleaseMemObject;
    PFN_clReleaseKernel              ReleaseKernel;
    PFN_clReleaseProgram             ReleaseProgram;
    PFN_clReleaseCommandQueue        ReleaseCommandQueue;
    PFN_clReleaseContext             ReleaseContext;
    PFN_clGetDeviceInfo              GetDeviceInfo;
    PFN_clGetProgramBuildInfo        GetProgramBuildInfo;
} cl_api;

static cl_api g_cl = {0};
static int    g_ready = -1;   /* -1 未试, 0 失败, 1 就绪 */
static char   g_err[512] = {0};

/* ══════════════════════════════════════════════════════════
 * Kernel 源码
 * ══════════════════════════════════════════════════════════ */

static const char* KERNEL_SRC =
"/* 用 uchar4 按原始字节通道处理。\n"
" * Java int[] 在小端内存里的字节序是 B,G,R,A，\n"
" * 但我们只做同类通道的加权混合，不关心通道语义，\n"
" * 所以按字节独立处理即可，绝不跨通道串味。 */\n"
"__kernel void resample_h(\n"
"    __global const uchar4* src,     /* W_in x H_in  */\n"
"    __global uchar4*       dst,     /* W_out x H_in */\n"
"    __global const float*  weights, /* W_out * TAPS  */\n"
"    __global const int*    firsts,  /* W_out         */\n"
"    const int W_in,\n"
"    const int W_out,\n"
"    const int H,\n"
"    const int TAPS)\n"
"{\n"
"    int x = get_global_id(0);\n"
"    int y = get_global_id(1);\n"
"    if (x >= W_out || y >= H) return;\n"
"\n"
"    int wb = x * TAPS;\n"
"    int first = firsts[x];\n"
"    int row = y * W_in;\n"
"\n"
"    float acc0 = 0.0f, acc1 = 0.0f, acc2 = 0.0f, acc3 = 0.0f;\n"
"    for (int k = 0; k < TAPS; k++) {\n"
"        int sx = first + k;\n"
"        sx = sx < 0 ? 0 : (sx >= W_in ? W_in - 1 : sx);\n"
"        float w = weights[wb + k];\n"
"        uchar4 c = src[row + sx];\n"
"        acc0 += (float)c.s0 * w;\n"
"        acc1 += (float)c.s1 * w;\n"
"        acc2 += (float)c.s2 * w;\n"
"        acc3 += (float)c.s3 * w;\n"
"    }\n"
"\n"
"    uchar4 o;\n"
"    o.s0 = (uchar)convert_int_sat_rte(acc0);\n"
"    o.s1 = (uchar)convert_int_sat_rte(acc1);\n"
"    o.s2 = (uchar)convert_int_sat_rte(acc2);\n"
"    o.s3 = (uchar)convert_int_sat_rte(acc3);\n"
"    dst[y * W_out + x] = o;\n"
"}\n"
"\n"
"__kernel void resample_v(\n"
"    __global const uchar4* src,     /* W x H_in  */\n"
"    __global uchar4*       dst,     /* W x H_out */\n"
"    __global const float*  weights, /* H_out * TAPS */\n"
"    __global const int*    firsts,  /* H_out        */\n"
"    const int W,\n"
"    const int H_in,\n"
"    const int H_out,\n"
"    const int TAPS)\n"
"{\n"
"    int x = get_global_id(0);\n"
"    int y = get_global_id(1);\n"
"    if (x >= W || y >= H_out) return;\n"
"\n"
"    int wb = y * TAPS;\n"
"    int first = firsts[y];\n"
"\n"
"    float acc0 = 0.0f, acc1 = 0.0f, acc2 = 0.0f, acc3 = 0.0f;\n"
"    for (int k = 0; k < TAPS; k++) {\n"
"        int sy = first + k;\n"
"        sy = sy < 0 ? 0 : (sy >= H_in ? H_in - 1 : sy);\n"
"        float w = weights[wb + k];\n"
"        uchar4 c = src[sy * W + x];\n"
"        acc0 += (float)c.s0 * w;\n"
"        acc1 += (float)c.s1 * w;\n"
"        acc2 += (float)c.s2 * w;\n"
"        acc3 += (float)c.s3 * w;\n"
"    }\n"
"\n"
"    uchar4 o;\n"
"    o.s0 = (uchar)convert_int_sat_rte(acc0);\n"
"    o.s1 = (uchar)convert_int_sat_rte(acc1);\n"
"    o.s2 = (uchar)convert_int_sat_rte(acc2);\n"
"    o.s3 = (uchar)convert_int_sat_rte(acc3);\n"
"    dst[y * W + x] = o;\n"
"}\n";

/* ══════════════════════════════════════════════════════════
 * 初始化：dlopen + 取函数指针
 * ══════════════════════════════════════════════════════════ */

#define LOAD_SYM(field, name)                                            \
    do {                                                                 \
        g_cl.field = (PFN_##name)dlsym(g_cl.lib, #name);                 \
        if (!g_cl.field) {                                               \
            snprintf(g_err, sizeof(g_err), "缺少符号 %s", #name);        \
            dlclose(g_cl.lib); g_cl.lib = NULL; return -1;               \
        }                                                                \
    } while (0)

static int cl_init(void) {
    if (g_ready >= 0) return g_ready;

    /* 按优先级找库：先 App 私有目录（部署过的），再 vendor/system */
    static const char* candidates[] = {
        "libOpenCL.so",
        "/data/data/com.lumen.image/lib/libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        NULL
    };

    for (int i = 0; candidates[i]; i++) {
        g_cl.lib = dlopen(candidates[i], RTLD_NOW | RTLD_LOCAL);
        if (g_cl.lib) {
            LOGI("OpenCL 库已加载: %s", candidates[i]);
            break;
        }
    }
    if (!g_cl.lib) {
        snprintf(g_err, sizeof(g_err), "dlopen libOpenCL.so 失败: %s", dlerror());
        LOGE("%s", g_err);
        g_ready = 0;
        return -1;
    }

    LOAD_SYM(GetPlatformIDs,          clGetPlatformIDs);
    LOAD_SYM(GetDeviceIDs,            clGetDeviceIDs);
    LOAD_SYM(CreateContext,           clCreateContext);
    LOAD_SYM(CreateCommandQueue,      clCreateCommandQueue);
    LOAD_SYM(CreateProgramWithSource, clCreateProgramWithSource);
    LOAD_SYM(BuildProgram,            clBuildProgram);
    LOAD_SYM(CreateKernel,            clCreateKernel);
    LOAD_SYM(CreateBuffer,            clCreateBuffer);
    LOAD_SYM(SetKernelArg,            clSetKernelArg);
    LOAD_SYM(EnqueueNDRangeKernel,    clEnqueueNDRangeKernel);
    LOAD_SYM(EnqueueReadBuffer,       clEnqueueReadBuffer);
    LOAD_SYM(Finish,                  clFinish);
    LOAD_SYM(ReleaseMemObject,        clReleaseMemObject);
    LOAD_SYM(ReleaseKernel,           clReleaseKernel);
    LOAD_SYM(ReleaseProgram,          clReleaseProgram);
    LOAD_SYM(ReleaseCommandQueue,     clReleaseCommandQueue);
    LOAD_SYM(ReleaseContext,          clReleaseContext);
    LOAD_SYM(GetDeviceInfo,           clGetDeviceInfo);
    LOAD_SYM(GetProgramBuildInfo,     clGetProgramBuildInfo);

    g_ready = 1;
    LOGI("OpenCL API 全部就绪");
    return 0;
}

/* ══════════════════════════════════════════════════════════
 * Lanczos3 权重预计算（CPU 侧）
 * ══════════════════════════════════════════════════════════ */

#define LOBES 3
#define TAPS  (2 * LOBES)

static float lanczos3(float x) {
    x = fabsf(x);
    if (x < 1e-6f) return 1.0f;
    if (x >= 3.0f) return 0.0f;
    float px = (float)M_PI * x;
    return 3.0f * sinf(px) * sinf(px / 3.0f) / (px * px);
}

/*
 * 为 dstLen 个输出位置生成 TAPS 个权重 + 起点索引。
 * weights: [dstLen * TAPS]  已归一化
 * firsts:  [dstLen]         采样起始像素
 */
static void build_weights(int srcLen, int dstLen, float* weights, int* firsts) {
    float scale  = (float)srcLen / (float)dstLen;
    float fscale = scale > 1.0f ? scale : 1.0f;

    for (int j = 0; j < dstLen; j++) {
        float center = (float)(j + 0.5f) * scale - 0.5f;
        int first = (int)floorf(center) - LOBES + 1;
        firsts[j] = first;

        float sum = 0.0f;
        float* wrow = weights + (size_t)j * TAPS;
        for (int k = 0; k < TAPS; k++) {
            int sx = first + k;
            float d = (center - (float)sx) / fscale;
            float w = lanczos3(d);
            wrow[k] = w;
            sum += w;
        }
        if (fabsf(sum) < 1e-6f) {
            wrow[0] = 1.0f;
            for (int k = 1; k < TAPS; k++) wrow[k] = 0.0f;
        } else {
            for (int k = 0; k < TAPS; k++) wrow[k] /= sum;
        }
    }
}

/* ══════════════════════════════════════════════════════════
 * JNI 入口
 * ══════════════════════════════════════════════════════════ */

/* 把错误消息写进 Java 侧的 String[1] */
static void set_err(JNIEnv* env, jobjectArray jerr, const char* msg) {
    if (!jerr || !msg) return;
    jstring s = (*env)->NewStringUTF(env, msg);
    if (s) {
        (*env)->SetObjectArrayElement(env, jerr, 0, s);
        (*env)->DeleteLocalRef(env, s);
    }
}

/* 返回 OpenCL 是否可用（1=可用，0=不可用）；errArr[0] 里写原因 */
JNIEXPORT jint JNICALL
Java_com_lumen_image_UpscalerCl_nativeInit(JNIEnv* env, jclass cls, jobjectArray jerr) {
    (void)cls;
    int rc = cl_init();
    if (jerr) {
        set_err(env, jerr, (rc == 0) ? "ok" : (g_err[0] ? g_err : "unknown"));
    }
    return rc == 0 ? 1 : 0;
}

/*
 * 主入口：ARGB_8888 的 int[] 输入，输出同样。
 * 返回 0 成功，非 0 失败（errBuf 里写原因）。
 *
 * 为什么传 int[] 而不是 Bitmap：
 *   JNI 里操作 Bitmap 需要额外的 AndroidBitmap 头 + 格式适配，
 *   用 getPixels/setPixels 在 Java 侧做转换更简单也更稳。
 */
JNIEXPORT jint JNICALL
Java_com_lumen_image_UpscalerCl_nativeScale(
        JNIEnv* env, jclass cls,
        jintArray jsrc, jint srcW, jint srcH,
        jintArray jdst, jint dstW, jint dstH,
        jobjectArray jerr) {

    (void)cls;
    cl_int err = CL_SUCCESS;
    cl_handle platform = NULL, device = NULL, ctx = NULL, queue = NULL;
    cl_handle program = NULL, k_h = NULL, k_v = NULL;
    cl_handle b_src = NULL, b_mid = NULL, b_dst = NULL;
    cl_handle b_wh = NULL, b_wv = NULL, b_fh = NULL, b_fv = NULL;
    float*  w_h = NULL; int* f_h = NULL;
    float*  w_v = NULL; int* f_v = NULL;
    jint*   srcPx = NULL;
    jint*   dstPx = NULL;
    int     ret = -1;

    if (cl_init() != 0) {
        set_err(env, jerr, g_err[0] ? g_err : "OpenCL 不可用");
        return -1;
    }

    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
        set_err(env, jerr, "尺寸非法");
        return -1;
    }

    /* ── 1. 取平台 + 设备 ── */
    cl_uint nPlat = 0;
    err = g_cl.GetPlatformIDs(1, &platform, &nPlat);
    if (err != CL_SUCCESS || nPlat == 0) {
        snprintf(g_err, sizeof(g_err), "clGetPlatformIDs 失败 err=%d", err);
        goto done;
    }
    cl_uint nDev = 0;
    err = g_cl.GetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, &nDev);
    if (err != CL_SUCCESS || nDev == 0) {
        /* 没有独立 GPU 类型时退回 ALL */
        err = g_cl.GetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, 1, &device, &nDev);
        if (err != CL_SUCCESS || nDev == 0) {
            snprintf(g_err, sizeof(g_err), "找不到 OpenCL 设备 err=%d", err);
            goto done;
        }
    }

    /* ── 2. context + queue ── */
    ctx = g_cl.CreateContext(NULL, 1, &device, NULL, NULL, &err);
    if (!ctx || err != CL_SUCCESS) {
        snprintf(g_err, sizeof(g_err), "clCreateContext 失败 err=%d", err);
        goto done;
    }
    queue = g_cl.CreateCommandQueue(ctx, device, 0, &err);
    if (!queue || err != CL_SUCCESS) {
        snprintf(g_err, sizeof(g_err), "clCreateCommandQueue 失败 err=%d", err);
        goto done;
    }

    /* ── 3. 编译 program ── */
    {
        const char* src = KERNEL_SRC;
        size_t len = strlen(KERNEL_SRC);
        program = g_cl.CreateProgramWithSource(ctx, 1, &src, &len, &err);
        if (!program || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "clCreateProgramWithSource 失败 err=%d", err);
            goto done;
        }
        err = g_cl.BuildProgram(program, 1, &device, NULL, NULL, NULL);
        if (err != CL_SUCCESS) {
            size_t logLen = 0;
            char* log = NULL;
            g_cl.GetProgramBuildInfo(program, device, 0x1183 /* CL_PROGRAM_BUILD_LOG */,
                                    0, NULL, &logLen);
            if (logLen > 0) {
                log = (char*)malloc(logLen + 1);
                if (log) {
                    g_cl.GetProgramBuildInfo(program, device, 0x1183,
                                             logLen, log, NULL);
                    log[logLen] = 0;
                    snprintf(g_err, sizeof(g_err), "编译失败 err=%d: %.350s", err, log);
                    free(log);
                } else {
                    snprintf(g_err, sizeof(g_err), "编译失败 err=%d", err);
                }
            } else {
                snprintf(g_err, sizeof(g_err), "编译失败 err=%d", err);
            }
            goto done;
        }

        k_h = g_cl.CreateKernel(program, "resample_h", &err);
        if (!k_h || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "创建 resample_h 失败 err=%d", err);
            goto done;
        }
        k_v = g_cl.CreateKernel(program, "resample_v", &err);
        if (!k_v || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "创建 resample_v 失败 err=%d", err);
            goto done;
        }
    }

    /* ── 4. 权重 ── */
    w_h = (float*)malloc(sizeof(float) * (size_t)dstW * TAPS);
    f_h = (int*)  malloc(sizeof(int)   * (size_t)dstW);
    w_v = (float*)malloc(sizeof(float) * (size_t)dstH * TAPS);
    f_v = (int*)  malloc(sizeof(int)   * (size_t)dstH);
    if (!w_h || !f_h || !w_v || !f_v) {
        snprintf(g_err, sizeof(g_err), "CPU 权重内存不足");
        goto done;
    }
    build_weights(srcW, dstW, w_h, f_h);
    build_weights(srcH, dstH, w_v, f_v);

    /* ── 5. 取源像素 ── */
    srcPx = (*env)->GetIntArrayElements(env, jsrc, NULL);
    if (!srcPx) {
        snprintf(g_err, sizeof(g_err), "取源像素失败");
        goto done;
    }

    /* ── 6. buffer ── */
    {
        size_t szSrc = (size_t)srcW * srcH * 4;
        size_t szMid = (size_t)dstW * srcH * 4;
        size_t szDst = (size_t)dstW * dstH * 4;

        b_src = g_cl.CreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                  szSrc, srcPx, &err);
        if (!b_src || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "源 buffer 失败 err=%d", err);
            goto done;
        }
        b_mid = g_cl.CreateBuffer(ctx, CL_MEM_READ_WRITE, szMid, NULL, &err);
        if (!b_mid || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "中间 buffer 失败 err=%d", err);
            goto done;
        }
        b_dst = g_cl.CreateBuffer(ctx, CL_MEM_WRITE_ONLY, szDst, NULL, &err);
        if (!b_dst || err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "目标 buffer 失败 err=%d", err);
            goto done;
        }

        b_wh = g_cl.CreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                 sizeof(float) * (size_t)dstW * TAPS, w_h, &err);
        b_wv = g_cl.CreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                 sizeof(float) * (size_t)dstH * TAPS, w_v, &err);
        b_fh = g_cl.CreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                 sizeof(int) * (size_t)dstW, f_h, &err);
        b_fv = g_cl.CreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                 sizeof(int) * (size_t)dstH, f_v, &err);
        if (!b_wh || !b_wv || !b_fh || !b_fv) {
            snprintf(g_err, sizeof(g_err), "权重 buffer 失败 err=%d", err);
            goto done;
        }
    }

    /* ── 7. 水平一趟 ── */
    {
        int iW = srcW, iOW = dstW, iH = srcH, iT = TAPS;
        g_cl.SetKernelArg(k_h, 0, sizeof(cl_handle), &b_src);
        g_cl.SetKernelArg(k_h, 1, sizeof(cl_handle), &b_mid);
        g_cl.SetKernelArg(k_h, 2, sizeof(cl_handle), &b_wh);
        g_cl.SetKernelArg(k_h, 3, sizeof(cl_handle), &b_fh);
        g_cl.SetKernelArg(k_h, 4, sizeof(int), &iW);
        g_cl.SetKernelArg(k_h, 5, sizeof(int), &iOW);
        g_cl.SetKernelArg(k_h, 6, sizeof(int), &iH);
        g_cl.SetKernelArg(k_h, 7, sizeof(int), &iT);

        size_t gsz[2] = { (size_t)dstW, (size_t)srcH };
        err = g_cl.EnqueueNDRangeKernel(queue, k_h, 2, NULL, gsz, NULL, 0, NULL, NULL);
        if (err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "水平 kernel 失败 err=%d", err);
            goto done;
        }
    }

    /* ── 8. 垂直一趟 ── */
    {
        int iW = dstW, iHin = srcH, iHout = dstH, iT = TAPS;
        g_cl.SetKernelArg(k_v, 0, sizeof(cl_handle), &b_mid);
        g_cl.SetKernelArg(k_v, 1, sizeof(cl_handle), &b_dst);
        g_cl.SetKernelArg(k_v, 2, sizeof(cl_handle), &b_wv);
        g_cl.SetKernelArg(k_v, 3, sizeof(cl_handle), &b_fv);
        g_cl.SetKernelArg(k_v, 4, sizeof(int), &iW);
        g_cl.SetKernelArg(k_v, 5, sizeof(int), &iHin);
        g_cl.SetKernelArg(k_v, 6, sizeof(int), &iHout);
        g_cl.SetKernelArg(k_v, 7, sizeof(int), &iT);

        size_t gsz[2] = { (size_t)dstW, (size_t)dstH };
        err = g_cl.EnqueueNDRangeKernel(queue, k_v, 2, NULL, gsz, NULL, 0, NULL, NULL);
        if (err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "垂直 kernel 失败 err=%d", err);
            goto done;
        }
    }

    /* ── 9. 读回 ── */
    {
        size_t szDst = (size_t)dstW * dstH * 4;
        dstPx = (*env)->GetIntArrayElements(env, jdst, NULL);
        if (!dstPx) {
            snprintf(g_err, sizeof(g_err), "锁目标数组失败");
            goto done;
        }
        err = g_cl.EnqueueReadBuffer(queue, b_dst, CL_TRUE, 0, szDst, dstPx, 0, NULL, NULL);
        g_cl.Finish(queue);
        if (err != CL_SUCCESS) {
            snprintf(g_err, sizeof(g_err), "读回失败 err=%d", err);
            goto done;
        }
    }

    ret = 0;

done:
    if (ret == 0) {
        set_err(env, jerr, "ok");
    } else {
        set_err(env, jerr, g_err[0] ? g_err : "未知错误");
    }
    if (srcPx) (*env)->ReleaseIntArrayElements(env, jsrc, srcPx, JNI_ABORT);
    if (dstPx) (*env)->ReleaseIntArrayElements(env, jdst, dstPx, 0);

    free(w_h); free(f_h); free(w_v); free(f_v);

    if (b_src) g_cl.ReleaseMemObject(b_src);
    if (b_mid) g_cl.ReleaseMemObject(b_mid);
    if (b_dst) g_cl.ReleaseMemObject(b_dst);
    if (b_wh)  g_cl.ReleaseMemObject(b_wh);
    if (b_wv)  g_cl.ReleaseMemObject(b_wv);
    if (b_fh)  g_cl.ReleaseMemObject(b_fh);
    if (b_fv)  g_cl.ReleaseMemObject(b_fv);
    if (k_h)   g_cl.ReleaseKernel(k_h);
    if (k_v)   g_cl.ReleaseKernel(k_v);
    if (program) g_cl.ReleaseProgram(program);
    if (queue)   g_cl.ReleaseCommandQueue(queue);
    if (ctx)     g_cl.ReleaseContext(ctx);

    if (ret != 0) LOGE("nativeScale 失败: %s", g_err);
    return ret;
}