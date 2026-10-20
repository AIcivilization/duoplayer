#include <jni.h>

#include <string>

#include "engine.h"

using zx::Engine;

namespace {
Engine* E(jlong h) { return reinterpret_cast<Engine*>(h); }
bool validDeck(jint d) { return d >= 0 && d < Engine::kDecks; }
jstring str(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
}  // namespace

#define FN(name) Java_com_zxdual_player_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(nCreate)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new Engine()); }

JNIEXPORT void JNICALL FN(nDestroy)(JNIEnv*, jclass, jlong h) { delete E(h); }

// 返回 "OK\tcodec\trate\tsourceRate\tbits\tchannels\tdurationMs\tlossless" 或 "ERR\t原因"
JNIEXPORT jstring JNICALL FN(nLoad)(JNIEnv* env, jclass, jlong h, jint d, jint fd) {
    if (!validDeck(d)) return str(env, "ERR\t无效的轨道");
    zx::StreamInfo info;
    std::string err = E(h)->load(d, fd, info);
    if (!err.empty()) return str(env, "ERR\t" + err);
    int64_t dur = info.sampleRate > 0 ? info.totalFrames * 1000 / info.sampleRate : 0;
    std::string s = "OK\t" + info.codec + "\t" + std::to_string(info.sampleRate) + "\t" + std::to_string(info.sourceRate) +
                    "\t" + std::to_string(info.bitsPerSample) + "\t" + std::to_string(info.channels) + "\t" +
                    std::to_string(dur) + "\t" + (info.lossless ? "1" : "0");
    return str(env, s);
}

JNIEXPORT void JNICALL FN(nUnload)(JNIEnv*, jclass, jlong h, jint d) { if (validDeck(d)) E(h)->deck(d).unload(); }
JNIEXPORT void JNICALL FN(nPlay)(JNIEnv*, jclass, jlong h, jint d) { if (validDeck(d)) E(h)->play(d); }
JNIEXPORT void JNICALL FN(nPause)(JNIEnv*, jclass, jlong h, jint d) { if (validDeck(d)) E(h)->pause(d); }
JNIEXPORT void JNICALL FN(nSeek)(JNIEnv*, jclass, jlong h, jint d, jlong ms) { if (validDeck(d)) E(h)->deck(d).seekMs(ms); }
JNIEXPORT void JNICALL FN(nSetGainDb)(JNIEnv*, jclass, jlong h, jint d, jdouble db) { if (validDeck(d)) E(h)->deck(d).setGainDb(db); }
JNIEXPORT void JNICALL FN(nSetBalance)(JNIEnv*, jclass, jlong h, jint d, jdouble b) { if (validDeck(d)) E(h)->deck(d).setBalance(b); }
JNIEXPORT void JNICALL FN(nSetLoop)(JNIEnv*, jclass, jlong h, jint d, jboolean l) { if (validDeck(d)) E(h)->deck(d).setLoop(l); }

// [loaded, playing, ended, looping, positionMs, durationMs, underruns]
JNIEXPORT jlongArray JNICALL FN(nDeckStatus)(JNIEnv* env, jclass, jlong h, jint d) {
    jlong v[7] = {0};
    if (validDeck(d)) {
        auto s = E(h)->deck(d).status();
        v[0] = s.loaded; v[1] = s.playing; v[2] = s.ended; v[3] = s.looping;
        v[4] = s.positionMs; v[5] = s.durationMs; v[6] = s.underruns;
    }
    jlongArray arr = env->NewLongArray(7);
    env->SetLongArrayRegion(arr, 0, 7, v);
    return arr;
}

JNIEXPORT void JNICALL FN(nTick)(JNIEnv*, jclass, jlong h) { E(h)->tick(); }
JNIEXPORT void JNICALL FN(nSetOutputMode)(JNIEnv*, jclass, jlong h, jint m) {
    E(h)->setOutputMode(m == 1 ? zx::OutputMode::Standard : zx::OutputMode::PowerSaving);
}

// "rate\tburst\tbuffer\trunning\tlimiterHits\txruns\tmode\terror"
JNIEXPORT jstring JNICALL FN(nOutputStatus)(JNIEnv* env, jclass, jlong h) {
    auto s = E(h)->outputStatus();
    std::string r = std::to_string(s.sampleRate) + "\t" + std::to_string(s.framesPerBurst) + "\t" +
                    std::to_string(s.bufferFrames) + "\t" + (s.running ? "1" : "0") + "\t" +
                    std::to_string(s.limiterHits) + "\t" + std::to_string(s.xruns) + "\t" + s.mode + "\t" + s.error;
    return str(env, r);
}

}  // extern "C"
