#include "resampler.h"

#include <cmath>
#include <numeric>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#include "dsp_util.h"

namespace zx {

namespace {
constexpr double kStopbandDb = 130.0;  // 远低于 24bit 量化底噪 (-144dBFS 附近)
constexpr int kMaxPhases = 8192;
constexpr int kMaxTaps = 640;
}  // namespace

bool Resampler::configure(int inRate, int outRate, std::string& error) {
    if (inRate <= 0 || outRate <= 0) { error = "bad_rate"; return false; }
    int g = std::gcd(inRate, outRate);
    L_ = outRate / g;
    M_ = inRate / g;
    if (passthrough()) {
        N_ = 1;
        coef_.assign(1, 1.0);
        reset();
        return true;
    }
    if (L_ > kMaxPhases) { error = "rate_ratio:" + std::to_string(inRate) + "->" + std::to_string(outRate); return false; }

    // 通带到 min(in,out)/2 的 91%（44.1k 源即 20.07kHz），阻带从奈奎斯特频率开始，无混叠
    const double nyq = 0.5 * std::min(inRate, outRate);
    const double pass = 0.91 * nyq, stop = nyq;
    const double dw = 2.0 * M_PI * (stop - pass) / inRate;  // 以输入采样率归一化
    int N = (int)std::ceil((kStopbandDb - 8.0) / (2.285 * dw)) + 1;
    if (N > kMaxTaps) N = kMaxTaps;
    if (N < 8) N = 8;
    N_ = N;

    const long Lp = (long)L_ * N_;
    const double R = (double)inRate * L_;
    const double fcn = 0.5 * (pass + stop) / R;
    const double beta = kaiserBeta(kStopbandDb);
    const double c = (Lp - 1) / 2.0;
    coef_.assign((size_t)Lp, 0.0);
    for (int p = 0; p < L_; ++p) {
        double sum = 0;
        double* h = &coef_[(size_t)p * N_];
        for (int k = 0; k < N_; ++k) {
            long m = p + (long)k * L_;
            double x = m - c;
            double v = 2.0 * fcn * sinc(2.0 * fcn * x) * kaiser(x / (c + 0.5), beta);
            h[N_ - 1 - k] = v;  // 反序存放：与 x[base-N+1 .. base] 正向点积
            sum += v;
        }
        // 每一相直流增益严格为 1（无相间纹波）
        for (int k = 0; k < N_; ++k) h[k] /= sum;
    }
    delay_ = (int)std::lround(c / M_);
    reset();
    return true;
}

void Resampler::reset() {
    x_.assign((size_t)(N_ - 1) * 2, 0.0);
    base_ = N_ - 1;
    phase_ = 0;
    discard_ = passthrough() ? 0 : delay_;
}

void Resampler::process(const double* in, int frames, std::vector<double>& out) {
    if (passthrough()) {
        out.insert(out.end(), in, in + (size_t)frames * 2);
        return;
    }
    x_.insert(x_.end(), in, in + (size_t)frames * 2);
    const long avail = (long)(x_.size() / 2);
    const double* x = x_.data();
    while (base_ < avail) {
        const double* h = &coef_[(size_t)phase_ * N_];
        const double* xs = x + (base_ - N_ + 1) * 2;
#if defined(__aarch64__)
        float64x2_t acc0 = vdupq_n_f64(0.0), acc1 = vdupq_n_f64(0.0);
        int k = 0;
        for (; k + 1 < N_; k += 2) {
            acc0 = vfmaq_n_f64(acc0, vld1q_f64(xs + 2 * k), h[k]);
            acc1 = vfmaq_n_f64(acc1, vld1q_f64(xs + 2 * k + 2), h[k + 1]);
        }
        if (k < N_) acc0 = vfmaq_n_f64(acc0, vld1q_f64(xs + 2 * k), h[k]);
        float64x2_t acc = vaddq_f64(acc0, acc1);
        double l = vgetq_lane_f64(acc, 0), r = vgetq_lane_f64(acc, 1);
#else
        double l = 0, r = 0;
        for (int k = 0; k < N_; ++k) {
            l += h[k] * xs[2 * k];
            r += h[k] * xs[2 * k + 1];
        }
#endif
        if (discard_ > 0) {
            --discard_;
        } else {
            out.push_back(l);
            out.push_back(r);
        }
        phase_ += M_;
        base_ += phase_ / L_;
        phase_ %= L_;
    }
    // 只保留 N-1 帧历史
    long keepFrom = base_ - (N_ - 1);
    if (keepFrom > 0) {
        long drop = std::min<long>(keepFrom, avail);
        x_.erase(x_.begin(), x_.begin() + drop * 2);
        base_ -= drop;
    }
}

void Resampler::drain(std::vector<double>& out) {
    if (passthrough()) return;
    std::vector<double> zeros((size_t)N_ * 2, 0.0);
    process(zeros.data(), N_, out);
}

}  // namespace zx
