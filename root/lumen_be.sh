#!/system/bin/sh
# ============================================================
# Lumen 后端启动器（root 运行）
# 用法: lumen_be.sh start|stop|restart|status|log [n]
#
# 关键点:
#   oom_score_adj = -1000   LMK 永不回收（这是换 root 的核心收益）
#   renice -20              调度优先
#   绑定 top-app cpuset     吃满大核
# ============================================================

BIN=/data/adb/lumen
CORE=$BIN/libstable_diffusion_core.so
ENGINE=$BIN/libdit_engine.so
MODEL=/storage/emulated/0/Download/Nightmare/models/moss_image_4step
PORT=8189
PIDF=$BIN/backend.pid
LOGF=$BIN/backend.log
ENVF=$BIN/backend.env

OOM_ADJ=-1000
CPU_FREQ_MIN=1

# 默认值（可被 backend.env 覆盖）
NM_DIT_BACKEND_VAL="diffusion=CPU,te=CPU,vae=CPU"
NM_DIT_THREADS_VAL=8

# 读取 App 写入的配置
load_env() {
    if [ -f "$ENVF" ]; then
        . "$ENVF"
    fi
}

save_env() {
    {
        echo "NM_DIT_BACKEND_VAL=\"$NM_DIT_BACKEND_VAL\""
        echo "NM_DIT_THREADS_VAL=$NM_DIT_THREADS_VAL"
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

    # 检查文件
    [ -x "$CORE" ]   || { echo "ERR: 缺 $CORE";   return 1; }
    [ -f "$ENGINE" ] || { echo "ERR: 缺 $ENGINE"; return 1; }
    [ -f "$MODEL/dit.gguf" ] || { echo "ERR: 缺模型 $MODEL/dit.gguf"; return 1; }

    cd "$BIN" || return 1

    export LD_LIBRARY_PATH="$BIN"
    export NM_DIT_BACKEND="$NM_DIT_BACKEND_VAL"
    export SD_QWEN35_TOKENIZER="$MODEL/tokenizer.json"
    export NM_DIT_THREADS="$NM_DIT_THREADS_VAL"

    nohup "$CORE" \
        --type krea2 \
        --model_dir "$MODEL" \
        --lib_dir "$BIN" \
        --port "$PORT" \
        --dit_params_backend all=disk \
        --log_level 2 \
        >> "$LOGF" 2>&1 &

    PID=$!
    echo $PID > "$PIDF"

    # 立刻调优先级（越快越好，模型加载前就设上）
    i=0
    while [ $i -lt 15 ]; do
        if [ -d "/proc/$PID" ]; then
            echo $OOM_ADJ > /proc/$PID/oom_score_adj 2>/dev/null
            break
        fi
        i=$((i+1))
        sleep 0.2
    done

    # 提到 top-app cpuset（吃大核）
    if [ -d "/dev/cpuset/top-app/tasks" ]; then
        echo $PID > /dev/cpuset/top-app/tasks 2>/dev/null
    fi

    # 调度优先级
    renice -n -20 -p $PID 2>/dev/null

    # 抬高 CPU 最低频率（避免降频拖慢）
    if [ "$CPU_FREQ_MIN" = "1" ]; then
        for c in /sys/devices/system/cpu/cpu[4-7]/cpufreq/scaling_min_freq; do
            [ -f "$c" ] || continue
            MAXF=$(cat "${c%scaling_min_freq}cpuinfo_max_freq" 2>/dev/null)
            [ -n "$MAXF" ] && echo $MAXF > "$c" 2>/dev/null
        done
    fi

    echo "已启动 pid=$PID oom_adj=$(cat /proc/$PID/oom_score_adj 2>/dev/null) backend=$NM_DIT_BACKEND_VAL threads=$NM_DIT_THREADS_VAL"
}

stop() {
    if [ -f "$PIDF" ]; then
        PID=$(cat "$PIDF" 2>/dev/null)
        [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
        rm -f "$PIDF"
    fi
    for p in $(pidof libstable_diffusion_core.so 2>/dev/null); do
        kill -9 "$p" 2>/dev/null
    done
    echo "已停止"
}

status() {
    if [ -f "$PIDF" ]; then
        PID=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            OOM=$(cat /proc/$PID/oom_score_adj 2>/dev/null)
            RSS=$(awk '/VmRSS/{print int($2/1024)}' /proc/$PID/status 2>/dev/null)
            if netstat -tln 2>/dev/null | grep -q ":$PORT "; then P=1; else P=0; fi
            echo "RUNNING pid=$PID rss=${RSS}MB oom_adj=$OOM port=$P"
            return 0
        fi
    fi
    echo "STOPPED"
    return 1
}

setcfg() {
    # setcfg <cpu|opencl> <threads>
    case "$2" in
        opencl) NM_DIT_BACKEND_VAL="diffusion=GPUOpenCL,te=CPU,vae=CPU" ;;
        *)      NM_DIT_BACKEND_VAL="diffusion=CPU,te=CPU,vae=CPU" ;;
    esac
    [ -n "$3" ] && NM_DIT_THREADS_VAL=$3
    save_env
    echo "配置已保存: backend=$NM_DIT_BACKEND_VAL threads=$NM_DIT_THREADS_VAL（重启生效）"
}

case "$1" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; sleep 1; start ;;
    status)  status ;;
    log)     tail -n "${2:-30}" "$LOGF" 2>/dev/null ;;
    setcfg)  setcfg "$@" ;;
    *)       echo "用法: $0 start|stop|restart|status|log [n]|setcfg cpu|opencl [threads]" ;;
esac