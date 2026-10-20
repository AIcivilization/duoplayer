cmake_minimum_required(VERSION 3.22.1)
project(zxaudio CXX C)

add_library(zxaudio SHARED
    jni_bridge.cpp
    engine.cpp
    deck.cpp
    resampler.cpp
    decoder_drlibs.cpp
    decoder_dsf.cpp
    decoder_mediacodec.cpp
    decoder_factory.cpp)

target_include_directories(zxaudio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} third_party)
# 音频路径禁止 fast-math，保证 IEEE 精确运算（直通时位精确）
target_compile_options(zxaudio PRIVATE -O3 -fno-fast-math -Wall -Wno-unused-function)
target_link_libraries(zxaudio aaudio mediandk log android)
