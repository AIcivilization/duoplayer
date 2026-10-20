package com.zxdual.player.audio

/**
 * C++ 双轨引擎的薄封装。所有调用必须来自同一线程（PlaybackService 的 engine 线程）。
 */
class NativeEngine {
    private var handle: Long = nCreate()

    data class LoadResult(
        val ok: Boolean,
        val error: String = "",
        val codec: String = "",
        val sampleRate: Int = 0,
        val sourceRate: Int = 0,
        val bits: Int = 0,
        val channels: Int = 0,
        val durationMs: Long = 0,
        val lossless: Boolean = false,
    )

    data class DeckState(
        val loaded: Boolean,
        val playing: Boolean,
        val ended: Boolean,
        val looping: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val underruns: Long,
    )

    data class OutputState(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val bufferFrames: Int,
        val running: Boolean,
        val limiterHits: Int,
        val xruns: Int,
        val mode: String,
        val error: String,
    )

    fun load(deck: Int, fd: Int): LoadResult {
        val p = nLoad(handle, deck, fd).split('\t')
        if (p[0] != "OK") return LoadResult(false, error = p.getOrElse(1) { "未知错误" })
        return LoadResult(
            ok = true, codec = p[1], sampleRate = p[2].toInt(), sourceRate = p[3].toInt(),
            bits = p[4].toInt(), channels = p[5].toInt(), durationMs = p[6].toLong(), lossless = p[7] == "1",
        )
    }

    fun unload(deck: Int) = nUnload(handle, deck)
    fun play(deck: Int) = nPlay(handle, deck)
    fun pause(deck: Int) = nPause(handle, deck)
    fun seek(deck: Int, ms: Long) = nSeek(handle, deck, ms)
    fun setGainDb(deck: Int, db: Double) = nSetGainDb(handle, deck, db)
    fun setBalance(deck: Int, b: Double) = nSetBalance(handle, deck, b)
    fun setLoop(deck: Int, loop: Boolean) = nSetLoop(handle, deck, loop)
    fun tick() = nTick(handle)
    fun setOutputMode(standard: Boolean) = nSetOutputMode(handle, if (standard) 1 else 0)

    fun deckState(deck: Int): DeckState {
        val v = nDeckStatus(handle, deck)
        return DeckState(v[0] != 0L, v[1] != 0L, v[2] != 0L, v[3] != 0L, v[4], v[5], v[6])
    }

    fun outputState(): OutputState {
        val p = nOutputStatus(handle).split('\t')
        return OutputState(
            p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1", p[4].toInt(), p[5].toInt(), p[6],
            p.getOrElse(7) { "" },
        )
    }

    fun release() {
        if (handle != 0L) {
            nDestroy(handle)
            handle = 0
        }
    }

    companion object {
        init {
            System.loadLibrary("zxaudio")
        }

        @JvmStatic private external fun nCreate(): Long
        @JvmStatic private external fun nDestroy(h: Long)
        @JvmStatic private external fun nLoad(h: Long, deck: Int, fd: Int): String
        @JvmStatic private external fun nUnload(h: Long, deck: Int)
        @JvmStatic private external fun nPlay(h: Long, deck: Int)
        @JvmStatic private external fun nPause(h: Long, deck: Int)
        @JvmStatic private external fun nSeek(h: Long, deck: Int, ms: Long)
        @JvmStatic private external fun nSetGainDb(h: Long, deck: Int, db: Double)
        @JvmStatic private external fun nSetBalance(h: Long, deck: Int, b: Double)
        @JvmStatic private external fun nSetLoop(h: Long, deck: Int, loop: Boolean)
        @JvmStatic private external fun nDeckStatus(h: Long, deck: Int): LongArray
        @JvmStatic private external fun nTick(h: Long)
        @JvmStatic private external fun nSetOutputMode(h: Long, mode: Int)
        @JvmStatic private external fun nOutputStatus(h: Long): String
    }
}
