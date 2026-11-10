cmake_minimum_required(VERSION 3.18)
project(lumen_up C)

# OpenCL 头文件：NDK sysroot 里有 <CL/cl.h>，但只声明 API，不链接 libOpenCL.so
# （运行时用 dlopen 拿真库，避免 App 启动时因找不到 vendor 库而崩）
add_library(lumen_up SHARED upscale_cl.c)

target_compile_options(lumen_up PRIVATE
        -O3 -ffast-math -fvisibility=hidden)

target_link_libraries(lumen_up PRIVATE
        android
        log
        dl)

find_library(log-lib log)
target_link_libraries(lumen_up PRIVATE ${log-lib})
