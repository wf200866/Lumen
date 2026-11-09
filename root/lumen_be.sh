#!/system/bin/sh
# ============================================================
# Lumen 后端启动器（root）
# 用法: lumen_be.sh start|stop|restart|status|log [n]|setcfg ...
#
# 提速关键（实测）:
#   taskset 240   只跑 cpu4-7（大核 X2@3.19G + A710@2.75G）
#                 排除 cpu0-3（A510@2.02G），避免木桶效应
#   oom_adj -1000 不被 LMK 回收
#   renice -20    调度优先
# ============================================================

BIN=/data/adb/lumen
CORE=$BIN/libstable_diffusion_core.so
ENGINE=$BIN/libdit_engine.so
MODEL=/storage/emulated/0/Download/Nightmare/models/moss_image_4step
PORT=8189
PIDF=$BIN/backend.pid
LOGF=$BIN/backend.log
ENVF=$BIN/backend.env

# 默认配置（backend.env 可覆盖）
NM_DIT_BACKEND_VAL="diffusion=CPU,te=CPU,vae=CPU"
NM_DIT_THREADS_VAL=4
CPU_MASK_VAL=240          # 0xF0 = cpu4-7
USE_TASKSET=1

load_env() { [ -f "$ENVF" ] && . "$ENVF"; }
save_env() {
    {
        echo "NM_DIT_BACKEND_VAL=\"$NM_DIT_BACKEND_VAL\""
        echo "NM_DIT_THREADS_VAL=$NM_DIT_THREADS_VAL"
        echo "CPU_MASK_VAL=$CPU_MASK_VAL"
    } > "$ENVF"
}

start() {
    load_env

    if [ -f "$PIDF" ]; then
        OLD=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
            echo "已在运行 pid=$OLD"
            return 0
        fi
        rm -f "$PIDF"
    fi

    [ -x "$CORE" ]   || { echo "ERR 缺 $CORE";   return 1; }
    [ -f "$ENGINE" ] || { echo "ERR 缺 $ENGINE"; return 1; }
    [ -f "$MODEL/dit.gguf" ] || { echo "ERR 缺模型"; return 1; }

    cd "$BIN" || return 1

    export LD_LIBRARY_PATH="$BIN"
    export NM_DIT_BACKEND="$NM_DIT_BACKEND_VAL"
    export SD_QWEN35_TOKENIZER="$MODEL/tokenizer.json"
    export NM_DIT_THREADS="$NM_DIT_THREADS_VAL"

    # 核心：绑大核
    RUNNER=""
    if [ "$USE_TASKSET" = "1" ] && command -v taskset >/dev/null 2>&1; then
        RUNNER="taskset $CPU_MASK_VAL"
    fi

    nohup $RUNNER "$CORE" \
        --type krea2 \
        --model_dir "$MODEL" \
        --lib_dir "$BIN" \
        --port "$PORT" \
        --dit_params_backend all=disk \
        --log_level 2 \
        >> "$LOGF" 2>&1 &

    PID=$!
    echo $PID > "$PIDF"

    # 立刻拉高优先级
    i=0
    while [ $i -lt 20 ]; do
        if [ -d "/proc/$PID" ]; then
            echo -1000 > /proc/$PID/oom_score_adj 2>/dev/null
            break
        fi
        i=$((i+1)); sleep 0.15
    done
    [ -d "/dev/cpuset/top-app/tasks" ] && echo $PID > /dev/cpuset/top-app/tasks 2>/dev/null
    renice -n -20 -p $PID 2>/dev/null

    echo "启动 pid=$PID oom=$(cat /proc/$PID/oom_score_adj 2>/dev/null) threads=$NM_DIT_THREADS_VAL mask=$CPU_MASK_VAL backend=$NM_DIT_BACKEND_VAL"
}

stop() {
    [ -f "$PIDF" ] && { P=$(cat "$PIDF" 2>/dev/null); [ -n "$P" ] && kill -9 "$P" 2>/dev/null; rm -f "$PIDF"; }
    for p in $(pidof libstable_diffusion_core.so 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
    echo "已停止"
}

status() {
    if [ -f "$PIDF" ]; then
        PID=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            OOM=$(cat /proc/$PID/oom_score_adj 2>/dev/null)
            RSS=$(awk '/VmRSS/{print int($2/1024)}' /proc/$PID/status 2>/dev/null)
            if netstat -tln 2>/dev/null | grep -q ":$PORT "; then P=1; else P=0; fi
            echo "RUNNING pid=$PID rss=${RSS}MB oom=$OOM port=$P"
            return 0
        fi
    fi
    echo "STOPPED"
    return 1
}

setcfg() {
    # setcfg <cpu|opencl> <threads> <mask>
    case "$2" in
        opencl) NM_DIT_BACKEND_VAL="diffusion=GPUOpenCL,te=CPU,vae=CPU" ;;
        *)      NM_DIT_BACKEND_VAL="diffusion=CPU,te=CPU,vae=CPU" ;;
    esac
    [ -n "$3" ] && NM_DIT_THREADS_VAL=$3
    [ -n "$4" ] && CPU_MASK_VAL=$4
    save_env
    echo "已保存 threads=$NM_DIT_THREADS_VAL mask=$CPU_MASK_VAL backend=$NM_DIT_BACKEND_VAL（重启生效）"
}

case "$1" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; sleep 1; start ;;
    status)  status ;;
    log)     tail -n "${2:-30}" "$LOGF" 2>/dev/null ;;
    setcfg)  setcfg "$@" ;;
    *)       echo "用法: $0 start|stop|restart|status|log [n]|setcfg cpu|opencl [threads] [mask]" ;;
esac