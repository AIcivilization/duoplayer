package com.zxdual.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class LyricsTest {

    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    /** 所有测试文件里的歌词内容相同，统一校验 */
    private fun checkSample(l: Lyrics?) {
        assertNotNull(l)
        l!!
        assertTrue(l.synced)
        // offset:+100 → 所有时间提前 100ms
        assertEquals(listOf(900L, 3400L, 5023L, 5900L, 7150L), l.lines.map { it.timeMs })
        assertEquals(listOf("Hello world", "你好，世界"), l.lines[0].texts)
        assertEquals(listOf("Chorus line", "副歌这一句"), l.lines[1].texts)  // 同行「原文 / 译文」被拆开
        assertEquals(listOf("只有中文"), l.lines[2].texts)
        assertTrue(l.lines[3].texts.isEmpty())  // 空时间戳 = 间奏
        assertEquals(l.lines[1].texts, l.lines[4].texts)  // 一行多个时间戳
        assertTrue(l.isBilingual)
    }

    @Test fun parseUtf8() {
        val text = res("gbk.lrc").readBytes().let { String(it, charset("GB18030")) }
        checkSample(LrcParser.parse(text, "LRC"))
    }

    @Test fun autoDecodeGbkAndUtf16() {
        checkSample(LrcParser.parse(TextDecode.auto(res("gbk.lrc").readBytes()), "LRC"))
        checkSample(LrcParser.parse(TextDecode.auto(res("utf16.lrc").readBytes()), "LRC"))
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01.00]你好".toByteArray()
        assertEquals("[00:01.00]你好", TextDecode.auto(utf8Bom))
    }

    @Test fun indexAt() {
        val l = LrcParser.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c", "x")!!
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(1000))
        assertEquals(1, l.indexAt(2999))
        assertEquals(2, l.indexAt(99999))
    }

    @Test fun modes() {
        val line = LyricLine(0, listOf("Hello world", "你好，世界"))
        assertEquals(listOf("Hello world", "你好，世界"), LyricText.pick(line, LyricMode.BOTH))
        assertEquals(listOf("你好，世界"), LyricText.pick(line, LyricMode.ZH))
        assertEquals(listOf("Hello world"), LyricText.pick(line, LyricMode.EN))
        // 中文歌 + 英文翻译，顺序反过来也能挑对
        val zhFirst = LyricLine(0, listOf("月亮代表我的心", "The moon represents my heart"))
        assertEquals(listOf("The moon represents my heart"), LyricText.pick(zhFirst, LyricMode.EN))
        assertEquals(listOf("月亮代表我的心"), LyricText.pick(zhFirst, LyricMode.ZH))
    }

    @Test fun unsyncedAndEnhanced() {
        val plain = LrcParser.parse("第一行\nsecond line\n", "x")!!
        assertFalse(plain.synced)
        assertEquals(2, plain.lines.size)
        val enhanced = LrcParser.parse("[00:10.00]<00:10.00>Hel<00:10.50>lo", "x")!!
        assertEquals(listOf("Hello"), enhanced.lines[0].texts)
        val threeDigits = LrcParser.parse("[01:02.345]x\n[1:2.3]y", "x")!!
        assertEquals(listOf(62300L, 62345L), threeDigits.lines.map { it.timeMs })
    }

    private fun embedded(name: String): String? =
        RandomAccessFile(res(name), "r").use { f -> EmbeddedLyrics.read(f.channel) }

    @Test fun embeddedFlacVorbisComment() = checkSample(embedded("emb.flac")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedMp3TxxxV3() = checkSample(embedded("emb3.mp3")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedMp3TxxxV4() = checkSample(embedded("emb4.mp3")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedMp3UsltUtf16AfterCover() = checkSample(embedded("uslt3_utf16.mp3")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedMp3UsltGbk() = checkSample(embedded("uslt4_gbk.mp3")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedDsf() = checkSample(embedded("lyrics.dsf")?.let { LrcParser.parse(it, "内嵌") })
    @Test fun embeddedWav() = checkSample(embedded("lyrics.wav")?.let { LrcParser.parse(it, "内嵌") })
}
