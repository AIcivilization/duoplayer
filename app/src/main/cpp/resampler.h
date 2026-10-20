#pragma once
// 高质量有理数多相重采样器（立体声交错 double）。
// 源采样率 == 输出采样率时完全旁路（逐样本复制，位精确）。
#include <string>
#include <vector>

namespace zx {

class Resampler {
public:
    bool configure(int inRate, int outRate, std::string& error);
    bool passthrough() const { return L_ == 1 && M_ == 1; }
    // 追加输出帧到 out（交错立体声）
    void process(const double* in, int frames, std::vector<double>& out);
    // 文件结束时冲出滤波器尾巴
    void drain(std::vector<double>& out);
    void reset();
    int taps() const { return N_; }

private:
    int L_ = 1, M_ = 1, N_ = 1;
    std::vector<double> coef_;  // [phase][N]，已反序，可正向点积
    std::vector<double> x_;     // 交错立体声输入历史
    long base_ = 0;             // 当前输出对应的最新输入帧（x_ 内帧下标）
    int phase_ = 0;
    int discard_ = 0;           // 复位后丢弃的输出帧数（补偿滤波器群延迟）
    int delay_ = 0;
};

}  // namespace zx
