// 用真实 Deck（解码线程+环形缓冲+render）模拟音频回调，验证：单轨 0dB 位精确、两轨相加、暂停淡出、结束标记、循环
#include <fcntl.h>
#include <unistd.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>
#include "deck.h"
using namespace zx;
int main(int argc, char** argv) {
    const char* a = argv[1]; const char* b = argv[2]; int rate = atoi(argv[3]);
    Deck A, B; StreamInfo ia, ib;
    int fa = open(a, O_RDONLY), fb = open(b, O_RDONLY);
    printf("loadA: '%s'\n", A.load(fa, rate, ia).c_str());
    printf("loadB: '%s'\n", B.load(fb, rate, ib).c_str());
    close(fa); close(fb);
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    const int blk = 960;
    std::vector<double> bus(blk * 2), onlyA, mixed;
    A.play();
    for (int k = 0; k < 200; ++k) { std::fill(bus.begin(), bus.end(), 0.0); A.render(bus.data(), blk); B.render(bus.data(), blk); onlyA.insert(onlyA.end(), bus.begin(), bus.end()); std::this_thread::sleep_for(std::chrono::microseconds(500)); }
    B.play();
    for (int k = 0; k < 200; ++k) { std::fill(bus.begin(), bus.end(), 0.0); A.render(bus.data(), blk); B.render(bus.data(), blk); mixed.insert(mixed.end(), bus.begin(), bus.end()); std::this_thread::sleep_for(std::chrono::microseconds(500)); }
    A.pause();
    std::vector<double> fade;
    for (int k = 0; k < 20; ++k) { std::fill(bus.begin(), bus.end(), 0.0); A.render(bus.data(), blk); fade.insert(fade.end(), bus.begin(), bus.end()); }
    auto sa = A.status(), sb = B.status();
    printf("A pos=%lldms playing=%d audible=%d | B pos=%lldms underrunsA=%d underrunsB=%d\n", (long long)sa.positionMs, sa.playing, A.audible(), (long long)sb.positionMs, sa.underruns, sb.underruns);
    // 跑到结束
    B.seekMs(sb.durationMs - 200);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    for (int k = 0; k < 400 && !B.status().ended; ++k) { std::fill(bus.begin(), bus.end(), 0.0); B.render(bus.data(), blk); std::this_thread::sleep_for(std::chrono::microseconds(300)); }
    printf("B ended=%d playing=%d\n", B.status().ended, B.status().playing);
    FILE* f = fopen("deck_onlyA.f64", "wb"); fwrite(onlyA.data(), 8, onlyA.size(), f); fclose(f);
    f = fopen("deck_mixed.f64", "wb"); fwrite(mixed.data(), 8, mixed.size(), f); fclose(f);
    f = fopen("deck_fade.f64", "wb"); fwrite(fade.data(), 8, fade.size(), f); fclose(f);
    return 0;
}
