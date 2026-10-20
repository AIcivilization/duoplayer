#include "deck.h"

#include <algorithm>
#include <chrono>
#include <cmath>

#include "dsp_util.h"

namespace zx {

namespace {
constexpr double kRingSeconds = 2.0;    // 预解码缓冲
constexpr double kRefillSeconds = 0.5;  // 空出这么多才唤醒解码，减少 CPU 唤醒次数（省电）
constexpr int kDecodeChunk = 4096;
constexpr double kFadeSeconds = 0.008;  // 播放/暂停/跳转时 8ms 淡入淡出，防爆音
constexpr double kGainRampSeconds = 0.03;
}  // namespace

size_t Ring::write(const double* src, size_t frames) {
    size_t n = std::min(frames, writable());
    uint64_t w = w_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < n; ++i) {
        size_t idx = (size_t)((w + i) % cap_) * 2;
        buf_[idx] = src[2 * i];
        buf_[idx + 1] = src[2 * i + 1];
    }
    w_.store(w + n, std::memory_order_release);
    return n;
}

Deck::Deck() {
    decodeBuf_.resize(kDecodeChunk * 2);
    producer_ = std::thread([this] { producerLoop(); });
}

Deck::~Deck() {
    quit_.store(true);
    cv_.notify_all();
    if (producer_.joinable()) producer_.join();
}

std::string Deck::load(int fd, int outRate, StreamInfo& info) {
    std::string err;
    auto dec = openDecoder(fd, err);
    if (!dec) return err.empty() ? "无法打开文件" : err;
    Resampler rs;
    if (!rs.configure(dec->info().sampleRate, outRate, err)) return err;
    info = dec->info();

    std::lock_guard<std::mutex> lk(ctl_);
    playing_.store(false);
    dec_ = std::move(dec);
    rs_ = std::move(rs);
    outRate_ = outRate;
    durationMs_ = info.sampleRate > 0 ? info.totalFrames * 1000 / info.sampleRate : 0;
    {
        std::lock_guard<std::mutex> c(cb_);
        if (ring_.capacity() != (size_t)(outRate * kRingSeconds)) ring_.allocate((size_t)(outRate * kRingSeconds));
    }
    flushLocked(0);
    loaded_.store(true);
    cv_.notify_all();
    return "";
}

void Deck::unload() {
    std::lock_guard<std::mutex> lk(ctl_);
    playing_.store(false);
    loaded_.store(false);
    flushLocked(0);
    dec_.reset();
    durationMs_ = 0;
}

void Deck::flushLocked(int64_t baseMs) {
    rs_.reset();
    pendingOut_.clear();
    pendingPos_ = 0;
    drainPending_ = false;
    eof_.store(false);
    std::lock_guard<std::mutex> c(cb_);
    ring_.reset();
    env_ = 0.0;
    baseMs_.store(baseMs);
    consumed_.store(0);
    ended_.store(false);
    audible_.store(false);
}

void Deck::play() {
    if (!loaded_.load()) return;
    if (ended_.load()) seekMs(0);
    playing_.store(true);
}

void Deck::pause() { playing_.store(false); }

void Deck::seekMs(int64_t ms) {
    std::lock_guard<std::mutex> lk(ctl_);
    if (!dec_) return;
    if (ms < 0) ms = 0;
    if (durationMs_ > 0 && ms > durationMs_) ms = durationMs_;
    int64_t frame = ms * dec_->info().sampleRate / 1000;
    dec_->seek(frame);
    flushLocked(ms);
    cv_.notify_all();
}

void Deck::reconfigure(int outRate) {
    std::lock_guard<std::mutex> lk(ctl_);
    if (!dec_) { outRate_ = outRate; return; }
    if (outRate == outRate_) return;
    int64_t pos = positionMsInternal();
    std::string err;
    rs_.configure(dec_->info().sampleRate, outRate, err);
    outRate_ = outRate;
    {
        std::lock_guard<std::mutex> c(cb_);
        ring_.allocate((size_t)(outRate * kRingSeconds));
    }
    dec_->seek(pos * dec_->info().sampleRate / 1000);
    flushLocked(pos);
    cv_.notify_all();
}

void Deck::setGainDb(double db) { gainDb_.store(db); updateGains(); }
void Deck::setBalance(double b) { balance_.store(std::clamp(b, -1.0, 1.0)); updateGains(); }

void Deck::updateGains() {
    double db = gainDb_.load();
    // 0dB 时增益严格等于 1.0，保证直通位精确
    double g = db == 0.0 ? 1.0 : (db <= -90.0 ? 0.0 : dbToGain(db));
    double b = balance_.load();
    targetL_.store(b > 0 ? g * (1.0 - b) : g);
    targetR_.store(b < 0 ? g * (1.0 + b) : g);
}

