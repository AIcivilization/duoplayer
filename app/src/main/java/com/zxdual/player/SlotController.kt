package com.zxdual.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.zxdual.player.audio.NativeEngine
import com.zxdual.player.lyrics.EmbeddedLyrics
import com.zxdual.player.lyrics.LrcParser
import com.zxdual.player.lyrics.Lyrics
import com.zxdual.player.lyrics.TextDecode
import com.zxdual.player.model.FileRef
import com.zxdual.player.model.SlotConfig
import java.io.FileInputStream

/** UI 用的单路快照 */
data class SlotSnapshot(
    val index: Int,
    val hasFile: Boolean,
    val fileName: String,
    val title: String,
    val artist: String,
    val formatLine: String,
    val pathLine: String,
    val bitPerfect: Boolean,
    val enabled: Boolean,
    val playing: Boolean,
    val ended: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val gainDb: Double,
    val balance: Double,
    val loop: Boolean,
    val error: String,
    val lyrics: Lyrics?,
)

/**
 * A 或 B 一路：持有一个文件和它的音量/声像/循环/开关设置。只在 engine 线程调用。
 */
class SlotController(
    private val ctx: Context,
    private val engine: NativeEngine,
    val index: Int,
) {
    var config = SlotConfig()
        private set
    /** 文件列表模式下 A 不循环（否则列表无法前进），但不改用户保存的设置 */
    var forceNoLoop = false
        set(v) { field = v; engine.setLoop(index, effectiveLoop()) }

    private var info: NativeEngine.LoadResult? = null
    private var loadedUri: String? = null
    private var title = ""
    private var artist = ""
    private var error = ""
    private var lyrics: Lyrics? = null

    val loaded: Boolean get() = info != null
    fun effectiveLoop() = config.loop && !forceNoLoop
    val displayName: String? get() = config.file?.let { title.ifEmpty { it.name.substringBeforeLast('.') } }

    /** 应用一份设置；文件变了才重新加载 */
    fun apply(c: SlotConfig) {
        val fileChanged = c.file?.uri != loadedUri || (c.file != null && info == null)
        val lrcChanged = c.file?.lrc?.uri != config.file?.lrc?.uri
        config = c
        if (fileChanged) load() else if (lrcChanged && c.file != null) lyrics = loadLyrics(c.file)
        engine.setGainDb(index, nativeDb(c.gainDb))
        engine.setBalance(index, c.balance)
        engine.setLoop(index, effectiveLoop())
        if (!c.enabled) engine.pause(index)
    }

    private fun load() {
        val f = config.file
        info = null
        lyrics = null
        error = ""
        title = ""
        artist = ""
        loadedUri = f?.uri
        if (f == null) {
            engine.unload(index)
            return
        }
        Log.i(TAG, "slot$index load ${f.name}")
        val res = try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(f.uri), "r")?.use { pfd -> engine.load(index, pfd.fd) }
                ?: NativeEngine.LoadResult(false, error = "无法打开文件")
        } catch (e: SecurityException) {
            NativeEngine.LoadResult(false, error = "没有访问权限：请在「列表」页用「重新关联文件夹」找回文件")
        } catch (e: Exception) {
            NativeEngine.LoadResult(false, error = if (e is java.io.FileNotFoundException) "找不到文件：请在「列表」页用「重新关联文件夹」找回" else e.message ?: e.javaClass.simpleName)
        }
        if (!res.ok) {
            engine.unload(index)
            error = res.error
            return
        }
        info = res
        readTags(f)
        lyrics = loadLyrics(f)
    }

    fun play() {
        if (!config.enabled || info == null) return
        engine.play(index)
    }

    fun pause() = engine.pause(index)
    fun seek(ms: Long) = engine.seek(index, ms)
    fun state() = engine.deckState(index)

    /** 歌词优先级：同名 .lrc > 文件内嵌歌词 */
    private fun loadLyrics(f: FileRef): Lyrics? {
        val lrc = f.lrc
        if (lrc != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(Uri.parse(lrc.uri))?.use { it.readBytes() }
                if (bytes != null && bytes.size < (4 shl 20)) {
                    LrcParser.parse(TextDecode.auto(bytes), "LRC")?.let { return it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "lrc read failed: ${e.message}")
            }
        }
        return try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(f.uri), "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch -> EmbeddedLyrics.read(ch) }
            }?.let { LrcParser.parse(it, "内嵌") }
        } catch (e: Exception) {
            null
        }
    }

    private fun readTags(f: FileRef) {
        title = f.name.substringBeforeLast('.')
        artist = ""
        try {
            MediaMetadataRetriever().apply {
                setDataSource(ctx, Uri.parse(f.uri))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                artist = listOfNotNull(
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                ).filter { it.isNotBlank() }.joinToString(" · ")
                release()
            }
        } catch (_: Exception) {
            // DSF 等格式系统读不了标签，用文件名即可
        }
    }

    fun snapshot(st: NativeEngine.DeckState, outRate: Int, otherPlaying: Boolean): SlotSnapshot {
        val i = info
        var formatLine = ""
        var pathLine = ""
        var bitPerfect = false
        if (i != null) {
            val dsd = i.codec.startsWith("DSD")
            val bits = if (dsd) "1bit" else "${i.bits}bit"
            val src = if (dsd) "%.4f MHz".format(i.sourceRate / 1e6) else khz(i.sourceRate)
            formatLine = "${i.codec} · $bits · $src" + if (i.lossless) " · 无损" else " · 有损"
            val c = config
            pathLine = when {
                outRate <= 0 -> ""
                dsd -> "DSD→PCM ${khz(i.sampleRate)}" + (if (i.sampleRate != outRate) " → ${khz(outRate)}" else "")
                i.sampleRate == outRate -> {
                    val unity = c.gainDb == 0.0 && c.balance == 0.0
                    bitPerfect = i.lossless && i.bits in 1..24 && unity && !otherPlaying
                    when {
                        !unity -> "直通 ${khz(outRate)} · 64bit 增益"
                        bitPerfect -> "直通 ${khz(outRate)} · 位精确"
                        else -> "直通 ${khz(outRate)}"
                    }
                }
                else -> "重采样 ${khz(i.sampleRate)} → ${khz(outRate)}"
            } + if (otherPlaying && st.playing) " · 混音" else ""
        }
        return SlotSnapshot(
            index = index,
            hasFile = config.file != null,
            fileName = config.file?.name ?: "",
            title = displayName ?: "",
            artist = artist,
            formatLine = formatLine,
            pathLine = pathLine,
            bitPerfect = bitPerfect,
            enabled = config.enabled,
            playing = st.playing,
            ended = st.ended,
            positionMs = st.positionMs,
            durationMs = if (st.durationMs > 0) st.durationMs else i?.durationMs ?: 0,
            gainDb = config.gainDb,
            balance = config.balance,
            loop = config.loop,
            error = error,
            lyrics = lyrics,
        )
    }

    private fun nativeDb(db: Double) = if (db <= -60.0) -100.0 else db  // 滑到底 = 静音

    companion object {
        const val TAG = "ZXDual"

        fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0)
    }
}
