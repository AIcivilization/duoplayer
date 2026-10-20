// DSF (DSD Stream File) 解码：DSD -> PCM。
// 安卓公开 API 无法把原生 DSD 位流送进 ZX500 的 S-Master HX，所以这里用
// 高阶 Kaiser 窗 FIR + 字节查表做抽取，DSD64 -> 176.4kHz、DSD128 -> 176.4kHz（抽 32）等，
// 之后与 PCM 一样进入重采样/混音链路。
#include <unistd.h>

#include <cmath>
#include <cstring>
#include <vector>

#include "decoder.h"
#include "dsp_util.h"

namespace zx {
namespace {

constexpr uint8_t kDsdSilence = 0x69;

uint32_t le32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
uint64_t le64(const uint8_t* p) { return (uint64_t)le32(p) | ((uint64_t)le32(p + 4) << 32); }

class DsfDecoder : public Decoder {
public:
    explicit DsfDecoder(int fd) : fd_(fd) {}
    ~DsfDecoder() override { if (fd_ >= 0) ::close(fd_); }

    bool init(std::string& error) {
        uint8_t h[92];
        if (::pread64(fd_, h, sizeof(h), 0) != (ssize_t)sizeof(h)) { error = "corrupt:DSF"; return false; }
        if (memcmp(h, "DSD ", 4) != 0 || memcmp(h + 28, "fmt ", 4) != 0) { error = "corrupt:DSF"; return false; }
        uint64_t fmtSize = le64(h + 32);
        uint32_t formatId = le32(h + 44);
        chans_ = (int)le32(h + 52);
        fs_ = le32(h + 56);
        bitsPerSample_ = (int)le32(h + 60);
        sampleCount_ = le64(h + 64);
        blockSize_ = le32(h + 72);
        if (formatId != 0 || chans_ < 1 || chans_ > 6 || blockSize_ == 0 || fs_ < 2822400 ||
            (bitsPerSample_ != 1 && bitsPerSample_ != 8)) {
            error = "unsupported:DSF";
            return false;
        }
        uint8_t d[12];
        uint64_t dataHdr = 28 + fmtSize;
        if (::pread64(fd_, d, 12, (off64_t)dataHdr) != 12 || memcmp(d, "data", 4) != 0) { error = "corrupt:DSF"; return false; }
        dataOff_ = dataHdr + 12;

        // 抽取倍数：44.1k 系 DSD64 -> /16，DSD128 -> /32 ...（48k 系 DSD 同理得到 192k）
        long mult = std::lround(fs_ / 2822400.0);
        if (mult < 1) mult = 1;
        decim_ = 16 * (int)mult;
        step_ = decim_ / 8;
        outRate_ = (int)(fs_ / decim_);

        designFilter();

        info_.codec = "DSD" + std::to_string(64 * mult);
        info_.sampleRate = outRate_;
        info_.sourceRate = (int)fs_;
        info_.bitsPerSample = 1;
        info_.channels = chans_;
        info_.totalFrames = (int64_t)(sampleCount_ / decim_);
        info_.lossless = true;
        return seek(0);
    }

    int read(double* out, int frames) override {
        int produced = 0;
        while (produced < frames && outFrame_ < info_.totalFrames) {
            if ((int64_t)buf_[0].size() - pos_ < nbytes_) {
                if (!refill()) break;
                continue;
            }
            for (int c = 0; c < 2; ++c) {
                const uint8_t* w = buf_[c].data() + pos_;
                double acc = 0;
                const double* t = table_.data();
                for (int j = 0; j < nbytes_; ++j, t += 256) acc += t[w[j]];
                out[2 * produced + c] = acc;
            }
            pos_ += step_;
            ++produced;
            ++outFrame_;
        }
        return produced;
    }