int64_t Deck::positionMsInternal() const {
    int64_t ms = baseMs_.load() + consumed_.load() * 1000 / (outRate_ > 0 ? outRate_ : 48000);
    if (loop_.load() && durationMs_ > 0) ms %= durationMs_;
    else if (durationMs_ > 0) ms = std::min(ms, durationMs_);
    return ms;
}

DeckStatus Deck::status() const {
    DeckStatus s;
    s.loaded = loaded_.load();
    s.playing = playing_.load();
    s.ended = ended_.load();
    s.looping = loop_.load();
    s.underruns = underruns_.load();
    std::lock_guard<std::mutex> lk(ctl_);
    s.durationMs = durationMs_;
    s.positionMs = positionMsInternal();
    return s;
}

void Deck::producerLoop() {
    using namespace std::chrono_literals;
    while (!quit_.load()) {
        std::unique_lock<std::mutex> lk(ctl_);
        if (!dec_ || eof_.load()) {
            cv_.wait_for(lk, 100ms);
            continue;
        }
        // 1) 先把已重采样但未写入的数据写进环形缓冲
        if (pendingPos_ < pendingOut_.size()) {
            size_t frames = (pendingOut_.size() - pendingPos_) / 2;
            size_t n = ring_.write(pendingOut_.data() + pendingPos_, frames);
            pendingPos_ += n * 2;
            if (n < frames) {
                // 缓冲满：等待空出 kRefillSeconds
                size_t need = (size_t)(outRate_ * kRefillSeconds);
                cv_.wait_for(lk, std::chrono::milliseconds((int)(1000 * kRefillSeconds * 0.8)),
                             [&] { return quit_.load() || ring_.writable() >= need; });
                continue;
            }
        }
        pendingOut_.clear();
        pendingPos_ = 0;
        if (drainPending_) {
            drainPending_ = false;
            eof_.store(true);
            continue;
        }
        // 2) 解码下一块并重采样
        int n = dec_->read(decodeBuf_.data(), kDecodeChunk);
        if (n > 0) {
            rs_.process(decodeBuf_.data(), n, pendingOut_);
        } else if (loop_.load() && dec_->info().totalFrames > 0) {
            dec_->seek(0);  // 无缝循环：重采样器不复位，滤波器状态连续
        } else {
            rs_.drain(pendingOut_);
            drainPending_ = true;
        }
    }
}

void Deck::render(double* acc, int frames) {
    if (!cb_.try_lock()) return;  // 正在跳转/换曲：本次输出静音
    if (!loaded_.load(std::memory_order_relaxed)) { cb_.unlock(); return; }

    const bool want = playing_.load(std::memory_order_relaxed);
    if (!want && env_ == 0.0) { audible_.store(false); cb_.unlock(); return; }

    const double envStep = 1.0 / (kFadeSeconds * outRate_);
    const double gStep = 1.0 / (kGainRampSeconds * outRate_);
    const double tL = targetL_.load(std::memory_order_relaxed);
    const double tR = targetR_.load(std::memory_order_relaxed);
    size_t avail = ring_.readable();
    size_t used = 0;
    bool starved = false;

    for (int i = 0; i < frames; ++i) {
        // 包络与增益平滑：到达目标后严格等于目标值
        if (want) { env_ += envStep; if (env_ >= 1.0) env_ = 1.0; }
        else { env_ -= envStep; if (env_ <= 0.0) { env_ = 0.0; break; } }
        if (gL_ != tL) { double d = tL - gL_; gL_ = std::fabs(d) <= gStep ? tL : gL_ + (d > 0 ? gStep : -gStep); }
        if (gR_ != tR) { double d = tR - gR_; gR_ = std::fabs(d) <= gStep ? tR : gR_ + (d > 0 ? gStep : -gStep); }

        if (used >= avail) { starved = true; break; }
        const double* s = ring_.peek(used++);
        if (env_ == 1.0) {
            acc[2 * i] += s[0] * gL_;
            acc[2 * i + 1] += s[1] * gR_;
        } else {
            acc[2 * i] += s[0] * gL_ * env_;
            acc[2 * i + 1] += s[1] * gR_ * env_;
        }
    }
    ring_.advance(used);
    consumed_.fetch_add((int64_t)used, std::memory_order_relaxed);
    audible_.store(env_ > 0.0);
    if (starved) {
        if (eof_.load(std::memory_order_acquire) && ring_.readable() == 0) {
            ended_.store(true);
            playing_.store(false);
            env_ = 0.0;
            audible_.store(false);
        } else if (want) {
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
    }
    cb_.unlock();
    // 缓冲低于一半时提前唤醒解码线程（notify 不分配内存）
    if (avail - used < ring_.capacity() / 2) cv_.notify_one();
}

}  // namespace zx
