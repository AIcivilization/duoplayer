package com.zxdual.player.lyrics

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 一句歌词：同一时间戳下可以有多行（原文 + 翻译） */
data class LyricLine(val timeMs: Long, val texts: List<String>)

class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    /** 当前时间对应的行号；早于第一句返回 -1 */
    fun indexAt(posMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    val isBilingual: Boolean get() = lines.any { it.texts.size >= 2 }
}

enum class LyricMode(val label: String) { BOTH("双语"), ZH("中文"), EN("English") }

object LyricText {
    private val HAN = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")

    fun hasHan(s: String) = HAN.containsMatchIn(s)

    /** 按显示模式挑出要显示的行 */
    fun pick(line: LyricLine, mode: LyricMode): List<String> {
        val t = line.texts.filter { it.isNotBlank() }
        if (t.size <= 1) return t
        return when (mode) {
            LyricMode.BOTH -> t
            LyricMode.ZH -> listOf(t.firstOrNull { hasHan(it) } ?: t.first())
            LyricMode.EN -> listOf(t.firstOrNull { !hasHan(it) } ?: t.last())
        }
    }
}

object LrcParser {
    private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val TAG = Regex("^\\[([a-zA-Z#]+):(.*)]\\s*$")
    private val WORD_TIME = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    fun parse(text: String, source: String): Lyrics? {
        var offset = 0L
        val timed = LinkedHashMap<Long, MutableList<String>>()
        val plain = mutableListOf<String>()
        for (raw in text.replace("﻿", "").split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val stamps = mutableListOf<Long>()
            var rest = line.trimStart()
            while (true) {
                val m = TIME.find(rest)?.takeIf { it.range.first == 0 } ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                stamps.add(min * 60_000 + sec * 1000 + frac)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                val tag = TAG.matchEntire(line.trim())
                if (tag != null) {
                    if (tag.groupValues[1].equals("offset", true)) offset = tag.groupValues[2].trim().toLongOrNull() ?: 0L
                    continue
                }
                plain.add(line.trim())
                continue
            }
            val content = rest.replace(WORD_TIME, "").trim()
            for (t in stamps) timed.getOrPut(t) { mutableListOf() }.let { if (content.isNotEmpty() || it.isEmpty()) it.add(content) }
        }
        if (timed.isNotEmpty()) {
            val lines = timed.entries
                .map { (t, texts) -> LyricLine((t - offset).coerceAtLeast(0), splitInline(texts.filter { it.isNotEmpty() })) }
                .sortedBy { it.timeMs }
            return Lyrics(lines, synced = true, source = source)
        }
        if (plain.isNotEmpty()) return Lyrics(plain.map { LyricLine(-1, splitInline(listOf(it))) }, synced = false, source = source)
        return null
    }

    /** 「原文 / 译文」写在同一行时拆成两行（一边含汉字、另一边不含才拆） */
    private fun splitInline(texts: List<String>): List<String> {
        if (texts.size != 1) return texts
        val s = texts[0]
        for (sep in listOf(" / ", " | ", "｜", " // ")) {
            val i = s.indexOf(sep)
            if (i > 0) {
                val a = s.substring(0, i).trim()
                val b = s.substring(i + sep.length).trim()
                if (a.isNotEmpty() && b.isNotEmpty() && LyricText.hasHan(a) != LyricText.hasHan(b)) return listOf(a, b)
            }
        }
        return texts
    }
}

object TextDecode {
    private fun strict(cs: Charset, b: ByteArray): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: Exception) {
        null
    }

    /** 自动识别编码：BOM → UTF-8 → GB18030（中文 LRC 很多是 GBK）→ ISO-8859-1 */
    fun auto(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        strict(Charsets.UTF_8, b)?.let { return it }
        strict(Charset.forName("GB18030"), b)?.let { if (LyricText.hasHan(it)) return it }
        return String(b, Charsets.ISO_8859_1)
    }
}