    bool seek(int64_t frame) override {
        if (frame < 0) frame = 0;
        if (frame > info_.totalFrames) frame = info_.totalFrames;
        uint64_t dsdByte = (uint64_t)frame * step_;
        uint64_t block = dsdByte / blockSize_;
        nextBlock_ = block;
        consumedBytes_ = block * blockSize_;
        for (auto& b : buf_) b.assign(nbytes_ - step_, kDsdSilence);
        pos_ = 0;
        if (!refill()) return frame >= info_.totalFrames;
        pos_ = (int64_t)(dsdByte % blockSize_);
        outFrame_ = frame;
        return true;
    }

private:
    void designFilter() {
        // 通带 0~30kHz，阻带 70kHz 起 -120dB（远低于 24bit 底噪）
        const double A = 120.0, pass = 30000.0, stop = 70000.0;
        const double dw = 2.0 * M_PI * (stop - pass) / fs_;
        int K = (int)std::ceil((A - 8.0) / (2.285 * dw));
        K = (K + 7) / 8 * 8;
        nbytes_ = K / 8;
        std::vector<double> h(K);
        const double fc = 0.5 * (pass + stop) / fs_;
        const double beta = kaiserBeta(A);
        const double c = (K - 1) / 2.0;
        double sum = 0;
        for (int k = 0; k < K; ++k) {
            double x = k - c;
            h[k] = 2 * fc * sinc(2 * fc * x) * kaiser(x / (c + 0.5), beta);
            sum += h[k];
        }
        for (auto& v : h) v /= sum;  // 直流增益严格为 1：DSD 满幅 ±1 -> PCM ±1
        table_.assign((size_t)nbytes_ * 256, 0.0);
        for (int j = 0; j < nbytes_; ++j) {
            for (int b = 0; b < 256; ++b) {
                double acc = 0;
                for (int i = 0; i < 8; ++i) {
                    int bit = bitsPerSample_ == 1 ? (b >> i) & 1 : (b >> (7 - i)) & 1;  // DSF 默认 LSB 先行
                    acc += h[j * 8 + i] * (bit ? 1.0 : -1.0);
                }
                table_[(size_t)j * 256 + b] = acc;
            }
        }
        for (auto& b : buf_) b.reserve(nbytes_ + blockSize_ + 64);
    }

    // 读入下一个块组（每声道 blockSize 字节），追加到每声道缓冲。
    bool refill() {
        // 先压缩已消费部分
        if (pos_ > 0) {
            for (auto& b : buf_) b.erase(b.begin(), b.begin() + std::min<int64_t>(pos_, (int64_t)b.size()));
            pos_ = 0;
        }
        const uint64_t validBytes = (sampleCount_ + 7) / 8;
        if (consumedBytes_ >= validBytes + (uint64_t)nbytes_) return false;
        std::vector<uint8_t>& raw = raw_;
        raw.resize((size_t)blockSize_ * chans_);
        off64_t off = (off64_t)(dataOff_ + nextBlock_ * (uint64_t)blockSize_ * chans_);
        ssize_t r = consumedBytes_ < validBytes ? ::pread64(fd_, raw.data(), raw.size(), off) : 0;
        if (r < (ssize_t)raw.size()) {
            if (r < 0) r = 0;
            memset(raw.data() + r, kDsdSilence, raw.size() - r);
        }
        for (int c = 0; c < 2; ++c) {
            int src = c < chans_ ? c : 0;
            const uint8_t* p = raw.data() + (size_t)src * blockSize_;
            for (uint32_t i = 0; i < blockSize_; ++i) {
                uint64_t idx = consumedBytes_ + i;
                buf_[c].push_back(idx < validBytes ? p[i] : kDsdSilence);
            }
        }
        consumedBytes_ += blockSize_;
        ++nextBlock_;
        return true;
    }

    int fd_;
    int chans_ = 0, bitsPerSample_ = 1, decim_ = 16, step_ = 2, outRate_ = 176400, nbytes_ = 0;
    uint32_t fs_ = 0, blockSize_ = 4096;
    uint64_t sampleCount_ = 0, dataOff_ = 0, nextBlock_ = 0, consumedBytes_ = 0;
    int64_t pos_ = 0, outFrame_ = 0;
    std::vector<double> table_;
    std::vector<uint8_t> buf_[2];
    std::vector<uint8_t> raw_;
};

}  // namespace

std::unique_ptr<Decoder> openDsf(int fd, std::string& error) {
    int own = ::dup(fd);
    if (own < 0) { error = "io"; return nullptr; }
    auto d = std::make_unique<DsfDecoder>(own);
    if (!d->init(error)) return nullptr;
    return d;
}

}  // namespace zx
