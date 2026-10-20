#include <unistd.h>

#include <cstring>

#include "decoder.h"

namespace zx {

std::unique_ptr<Decoder> openDecoder(int fd, std::string& error) {
    uint8_t magic[12] = {0};
    ssize_t n = ::pread64(fd, magic, sizeof(magic), 0);
    if (n < 4) { error = "io"; return nullptr; }

    std::unique_ptr<Decoder> d;
    if (memcmp(magic, "fLaC", 4) == 0 || memcmp(magic, "ID3", 3) == 0) {
        d = openFlac(fd, error);  // ID3 开头的也可能是 MP3，失败后交给 MediaCodec
    } else if (memcmp(magic, "RIFF", 4) == 0 || memcmp(magic, "RF64", 4) == 0 ||
               memcmp(magic, "riff", 4) == 0 || memcmp(magic, "FORM", 4) == 0 ||
               memcmp(magic, "BW64", 4) == 0) {
        d = openWavOrAiff(fd, error);
    } else if (memcmp(magic, "DSD ", 4) == 0) {
        return openDsf(fd, error);
    }
    if (d) return d;
    std::string first = error;
    d = openMediaCodec(fd, error);
    if (!d && !first.empty()) error = first;  // 优先报告原生解码器的错误
    return d;
}

}  // namespace zx
