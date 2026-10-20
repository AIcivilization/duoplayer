#include "decoder.h"
namespace zx { std::unique_ptr<Decoder> openMediaCodec(int, std::string& e){ e="no mediacodec on host"; return nullptr; } }
