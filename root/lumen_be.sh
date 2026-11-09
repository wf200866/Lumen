#!/system/bin/sh
# Lumen 后端启动器（root 运行）
# 用法: lumen_be.sh start|stop|restart|status|log

BIN=/data/adb/lumen
CORE=$BIN/libstable_diffusion_core.so
MODEL=/storage/emulated/0/Download/Nightmare/models/moss_image_4step
PORT=8189
PIDF=$BIN/backend.pid
LOGF=$BIN/backend.log

# OOM 免疫 —— 这是换 root 的核心收益
OOM_ADJ=-1000

start() {
    if [ -f "$PIDF" ]; then
        OLD=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
            echo "已在运行 pid=$OLD"
            return 0
        fi
        rm -f "$PIDF"
    fi

    cd "$BIN" || return 1

    # 关键环境
    export LD_LIBRARY_PATH=$BIN
    export NM_DIT_BACKEND=diffusion=CPU,te=CPU,vae=CPU
    export SD_QWEN35_TOKENIZER=$MODEL/tokenizer.json
    export NM_DIT_THREADS=8

    # 起进程
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

    # 立刻调 OOM 优先级，越早越好
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if [ -d "/proc/$PID" ]; then
            echo $OOM_ADJ > /proc/$PID/oom_score_adj 2>/dev/null
            break
        fi
        sleep 0.2
    done

    # 再绑大核（cpu4-7 是 A710/A510，性能核）
    if [ -d "/proc/$PID" ]; then
        echo $PID > /dev/cpuset/top-app/tasks 2>/dev/null
    fi

    # 提到前台调度优先级
    renice -n -20 -p $PID 2>/dev/null

    echo "已启动 pid=$PID oom_adj=$(cat /proc/$PID/oom_score_adj 2>/dev/null)"
}

stop() {
    if [ -f "$PIDF" ]; then
        PID=$(cat "$PIDF")
        [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
        rm -f "$PIDF"
    fi
    # 兜底
    for p in $(pidof libstable_diffusion_core.so 2>/dev/null); do
        kill -9 "$p" 2>/dev/null
    done
    echo "已停止"
}

status() {
    if [ -f "$PIDF" ]; then
        PID=$(cat "$PIDF")
        if kill -0 "$PID" 2>/dev/null; then
            OOM=$(cat /proc/$PID/oom_score_adj 2>/dev/null)
            RSS=$(awk '/VmRSS/{print int($2/1024)}' /proc/$PID/status 2>/dev/null)
            PORT_OK=$(netstat -tln 2>/dev/null | grep -c ":$PORT ")
            echo "RUNNING pid=$PID rss=${RSS}MB oom_adj=$OOM port=$PORT_OK"
            return 0
        fi
    fi
    echo "STOPPED"
    return 1
}

case "$1" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; sleep 1; start ;;
    status)  status ;;
    log)     tail -n ${2:-30} "$LOGF" ;;
    *)       echo "用法: $0 start|stop|restart|status|log" ;;
esac