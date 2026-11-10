package com.lumen.image;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPU 后端管理 —— 带护栏的"实验性"加速。
 *
 * ═══ 为什么需要护栏 ═══
 * 实测结论（真机 SM8475 / Adreno 730）：
 *   GPU 跑 DiT 时满载，CPU 与 GPU 共享 LPDDR5 内存控制器，
 *   显示子系统(msm_drm) 拿不到带宽 → 黑屏 / 系统看门狗重启。
 *   纯 GPU 模式必崩，混合模式能出图但手机会卡。
 *
 * 所以这个类做四件事：
 *   1. 限频 + 限带宽（kgsl-busmon）—— 这是唯一能防黑屏的手段
 *   2. 全部写进 root 侧，App 不接管进程生命周期
 *   3. 提供 killGpuAndRestore() —— 任何异常都能一键回滚到安全状态
 *   4. 只允许在"CPU 主流程"之外单独启用，默认关闭
 *
 * ═══ 安全参数（实测得来）═══
 *   GPU 频率    → 可以拉满（快进快出），但必须限带宽
 *   DDR 带宽    → 550MHz 档（上限 905），这是防卡死的关键
 *   wake_nice   → 0（默认 -7，会抢显示唤醒）
 *   CPU 线程    → ≤6（8 核留 2 个给系统）
 *   cgroup      → shares=512（低于 display=1024，UI 永远优先）
 */
public final class GpuBackend {

    private static final String TAG = "LumenGpu";

    public static final String DIR = "/data/adb/lumen_gpu";
    public static final int PORT = 8191;

    private GpuBackend() {}

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    public static boolean isBusy() { return BUSY.get(); }

    // ─────────────────────────────────────────────────────────
    // 部署：把 GPU 版引擎 + OpenCL 依赖 + 脚本推到 /data/adb/lumen_gpu
    // ─────────────────────────────────────────────────────────

    /** 检查部署是否完整（eng + OpenCL 依赖 + 脚本） */
    public static boolean isDeployed() {
        String out = RootBackend.sh(
                "[ -f " + DIR + "/libdit_engine.so ] && " +
                "[ -f " + DIR + "/libOpenCL.so ] && " +
                "[ -f " + DIR + "/libCB.so ] && " +
                "[ -f " + DIR + "/run_gpu.sh ] && echo OK", 15);
        return out.contains("OK");
    }

