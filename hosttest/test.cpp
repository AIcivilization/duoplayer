#include <fcntl.h>
#include <unistd.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>
#include "decoder.h"
#include "resampler.h"
#include "deck.h"
using namespace zx;

static void dump(const char* path, const std::vector<double>& v) { FILE* f = fopen(path, "wb"); fwrite(v.data(), 8, v.size(), f); fclose(f); }

int main(int argc, char** argv) {
    std::string err;
    // 1) 直通位精确
    {
        Resampler r; r.configure(192000, 192000, err);
        std::vector<double> in(2000), out;
        for (size_t i = 0; i < in.size(); ++i) in[i] = ((int)(i * 7919 % 16777216) - 8388608) / 8388608.0;
        r.process(in.data(), 1000, out);
        printf("passthrough exact: %s\n", out == in ? "YES" : "NO");
    }
    // 2) 44.1k -> 192k 正弦 / 近奈奎斯特正弦，分块喂入
    for (auto [inR, outR, f] : std::vector<std::tuple<int,int,double>>{{44100,192000,1000.0},{44100,192000,19000.0},{96000,192000,1000.0},{176400,192000,1000.0},{352800,192000,1000.0},{48000,44100,1000.0}}) {
        Resampler r;
        if (!r.configure(inR, outR, err)) { printf("cfg fail %s\n", err.c_str()); continue; }
        int n = inR * 2;
        std::vector<double> in(n * 2), out;
        for (int i = 0; i < n; ++i) in[2*i] = in[2*i+1] = 0.5 * sin(2 * M_PI * f * i / inR);
        for (int p = 0; p < n; ) { int c = std::min(4096 - (p % 1000), n - p); r.process(&in[2*p], c, out); p += c; }
        char path[256]; snprintf(path, sizeof path, "rs_%d_%d_%d.f64", inR, outR, (int)f);
        dump(path, out);
        printf("resample %d->%d f=%g taps=%d out_frames=%zu (expect ~%d)\n", inR, outR, f, r.taps(), out.size()/2, (int)((long)n*outR/inR));
    }
    // 3) 文件解码
    for (int k = 1; k < argc; ++k) {
        int fd = open(argv[k], O_RDONLY);
        auto d = openDecoder(fd, err);
        close(fd);
        if (!d) { printf("%s: FAIL %s\n", argv[k], err.c_str()); continue; }
        auto& i = d->info();
        std::vector<double> all, buf(8192);
        int got; while ((got = d->read(buf.data(), 4096)) > 0) all.insert(all.end(), buf.begin(), buf.begin() + got*2);
        printf("%s: %s rate=%d src=%d bits=%d ch=%d frames=%lld decoded=%zu\n", argv[k], i.codec.c_str(), i.sampleRate, i.sourceRate, i.bitsPerSample, i.channels, (long long)i.totalFrames, all.size()/2);
        std::string o = std::string(argv[k]) + ".dec.f64"; dump(o.c_str(), all);
        // seek 测试：跳到 1 秒处读一块，比较
        d->seek(i.sampleRate);
        got = d->read(buf.data(), 1024);
        double maxd = 0; for (int j = 0; j < got*2; ++j) maxd = std::max(maxd, std::fabs(buf[j] - all[(size_t)i.sampleRate*2 + j]));
        printf("   seek(1s) max diff vs linear decode: %g\n", maxd);
    }
    return 0;
}
