#pragma once
// 一条播放轨（Deck）：独立的解码线程 + 重采样 + 无锁环形缓冲。
// 音频回调只做「从环形缓冲取样 * 增益」并累加到混音总线，绝不解码、不分配内存。
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "decoder.h"
#include "resampler.h"

namespace zx {

// 单生产者/单消费者环形缓冲（交错立体声 double）
class Ring {
public:
    void allocate(size_t frames) { cap_ = frames; buf_.assign(frames * 2, 0.0); reset(); }
    void reset() { w_.store(0); r_.store(0); }
    size_t readable() const { return (size_t)(w_.load(std::memory_order_acquire) - r_.load(std::memory_order_relaxed)); }
    size_t writable() const { return cap_ - (size_t)(w_.load(std::memory_order_relaxed) - r_.load(std::memory_order_acquire)); }
    size_t capacity() const { return cap_; }
    size_t write(const double* src, size_t frames);
    // 消费者：取第 i 帧（相对当前读指针）
    const double* peek(size_t i) const { return &buf_[((r_.load(std::memory_order_relaxed) + i) % cap_) * 2]; }
    void advance(size_t frames) { r_.fetch_add(frames, std::memory_order_release); }

private:
    std::vector<double> buf_;
    size_t cap_ = 0;
    std::atomic<uint64_t> w_{0}, r_{0};
};

struct DeckStatus {
    bool loaded = false, playing = false, ended = false, looping = false;
    int64_t positionMs = 0, durationMs = 0;
    int underruns = 0;
};

class Deck {
public:
    Deck();
    ~Deck();

    // 控制接口（任意线程）
    std::string load(int fd, int outRate, StreamInfo& info);  // 返回空串表示成功
    void unload();
    void play();
    void pause();
    void seekMs(int64_t ms);
    void setGainDb(double db);
    void setBalance(double b);  // -1 左 ... 0 居中 ... +1 右
    void setLoop(bool loop) { loop_.store(loop); }
    void reconfigure(int outRate);
    DeckStatus status() const;
    bool wantsPlay() const { return playing_.load(); }
    bool audible() const { return audible_.load(); }

    // 音频回调线程专用：把本轨累加到 acc（交错立体声 double）
    void render(double* acc, int frames);

private:
    void producerLoop();
    void flushLocked(int64_t baseMs);  // 需持有 ctl_
    void updateGains();
    int64_t positionMsInternal() const;

    // ---- 控制/解码侧（ctl_ 保护）----
    mutable std::mutex ctl_;
    std::unique_ptr<Decoder> dec_;
    Resampler rs_;
    std::vector<double> decodeBuf_;
    std::vector<double> pendingOut_;
    size_t pendingPos_ = 0;
    bool drainPending_ = false;
    int outRate_ = 48000;
    int64_t durationMs_ = 0;

    // ---- 回调侧（cb_ 保护；回调只 try_lock，拿不到就本次输出静音）----
    std::mutex cb_;
    Ring ring_;
    double env_ = 0.0;           // 播放/暂停淡入淡出包络，稳态严格为 0 或 1
    double gL_ = 1.0, gR_ = 1.0;  // 当前（平滑中的）增益

    // ---- 原子共享状态 ----
    std::atomic<bool> loaded_{false}, playing_{false}, ended_{false}, eof_{false}, loop_{false}, audible_{false};
    std::atomic<double> targetL_{1.0}, targetR_{1.0};
    std::atomic<double> gainDb_{0.0}, balance_{0.0};
    std::atomic<int64_t> baseMs_{0}, consumed_{0};
    std::atomic<int> underruns_{0};

    std::atomic<bool> quit_{false};
    std::condition_variable cv_;
    std::thread producer_;
};

}  // namespace zx