    /**
     * 部署 GPU 后端。
     *
     * 注意：GPU 版 libdit_engine.so（59 MB，含 OpenCL）不打包进 APK ——
     * 否则 APK 从 46 MB 涨到 105 MB，而实测 GPU 并不比 CPU 快。
     * 所以这里只负责：拷 core + 拷 OpenCL 依赖 + 写脚本，
     * 引擎由用户/工具提前放到 DIR（例如从 /sdcard/MOSS-Image/libdit_engine.so.bak）。
     */
    public static String deploy(Context ctx) {
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;

        String mk = RootBackend.sh("mkdir -p " + DIR, 10);
        if (mk.contains("[error]")) return "建目录失败";

        // 1. GPU 版引擎必须已存在
        String hasEng = RootBackend.sh("[ -f " + DIR + "/libdit_engine.so ] && echo HAS", 10);
        if (!hasEng.contains("HAS")) {
            // 尝试从几个已知位置找 GPU 版
            String picked = RootBackend.sh(
                    "for c in /sdcard/MOSS-Image/libdit_engine.so.bak " +
                    "/storage/emulated/0/MOSS-Image/libdit_engine.so.bak; do " +
                    "  if [ -f $c ]; then SZ=$(stat -c %s $c); " +
                    "    if [ $SZ -gt 58000000 ] && [ $SZ -lt 60000000 ]; then cp -f $c " + DIR + "/libdit_engine.so && echo FOUND; break; fi; " +
                    "  fi; done", 120);
            if (!picked.contains("FOUND")) {
                return "缺少 GPU 版引擎（59 MB）。\n" +
                       "请把 libdit_engine.so.bak 放到 /sdcard/MOSS-Image/ 下再试。\n" +
                       "（APK 里带的是 CPU 版 54 MB，不能用于 GPU）";
            }
        }

        // 2. 核心库（和 CPU 后端共用）
        File core = new File(nativeDir, "libsd_core.so");
        if (core.exists()) {
            RootBackend.sh("cp -f " + core.getAbsolutePath() + " " + DIR + "/libstable_diffusion_core.so", 120);
        } else {
            RootBackend.sh("cp -f " + RootBackend.BIN_DIR + "/libstable_diffusion_core.so " + DIR + "/ 2>/dev/null", 60);
        }

        // 3. libc++（引擎依赖）
        File cxx = new File(nativeDir, "libc++_shared.so");
        if (cxx.exists()) {
            RootBackend.sh("cp -f " + cxx.getAbsolutePath() + " " + DIR + "/", 60);
        }

        // 4. OpenCL 依赖：必须是真实副本，软链会被 linker 拒绝
        String ocl = RootBackend.sh(
                "cp -f /vendor/lib64/libOpenCL.so " + DIR + "/ 2>/dev/null; " +
                "cp -f /vendor/lib64/libCB.so " + DIR + "/ 2>/dev/null; " +
                "ls " + DIR + "/libOpenCL.so " + DIR + "/libCB.so 2>/dev/null | wc -l", 60);
        if (!ocl.trim().endsWith("2")) {
            return "OpenCL 库拷贝失败（需要 /vendor/lib64/libOpenCL.so + libCB.so）: " + ocl.trim();
        }

        // 5. 启动脚本
        writeScript();

        String fin = RootBackend.sh("chmod 755 " + DIR + "/*.so " + DIR + "/run_gpu.sh && echo DONE", 25);
        return fin.contains("DONE") ? "ok" : ("收尾失败: " + fin);
    }

