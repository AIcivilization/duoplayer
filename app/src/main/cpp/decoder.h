#pragma once
// 解码器统一接口：输出交错立体声 double（[-1, 1)），整数样本按 2^-31 精确换算，
// 24bit 及以下的 PCM 在 double/float 中都是无损表示。
#include <cstdint>
#include <memory>
#include <string>

namespace zx {

struct StreamInfo {
    std::string codec;        // "FLAC" / "WAV" / "AIFF" / "DSD64" / "AAC" ...
    int sampleRate = 0;       // 解码器输出（PCM）采样率
    int sourceRate = 0;       // 文件原始采样率（DSD 时为 2822400 等）
    int bitsPerSample = 0;    // 原始位深（DSD 为 1）
    int channels = 0;         // 原始声道数
    int64_t totalFrames = 0;  // 以 sampleRate 计的总帧数，未知为 0
    bool lossless = false;
};

class Decoder {
public:
    virtual ~Decoder() = default;
    const StreamInfo& info() const { return info_; }
    // 读取最多 frames 帧立体声到 out（长度 frames*2）。返回实际帧数，0 表示结束。
    virtual int read(double* out, int frames) = 0;
    // 跳转到 frame（以 info().sampleRate 计）。
    virtual bool seek(int64_t frame) = 0;

protected:
    StreamInfo info_;
};

// fd 由调用方持有；解码器内部会 dup()，可在返回后关闭原 fd。
std::unique_ptr<Decoder> openDecoder(int fd, std::string& error);

std::unique_ptr<Decoder> openFlac(int fd, std::string& error);
std::unique_ptr<Decoder> openWavOrAiff(int fd, std::string& error);
std::unique_ptr<Decoder> openDsf(int fd, std::string& error);
std::unique_ptr<Decoder> openMediaCodec(int fd, std::string& error);

}  // namespace zx
