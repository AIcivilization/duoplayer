#!/bin/bash
# 在电脑上验证音频引擎：解码位精确、重采样指标、DSD 解码、双轨混音。需要 clang++、python3+numpy、ffmpeg。
set -e
cd "$(dirname "$0")"
C=../../app/src/main/cpp
FL="-std=c++17 -O2 -fno-fast-math -I$C -I$C/third_party -Dlseek64=lseek -Dpread64=pread -Doff64_t=off_t"
SRC="$C/resampler.cpp $C/decoder_drlibs.cpp $C/decoder_dsf.cpp $C/decoder_factory.cpp ../stub_mc.cpp"
mkdir -p out && cd out
clang++ $FL ../test.cpp $SRC -o t_dsp
clang++ $FL ../test_deck.cpp $C/deck.cpp $SRC -o t_deck
python3 ../gen.py
ffmpeg -loglevel error -y -i src96000.wav -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 src96000.flac
ffmpeg -loglevel error -y -i src96000.wav -c:a pcm_s24be src96000.aiff
./t_dsp src96000.wav src96000.flac src96000.aiff sine64.dsf src192000.wav
./t_deck src192000.wav src192000.wav 192000
python3 ../ana.py