    /** 写启动脚本（内容由 App 注入，避免 assets 依赖） */
    private static void writeScript() {
        String script =
            "#!/system/bin/sh\n" +
            "BIN=" + DIR + "\n" +
            "MODEL=${1:-/data/local/lumen_models}\n" +
            "THR=${2:-6}\n" +
            "BUS=${3:-550}\n" +
            "VRAM=${4:-2.5}\n" +
            "K=/sys/class/kgsl/kgsl-3d0\n" +
            "B=/sys/class/devfreq/kgsl-busmon\n" +
            "LOG=$BIN/gpu.log\n" +
            "\n" +
            "restore_hw() {\n" +
            "  echo 905000000 > $K/devfreq/max_freq 2>/dev/null\n" +
            "  echo 550000000 > $K/devfreq/min_freq 2>/dev/null\n" +
            "  echo 905000000 > $B/max_freq 2>/dev/null\n" +
            "  echo -7 > $K/wake_nice 2>/dev/null\n" +
            "}\n" +
            "\n" +
            "case \"$5\" in\n" +
            "  stop)\n" +
            "    for p in $(pidof libstable_diffusion_core.so 2>/dev/null); do kill -9 $p; done\n" +
            "    restore_hw\n" +
            "    echo STOPPED\n" +
            "    exit 0;;\n" +
            "  status)\n" +
            "    P=$(pidof libstable_diffusion_core.so 2>/dev/null)\n" +
            "    if [ -n \"$P\" ]; then echo \"RUNNING pid=$P\"; else echo STOPPED; fi\n" +
            "    exit 0;;\n" +
            "esac\n" +
            "\n" +
            "# ─── 前置内存门禁 ───\n" +
            "AV=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)\n" +
            "if [ \"$AV\" -lt 4500 ]; then echo \"ABORT mem=${AV}MB\"; exit 1; fi\n" +
            "\n" +
            "# ─── 硬件护栏 ───\n" +
            "echo 905000000 > $K/devfreq/max_freq 2>/dev/null\n" +
            "echo 905000000 > $K/devfreq/min_freq 2>/dev/null\n" +
            "echo $((BUS*1000000)) > $B/max_freq 2>/dev/null\n" +
            "echo 0 > $K/wake_nice 2>/dev/null\n" +
            "CG=/dev/cpuctl/lumen; mkdir -p $CG 2>/dev/null\n" +
            "echo 512 > $CG/cpu.shares 2>/dev/null\n" +
            "echo $$ > $CG/cgroup.procs 2>/dev/null\n" +
            "ionice -c 3 -p $$ 2>/dev/null\n" +
            "\n" +
            "export LD_LIBRARY_PATH=$BIN\n" +
            "export NM_DIT_BACKEND='diffusion=GPUOpenCL,te=CPU,vae=CPU'\n" +
            "export SD_QWEN35_TOKENIZER=$MODEL/tokenizer.json\n" +
            "export NM_DIT_THREADS=$THR\n" +
            "export DIT_MAX_VRAM=\"GPUOpenCL=$VRAM\"\n" +
            "export GGML_OPENCL_KERNEL_CACHE_DIR=$BIN/ocl_cache\n" +
            "mkdir -p $BIN/ocl_cache\n" +
            "\n" +
            "cd $BIN\n" +
            "$BIN/libstable_diffusion_core.so --type krea2 --model_dir $MODEL \\\n" +
            "  --lib_dir $BIN --port " + PORT + " --log_level 2 >> $LOG 2>&1 &\n" +
            "BP=$!\n" +
            "echo \"START pid=$BP thr=$THR bus=$BUS vram=$VRAM\" >> $LOG\n" +
            "\n" +
            "# ─── 熔断：内存低于 500MB 连击 2 次就杀 ───\n" +
            "LOW=0\n" +
            "while kill -0 $BP 2>/dev/null; do\n" +
            "  sleep 2\n" +
            "  M=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)\n" +
            "  if [ \"${M:-9999}\" -lt 500 ]; then\n" +
            "    LOW=$((LOW+1))\n" +
            "    if [ $LOW -ge 2 ]; then\n" +
            "      echo \"TRIP mem=${M}MB\" >> $LOG\n" +
            "      kill -9 $BP; break\n" +
            "    fi\n" +
            "  else LOW=0; fi\n" +
            "done\n" +
            "restore_hw\n" +
            "echo EXIT >> $LOG\n";

        // 用 base64 传，避免 shell 转义地狱
        String b64 = android.util.Base64.encodeToString(
                script.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        RootBackend.sh(
                "echo '" + b64 + "' | base64 -d > " + DIR + "/run_gpu.sh && chmod 755 " + DIR + "/run_gpu.sh",
                25);
    }

    // ─────────────────────────────────────────────────────────
    // 控制
    // ─────────────────────────────────────────────────────────

    /** 启动 GPU 后端。modelDir 建议传 /data/local/lumen_models（f2fs 快路径） */
    public static String start(String modelDir, int threads, int busMhz, float vramGb) {
        if (BUSY.get()) return "GPU 后端已在运行";
        BUSY.set(true);
        try {
            return RootBackend.sh(
                    "sh " + DIR + "/run_gpu.sh " + modelDir + " " + threads + " " + busMhz + " " + vramGb,
                    45);
        } finally {
            BUSY.set(false);
        }
    }

    public static String stop() {
        String r = RootBackend.sh("sh " + DIR + "/run_gpu.sh /data/local/lumen_models 6 550 2.5 stop", 25);
        restoreHardware();
        return r;
    }

    /** 强制恢复硬件到出厂状态（任何异常都能调） */
    public static void restoreHardware() {
        RootBackend.sh(
                "echo 905000000 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null; " +
                "echo 550000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null; " +
                "echo 905000000 > /sys/class/devfreq/kgsl-busmon/max_freq 2>/dev/null; " +
                "echo -7 > /sys/class/kgsl/kgsl-3d0/wake_nice 2>/dev/null; " +
                "echo RESTORED", 20);
    }

    public static boolean ping() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 600);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** GPU 是否可用（部署完整 + root 可用） */
    public static boolean available() {
        return RootBackend.hasRoot() && isDeployed();
    }

    /** 取一段日志 */
    public static String log(int lines) {
        return RootBackend.sh("tail -n " + lines + " " + DIR + "/gpu.log 2>/dev/null", 15);
    }
}