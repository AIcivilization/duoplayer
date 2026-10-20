#include "engine.h"

#include <android/log.h>

#include <cmath>
#include <cstring>

#define LOG_TAG "ZXDual"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace zx {

namespace {
constexpr int kMaxCallbackFrames = 16384;
constexpr double kCeiling = 1.0;           // float 满幅；超过才动作，未超过时信号逐位不变
constexpr double kLimiterReleaseSec = 0.15;
constexpr int kIdleTicksBeforeStop = 8;    // 两轨都停 ~2 秒后关闭输出流省电
}  // namespace

Engine::Engine() {
    for (auto& d : decks_) d = std::make_unique<Deck>();
    bus_.assign(kMaxCallbackFrames * 2, 0.0);
    openStream();
}

Engine::~Engine() {
    closeStream();
    for (auto& d : decks_) d.reset();
}

bool Engine::openStream() {
    std::lock_guard<std::mutex> lk(streamMtx_);
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { lastError_ = "AAudio 不可用"; return false; }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    // 不指定采样率 => 拿到系统混音器的原生采样率，避免系统二次重采样
    AAudioStreamBuilder_setSampleRate(b, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(
        b, mode_ == OutputMode::PowerSaving ? AAUDIO_PERFORMANCE_MODE_POWER_SAVING : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(b, &Engine::dataCb, this);
    AAudioStreamBuilder_setErrorCallback(b, &Engine::errorCb, this);
    AAudioStream* s = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !s) {
        lastError_ = std::string("打开音频输出失败: ") + AAudio_convertResultToText(r);
        LOGE("%s", lastError_.c_str());
        return false;
    }
    stream_ = s;
    int rate = AAudioStream_getSampleRate(s);
    // 尽量用大缓冲，减少唤醒（省电，且对音质无影响）
    AAudioStream_setBufferSizeInFrames(s, AAudioStream_getBufferCapacityInFrames(s));
    limRelease_ = std::exp(-1.0 / (kLimiterReleaseSec * rate));
    limGain_ = 1.0;
    lastError_.clear();
    disconnected_.store(false);
    int old = rate_.exchange(rate);
    LOGI("output stream: %d Hz, burst %d, buffer %d, format %d", rate, AAudioStream_getFramesPerBurst(s),
         AAudioStream_getBufferSizeInFrames(s), AAudioStream_getFormat(s));
    if (old != rate) {
        for (auto& d : decks_) d->reconfigure(rate);
    }
    return true;
}

void Engine::closeStream() {
    std::lock_guard<std::mutex> lk(streamMtx_);
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

std::string Engine::load(int deckIdx, int fd, StreamInfo& info) {
    if (!stream_ && !openStream()) return lastError_;
    return decks_[deckIdx]->load(fd, rate_.load(), info);
}

void Engine::play(int deckIdx) {
    decks_[deckIdx]->play();
    ensureRunning();
}

void Engine::pause(int deckIdx) { decks_[deckIdx]->pause(); }

void Engine::ensureRunning() {
    if (disconnected_.load() || !stream_) {
        closeStream();
        openStream();
    }
    std::lock_guard<std::mutex> lk(streamMtx_);
    if (!stream_) return;
    aaudio_stream_state_t st = AAudioStream_getState(stream_);
    if (st != AAUDIO_STREAM_STATE_STARTED && st != AAUDIO_STREAM_STATE_STARTING) {
        aaudio_result_t r = AAudioStream_requestStart(stream_);
        if (r != AAUDIO_OK) lastError_ = std::string("启动输出失败: ") + AAudio_convertResultToText(r);
    }
    idleTicks_ = 0;
}

void Engine::setOutputMode(OutputMode m) {
    if (m == mode_ && stream_) return;
    mode_ = m;
    bool anyPlaying = false;
    for (auto& d : decks_) anyPlaying |= d->wantsPlay();
    closeStream();
    openStream();
    if (anyPlaying) ensureRunning();
}

void Engine::tick() {
    bool anyWant = false, anyAudible = false;
    for (auto& d : decks_) {
        anyWant |= d->wantsPlay();
        anyAudible |= d->audible();
    }
    if (disconnected_.load()) {
        LOGI("output disconnected, reopening");
        closeStream();
        openStream();
        if (anyWant) ensureRunning();
        return;
    }
    if (anyWant) {
        ensureRunning();
        return;
    }
    if (!anyAudible && ++idleTicks_ >= kIdleTicksBeforeStop) {
        std::lock_guard<std::mutex> lk(streamMtx_);
        if (stream_ && AAudioStream_getState(stream_) == AAUDIO_STREAM_STATE_STARTED) {
            AAudioStream_requestPause(stream_);
        }
    }
}

OutputStatus Engine::outputStatus() {
    OutputStatus s;
    std::lock_guard<std::mutex> lk(streamMtx_);
    s.sampleRate = rate_.load();
    s.limiterHits = limiterHits_.load();
    s.mode = mode_ == OutputMode::PowerSaving ? "省电" : "标准";
    s.error = lastError_;
    if (stream_) {
        s.framesPerBurst = AAudioStream_getFramesPerBurst(stream_);
        s.bufferFrames = AAudioStream_getBufferSizeInFrames(stream_);
        s.running = AAudioStream_getState(stream_) == AAUDIO_STREAM_STATE_STARTED;
        s.xruns = AAudioStream_getXRunCount(stream_);
    }
    return s;
}

aaudio_data_callback_result_t Engine::dataCb(AAudioStream*, void* user, void* audio, int32_t frames) {
    auto* e = static_cast<Engine*>(user);
    float* out = static_cast<float*>(audio);
    while (frames > 0) {
        int n = frames > kMaxCallbackFrames ? kMaxCallbackFrames : frames;
        e->mix(out, n);
        out += (size_t)n * 2;
        frames -= n;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void Engine::errorCb(AAudioStream*, void* user, aaudio_result_t err) {
    // 不能在错误回调里关闭/重开流：交给 tick() 处理（耳机插拔、3.5/4.4 切换等）
    auto* e = static_cast<Engine*>(user);
    if (err == AAUDIO_ERROR_DISCONNECTED) e->disconnected_.store(true);
}

void Engine::mix(float* out, int frames) {
    double* bus = bus_.data();
    memset(bus, 0, sizeof(double) * 2 * frames);
    for (auto& d : decks_) d->render(bus, frames);

    // 透明峰值保护：只有在两轨相加超过满幅时才压低增益（瞬时起控、150ms 释放）。
    // 未超限且增益已回到 1.0 时，输出 = 总线值本身，24bit 源在 float 中逐位无损。
    bool hit = false;
    for (int i = 0; i < frames; ++i) {
        double l = bus[2 * i], r = bus[2 * i + 1];
        double peak = std::fmax(std::fabs(l), std::fabs(r));
        if (peak * limGain_ > kCeiling) {
            limGain_ = kCeiling / peak;
            hit = true;
        } else if (limGain_ < 1.0) {
            limGain_ = 1.0 - (1.0 - limGain_) * limRelease_;
            if (1.0 - limGain_ < 1e-9) limGain_ = 1.0;
        }
        if (limGain_ != 1.0) { l *= limGain_; r *= limGain_; }
        out[2 * i] = (float)l;
        out[2 * i + 1] = (float)r;
    }
    if (hit) limiterHits_.fetch_add(1, std::memory_order_relaxed);
}

}  // namespace zx
