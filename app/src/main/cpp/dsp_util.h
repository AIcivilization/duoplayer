#pragma once
#include <cmath>

namespace zx {

inline double sinc(double x) {
    if (std::fabs(x) < 1e-12) return 1.0;
    double px = M_PI * x;
    return std::sin(px) / px;
}

inline double besselI0(double x) {
    double sum = 1.0, term = 1.0, q = x * x / 4.0;
    for (int k = 1; k < 64; ++k) {
        term *= q / ((double)k * k);
        sum += term;
        if (term < sum * 1e-17) break;
    }
    return sum;
}

// x 归一化到 [-1, 1]
inline double kaiser(double x, double beta) {
    if (x <= -1.0 || x >= 1.0) return 0.0;
    return besselI0(beta * std::sqrt(1.0 - x * x)) / besselI0(beta);
}

// 阻带衰减 A(dB) 对应的 Kaiser beta
inline double kaiserBeta(double A) {
    if (A > 50) return 0.1102 * (A - 8.7);
    if (A >= 21) return 0.5842 * std::pow(A - 21, 0.4) + 0.07886 * (A - 21);
    return 0.0;
}

inline double dbToGain(double db) { return std::pow(10.0, db / 20.0); }

}  // namespace zx
