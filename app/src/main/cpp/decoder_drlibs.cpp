// FLAC / WAV / AIFF 解码：dr_flac + dr_wav（原生 32bit 整数输出，不经过安卓 MediaCodec，
// 因为 Android 9 的系统 FLAC 解码器会把 24bit 截成 16bit）。
#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
#include "dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "dr_wav.h"

#include <unistd.h>
#include <vector>

#include "decoder.h"

namespace zx {
namespace {

constexpr double kS32Scale = 1.0 / 2147483648.0;

size_t fdRead(void* user, void* buf, size_t n) {
    int fd = *static_cast<int*>(user);
    size_t total = 0;
    while (total < n) {
        ssize_t r = ::read(fd, static_cast<char*>(buf) + total, n - total);
        if (r <= 0) break;
        total += static_cast<size_t>(r);
    }
    return total;
}

bool fdSeek(void* user, int offset, int whence) {
    int fd = *static_cast<int*>(user);
    return ::lseek64(fd, offset, whence) >= 0;
}

bool fdTell(void* user, int64_t* cursor) {
    int fd = *static_cast<int*>(user);
    off64_t p = ::lseek64(fd, 0, SEEK_CUR);
    if (p < 0) return false;
    *cursor = p;
    return true;
}

int toWhence(int origin) {
    // DRFLAC_SEEK_SET/CUR/END 与 DRWAV_SEEK_SET/CUR/END 都是 0/1/2
    return origin == 0 ? SEEK_SET : origin == 1 ? SEEK_CUR : SEEK_END;
}

drflac_bool32 flacSeek(void* u, int off, drflac_seek_origin o) { return fdSeek(u, off, toWhence((int)o)); }
drflac_bool32 flacTell(void* u, drflac_int64* c) { int64_t v; if (!fdTell(u, &v)) return DRFLAC_FALSE; *c = v; return DRFLAC_TRUE; }
drwav_bool32 wavSeek(void* u, int off, drwav_seek_origin o) { return fdSeek(u, off, toWhence((int)o)); }
drwav_bool32 wavTell(void* u, drwav_int64* c) { int64_t v; if (!fdTell(u, &v)) return DRWAV_FALSE; *c = v; return DRWAV_TRUE; }

template <typename T>
void toStereo(const T* in, int frames, int ch, double scale, double* out) {
    if (ch == 1) {
        for (int i = 0; i < frames; ++i) {
            double v = in[i] * scale;
            out[2 * i] = v;
            out[2 * i + 1] = v;
        }
    } else {
        // 多声道文件只取前左/前右（不做下混，保持原样本）
        for (int i = 0; i < frames; ++i) {
            out[2 * i] = in[i * ch] * scale;
            out[2 * i + 1] = in[i * ch + 1] * scale;
        }
    }
}

class FlacDecoder : public Decoder {
public:
    FlacDecoder(int fd, drflac* f) : fd_(fd), f_(f) {}
    ~FlacDecoder() override {
        if (f_) drflac_close(f_);
        if (fd_ >= 0) ::close(fd_);
    }
    int* fdPtr() { return &fd_; }
    void attach(drflac* f) { f_ = f; }

    int read(double* out, int frames) override {
        int ch = f_->channels;
        buf_.resize(static_cast<size_t>(frames) * ch);
        auto got = drflac_read_pcm_frames_s32(f_, frames, buf_.data());
        toStereo(buf_.data(), (int)got, ch, kS32Scale, out);
        return (int)got;
    }
    bool seek(int64_t frame) override { return drflac_seek_to_pcm_frame(f_, (drflac_uint64)frame); }

    void fillInfo() {
        info_.codec = "FLAC";
        info_.sampleRate = info_.sourceRate = (int)f_->sampleRate;
        info_.bitsPerSample = f_->bitsPerSample;
        info_.channels = f_->channels;
        info_.totalFrames = (int64_t)f_->totalPCMFrameCount;
        info_.lossless = true;
    }

private:
    int fd_;
    drflac* f_;
    std::vector<int32_t> buf_;
};

class WavDecoder : public Decoder {
public:
    explicit WavDecoder(int fd) : fd_(fd) {}
    ~WavDecoder() override {
        if (inited_) drwav_uninit(&w_);
        if (fd_ >= 0) ::close(fd_);
    }
    bool init() {
        inited_ = drwav_init(&w_, fdRead, wavSeek, wavTell, &fd_, nullptr);
        if (!inited_) return false;
        isFloat_ = w_.translatedFormatTag == DR_WAVE_FORMAT_IEEE_FLOAT;
        info_.codec = w_.container == drwav_container_aiff ? "AIFF" : "WAV";
        info_.sampleRate = info_.sourceRate = (int)w_.sampleRate;
        info_.bitsPerSample = w_.bitsPerSample;
        info_.channels = w_.channels;
        info_.totalFrames = (int64_t)w_.totalPCMFrameCount;
        info_.lossless = true;
        return w_.channels >= 1 && w_.sampleRate > 0;
    }
    int read(double* out, int frames) override {
        int ch = w_.channels;
        if (isFloat_) {
            fbuf_.resize(static_cast<size_t>(frames) * ch);
            auto got = drwav_read_pcm_frames_f32(&w_, frames, fbuf_.data());
            toStereo(fbuf_.data(), (int)got, ch, 1.0, out);
            return (int)got;
        }
        ibuf_.resize(static_cast<size_t>(frames) * ch);
        auto got = drwav_read_pcm_frames_s32(&w_, frames, ibuf_.data());
        toStereo(ibuf_.data(), (int)got, ch, kS32Scale, out);
        return (int)got;
    }
    bool seek(int64_t frame) override { return drwav_seek_to_pcm_frame(&w_, (drwav_uint64)frame); }

private:
    int fd_;
    drwav w_{};
    bool inited_ = false;
    bool isFloat_ = false;
    std::vector<int32_t> ibuf_;
    std::vector<float> fbuf_;
};

}  // namespace

std::unique_ptr<Decoder> openFlac(int fd, std::string& error) {
    int own = ::dup(fd);
    if (own < 0) { error = "io"; return nullptr; }
    ::lseek64(own, 0, SEEK_SET);
    auto dec = std::make_unique<FlacDecoder>(own, nullptr);
    drflac* f = drflac_open(fdRead, flacSeek, flacTell, dec->fdPtr(), nullptr);
    if (!f) { error = "corrupt:FLAC"; return nullptr; }
    dec->attach(f);
    dec->fillInfo();
    return dec;
}

std::unique_ptr<Decoder> openWavOrAiff(int fd, std::string& error) {
    int own = ::dup(fd);
    if (own < 0) { error = "io"; return nullptr; }
    ::lseek64(own, 0, SEEK_SET);
    auto dec = std::make_unique<WavDecoder>(own);
    if (!dec->init()) { error = "corrupt:WAV/AIFF"; return nullptr; }
    return dec;
}

}  // namespace zx
