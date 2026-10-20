// 其余格式（MP3 / AAC / M4A(ALAC) / OGG / OPUS ...）走系统 NDK MediaCodec 解码。
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <vector>

#include "decoder.h"

namespace zx {
namespace {

constexpr int kEncPcm16 = 2;
constexpr int kEncFloat = 4;

std::string codecName(const std::string& mime, bool& lossless) {
    lossless = false;
    if (mime == "audio/mpeg") return "MP3";
    if (mime == "audio/mp4a-latm") return "AAC";
    if (mime == "audio/vorbis") return "Vorbis";
    if (mime == "audio/opus") return "Opus";
    if (mime == "audio/flac") { lossless = true; return "FLAC"; }
    if (mime == "audio/alac") { lossless = true; return "ALAC"; }
    if (mime == "audio/raw") { lossless = true; return "PCM"; }
    return mime;
}

class CodecDecoder : public Decoder {
public:
    explicit CodecDecoder(int fd) : fd_(fd) {}
    ~CodecDecoder() override {
        if (codec_) { AMediaCodec_stop(codec_); AMediaCodec_delete(codec_); }
        if (ex_) AMediaExtractor_delete(ex_);
        if (fd_ >= 0) ::close(fd_);
    }

    bool init(std::string& error) {
        struct stat st {};
        if (fstat(fd_, &st) != 0) { error = "io"; return false; }
        ex_ = AMediaExtractor_new();
        if (AMediaExtractor_setDataSourceFd(ex_, fd_, 0, st.st_size) != AMEDIA_OK) { error = "unknown_format"; return false; }
        size_t n = AMediaExtractor_getTrackCount(ex_);
        AMediaFormat* fmt = nullptr;
        const char* mime = nullptr;
        for (size_t i = 0; i < n; ++i) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(ex_, i);
            const char* m = nullptr;
            if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m && strncmp(m, "audio/", 6) == 0) {
                AMediaExtractor_selectTrack(ex_, i);
                fmt = f;
                mime = m;
                break;
            }
            AMediaFormat_delete(f);
        }
        if (!fmt) { error = "no_audio_track"; return false; }
        std::string mimeStr = mime;
        int32_t rate = 0, ch = 0;
        int64_t durUs = 0;
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
        AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &durUs);
        // 尽量请求 float 输出（系统支持时可保留 >16bit 精度）
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_PCM_ENCODING, kEncFloat);
        codec_ = AMediaCodec_createDecoderByType(mimeStr.c_str());
        if (!codec_ || AMediaCodec_configure(codec_, fmt, nullptr, nullptr, 0) != AMEDIA_OK ||
            AMediaCodec_start(codec_) != AMEDIA_OK) {
            AMediaFormat_delete(fmt);
            error = "no_decoder:" + mimeStr;
            return false;
        }
        AMediaFormat_delete(fmt);
        info_.codec = codecName(mimeStr, info_.lossless);
        info_.sampleRate = info_.sourceRate = rate;
        info_.channels = ch;
        info_.bitsPerSample = 16;
        rate_ = rate;
        ch_ = ch;
        durUs_ = durUs;
        // 预解码到第一个输出缓冲，拿到真实的采样率/声道（HE-AAC 等可能与容器标注不同）
        for (int i = 0; i < 4000 && pending_.empty() && !outEos_; ++i) pump();
        info_.sampleRate = info_.sourceRate = rate_;
        info_.channels = ch_;
        info_.totalFrames = durUs_ * (int64_t)rate_ / 1000000;
        if (rate_ <= 0 || ch_ <= 0) { error = "bad_format"; return false; }
        return true;
    }

    int read(double* out, int frames) override {
        int produced = 0;
        while (produced < frames) {
            size_t avail = (pending_.size() - pendingPos_) / 2;
            if (avail == 0) {
                pending_.clear();
                pendingPos_ = 0;
                if (outEos_) break;
                pump();
                continue;
            }
            size_t take = std::min<size_t>(avail, (size_t)(frames - produced));
            memcpy(out + 2 * produced, pending_.data() + pendingPos_, take * 2 * sizeof(double));
            pendingPos_ += take * 2;
            produced += (int)take;
        }
        return produced;
    }

    bool seek(int64_t frame) override {
        int64_t us = frame * 1000000 / (rate_ > 0 ? rate_ : 44100);
        AMediaExtractor_seekTo(ex_, us, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        AMediaCodec_flush(codec_);
        pending_.clear();
        pendingPos_ = 0;
        inEos_ = outEos_ = false;
        skipUntil_ = frame;
        return true;
    }

private:
    void pump() {
        if (!inEos_) {
            ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_, 5000);
            if (idx >= 0) {
                size_t cap = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec_, idx, &cap);
                ssize_t sz = AMediaExtractor_readSampleData(ex_, buf, cap);
                if (sz < 0) {
                    AMediaCodec_queueInputBuffer(codec_, idx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inEos_ = true;
                } else {
                    int64_t t = AMediaExtractor_getSampleTime(ex_);
                    AMediaCodec_queueInputBuffer(codec_, idx, 0, sz, t < 0 ? 0 : t, 0);
                    AMediaExtractor_advance(ex_);
                }
            }
        }
        AMediaCodecBufferInfo bi;
        ssize_t o = AMediaCodec_dequeueOutputBuffer(codec_, &bi, 5000);
        if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* f = AMediaCodec_getOutputFormat(codec_);
            int32_t v;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v)) rate_ = v;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v)) ch_ = v;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_PCM_ENCODING, &v)) enc_ = v;
            AMediaFormat_delete(f);
            return;
        }
        if (o < 0) return;
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getOutputBuffer(codec_, o, &cap);
        if (buf && bi.size > 0 && ch_ > 0) {
            const uint8_t* p = buf + bi.offset;
            int bps = enc_ == kEncFloat ? 4 : 2;
            int frames = bi.size / (bps * ch_);
            int64_t first = bi.presentationTimeUs * (int64_t)rate_ / 1000000;
            int skip = 0;
            if (skipUntil_ >= 0) {
                if (first + frames <= skipUntil_) skip = frames;
                else if (first < skipUntil_) skip = (int)(skipUntil_ - first);
                if (skip < frames) skipUntil_ = -1;
            }
            for (int i = skip; i < frames; ++i) {
                for (int c = 0; c < 2; ++c) {
                    int sc = c < ch_ ? c : 0;
                    double v;
                    if (enc_ == kEncFloat) {
                        float f;
                        memcpy(&f, p + (size_t)(i * ch_ + sc) * 4, 4);
                        v = f;
                    } else {
                        int16_t s;
                        memcpy(&s, p + (size_t)(i * ch_ + sc) * 2, 2);
                        v = s / 32768.0;
                    }
                    pending_.push_back(v);
                }
            }
            if (enc_ != kEncFloat) info_.bitsPerSample = 16;
        }
        if (bi.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outEos_ = true;
        AMediaCodec_releaseOutputBuffer(codec_, o, false);
    }

    int fd_;
    AMediaExtractor* ex_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    int rate_ = 0, ch_ = 0, enc_ = kEncPcm16;
    int64_t durUs_ = 0, skipUntil_ = -1;
    bool inEos_ = false, outEos_ = false;
    std::vector<double> pending_;
    size_t pendingPos_ = 0;
};

}  // namespace

std::unique_ptr<Decoder> openMediaCodec(int fd, std::string& error) {
    int own = ::dup(fd);
    if (own < 0) { error = "io"; return nullptr; }
    auto d = std::make_unique<CodecDecoder>(own);
    if (!d->init(error)) return nullptr;
    return d;
}

}  // namespace zx
