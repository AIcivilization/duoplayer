#pragma once
// 双轨混音引擎：两条 Deck 在 64bit 浮点总线上相加，经透明峰值保护后以 32bit float
// 送入 AAudio。采样率跟随系统原生输出（ZX500 打开 High-Res streaming 后为 192kHz），
// 由我们自己的高质量重采样器完成变换，系统混音器不再做任何重采样。
#include <aaudio/AAudio.h>

#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "deck.h"

namespace zx {

enum class OutputMode { PowerSaving = 0, Standard = 1 };

struct OutputStatus {
    int sampleRate = 0;
    int framesPerBurst = 0;
    int bufferFrames = 0;
    bool running = false;
    int limiterHits = 0;
    int xruns = 0;
    std::string mode;
    std::string error;
};

class Engine {
public:
    static constexpr int kDecks = 2;

    Engine();
    ~Engine();

    Deck& deck(int i) { return *decks_[i]; }
    std::string load(int deckIdx, int fd, StreamInfo& info);
    void play(int deckIdx);
    void pause(int deckIdx);

    void setOutputMode(OutputMode m);
    // 周期调用（~4Hz）：处理设备断开重连、空闲时停流省电
    void tick();
    OutputStatus outputStatus();
    int sampleRate() const { return rate_.load(); }

private:
    bool openStream();
    void closeStream();
    void ensureRunning();
    static aaudio_data_callback_result_t dataCb(AAudioStream* s, void* user, void* audio, int32_t frames);
    static void errorCb(AAudioStream* s, void* user, aaudio_result_t err);
    void mix(float* out, int frames);

    std::array<std::unique_ptr<Deck>, kDecks> decks_;
    std::mutex streamMtx_;
    AAudioStream* stream_ = nullptr;
    std::atomic<int> rate_{0};
    std::atomic<bool> disconnected_{false};
    OutputMode mode_ = OutputMode::PowerSaving;
    std::string lastError_;
    int idleTicks_ = 0;

    // 回调线程私有
    std::vector<double> bus_;
    double limGain_ = 1.0;
    double limRelease_ = 0.0;
    std::atomic<int> limiterHits_{0};
};

}  // namespace zx
