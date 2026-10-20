package com.zxdual.player.lyrics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 读取音频文件内嵌的歌词：
 *  - FLAC：Vorbis Comment 的 LYRICS / UNSYNCEDLYRICS
 *  - MP3 / DSF / WAV / AIFF：ID3v2 的 USLT 帧（或 TXXX:LYRICS）
 * 只按需读取标签区，跳过封面等大块数据。
 */
object EmbeddedLyrics {
    private const val MAX_TEXT = 2 shl 20  // 单个歌词字段最多 2MB
    private val VORBIS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")

    fun read(ch: FileChannel): String? = try {
        readAny(ch)
    } catch (_: Exception) {
        null
    }

    private fun readAny(ch: FileChannel): String? {
        val head = readAt(ch, 0, 12) ?: return null
        return when {
            head.startsWith("fLaC") -> flac(ch, 4)
            head.startsWith("ID3") -> id3(ch, 0) ?: id3Size(ch, 0)?.let { if (readAt(ch, it, 4)?.startsWith("fLaC") == true) flac(ch, it + 4) else null }
            head.startsWith("DSD ") -> {
                val meta = le64(readAt(ch, 20, 8) ?: return null)
                if (meta > 0) id3(ch, meta) else null
            }
            head.startsWith("RIFF") -> chunks(ch, 12, littleEndian = true)
            head.startsWith("FORM") -> chunks(ch, 12, littleEndian = false)
            else -> null
        }
    }

    // ---------------- FLAC ----------------

    private fun flac(ch: FileChannel, start: Long): String? {
        var pos = start
        while (true) {
            val h = readAt(ch, pos, 4) ?: return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4 && len in 8..(16 shl 20)) {
                val b = readAt(ch, pos + 4, len) ?: return null
                return vorbis(b)
            }
            if (last) return null
            pos += 4 + len
        }
    }

    private fun vorbis(b: ByteArray): String? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val vendor = bb.int
        if (vendor < 0 || vendor > bb.remaining()) return null
        bb.position(bb.position() + vendor)
        val n = bb.int
        repeat(n.coerceAtMost(10000)) {
            if (bb.remaining() < 4) return null
            val len = bb.int
            if (len < 0 || len > bb.remaining()) return null
            val start = bb.position()
            val eq = (start until start + len).firstOrNull { b[it] == '='.code.toByte() }
            if (eq != null) {
                val key = String(b, start, eq - start, Charsets.US_ASCII).uppercase()
                if (key in VORBIS_KEYS) return String(b, eq + 1, start + len - eq - 1, Charsets.UTF_8)
            }
            bb.position(start + len)
        }
        return null
    }

    // ---------------- ID3v2 ----------------

    private fun id3Size(ch: FileChannel, off: Long): Long? {
        val h = readAt(ch, off, 10) ?: return null
        if (!h.startsWith("ID3")) return null
        val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
        return off + 10 + syncsafe(h, 6) + footer
    }

    private fun id3(ch: FileChannel, off: Long): String? {
        val h = readAt(ch, off, 10) ?: return null
        if (!h.startsWith("ID3")) return null
        val ver = h[3].toInt()
        if (ver != 3 && ver != 4) return null
        val flags = h[5].toInt()
        val end = off + 10 + syncsafe(h, 6)
        var pos = off + 10
        if (flags and 0x40 != 0) {  // 扩展头
            val e = readAt(ch, pos, 4) ?: return null
            pos += if (ver == 4) syncsafe(e, 0) else 4 + be32(e, 0)
        }
        var txxx: String? = null
        while (pos + 10 <= end) {
            val fh = readAt(ch, pos, 10) ?: return null
            if (fh[0].toInt() == 0) break  // padding
            val id = String(fh, 0, 4, Charsets.US_ASCII)
            val size = if (ver == 4) syncsafe(fh, 4) else be32(fh, 4)
            if (size <= 0 || pos + 10 + size > end) break
            val fflags = ((fh[8].toInt() and 0xFF) shl 8) or (fh[9].toInt() and 0xFF)
            if ((id == "USLT" || id == "TXXX") && size <= MAX_TEXT) {
                var body = readAt(ch, pos + 10, size) ?: return null
                if (ver == 4 && fflags and 0x0001 != 0) body = body.copyOfRange(4, body.size)  // data length indicator
                if ((ver == 4 && fflags and 0x0002 != 0) || (ver == 3 && flags and 0x80 != 0)) body = unsync(body)
                if (id == "USLT") {
                    uslt(body)?.let { return it }
                } else if (txxx == null) {
                    txxx = txxxLyrics(body)
                }
            }
            pos += 10 + size
        }
        return txxx
    }

    /** USLT: 编码(1) 语言(3) 描述\0 歌词 */
    private fun uslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt()
        val (_, textStart) = terminated(b, 4, enc)
        return decode(b, textStart, b.size, enc).takeIf { it.isNotBlank() }
    }

    /** TXXX: 编码(1) 描述\0 值；只取描述为 LYRICS / UNSYNCEDLYRICS / USLT 的 */
    private fun txxxLyrics(b: ByteArray): String? {
        if (b.size < 3) return null
        val enc = b[0].toInt()
        val (descEnd, valueStart) = terminated(b, 1, enc)
        val desc = decode(b, 1, descEnd, enc).trim().uppercase()
        // ffmpeg 等工具会把歌词写成 TXXX:USLT
        if (desc != "LYRICS" && desc != "UNSYNCEDLYRICS" && desc != "USLT") return null
        return decode(b, valueStart, b.size, enc).takeIf { it.isNotBlank() }
    }

    /** 返回 (字符串结束位置, 下一字段开始位置) */
    private fun terminated(b: ByteArray, from: Int, enc: Int): Pair<Int, Int> {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i to i + 2
                i += 2
            }
            return b.size to b.size
        }
        var i = from
        while (i < b.size) {
            if (b[i].toInt() == 0) return i to i + 1
            i++
        }
        return b.size to b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (from >= to) return ""
        val s = b.copyOfRange(from, to)
        return when (enc) {
            1 -> TextDecode.auto(s).trimEnd('\u0000')  // 带 BOM 的 UTF-16
            2 -> String(s, Charsets.UTF_16BE).trimEnd('\u0000')
            3 -> String(s, Charsets.UTF_8).trimEnd('\u0000')
            else -> TextDecode.auto(s).trimEnd('\u0000')  // 标称 Latin-1，中文文件里常是 GBK
        }
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---------------- RIFF / AIFF 内的 ID3 块 ----------------

    private fun chunks(ch: FileChannel, start: Long, littleEndian: Boolean): String? {
        var pos = start
        val size = ch.size()
        while (pos + 8 <= size) {
            val h = readAt(ch, pos, 8) ?: return null
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val len = if (littleEndian) le32(h, 4) else be32(h, 4).toLong()
            if (id.equals("id3 ", true) || id == "ID32") return id3(ch, pos + 8)
            if (len < 0) return null
            pos += 8 + len + (len and 1)
        }
        return null
    }

    // ---------------- 工具 ----------------

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0) return null
        val bb = ByteBuffer.allocate(len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n <= 0) return null
            p += n
        }
        return bb.array()
    }

    private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }
    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF)) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun le64(b: ByteArray): Long = le32(b, 0) or (le32(b, 4) shl 32)
}
