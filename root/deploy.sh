#!/system/bin/sh
# Lumen root 后端控制（供 App 通过 libsu 风格调用，也支持手动执行）
BIN=/data/adb/lumen
MODEL=/storage/emulated/0/Download/Nightmare/models/moss_image_4step
PORT=8189

deploy() {
    mkdir -p $BIN
    [ -f "$BIN/libstable_diffusion_core.so" ] || return 2
    [ -f "$BIN/libdit_engine.so" ] || return 3
    chmod 755 $BIN/*.so $BIN/lumen_be.sh 2>/dev/null
    return 0
}

"$@" 2>/dev/null
