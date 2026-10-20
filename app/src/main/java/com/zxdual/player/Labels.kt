package com.zxdual.player

import android.content.Context
import com.zxdual.player.lyrics.LyricMode
import com.zxdual.player.model.Repeat

/** 枚举、错误码 → 当前系统语言的界面文字 */
object Labels {
    fun repeat(c: Context, r: Repeat) = c.getString(
        when (r) {
            Repeat.OFF -> R.string.repeat_off
            Repeat.ALL -> R.string.repeat_all
            Repeat.ONE -> R.string.repeat_one
        },
    )

    fun lyricMode(c: Context, m: LyricMode) = c.getString(
        when (m) {
            LyricMode.BOTH -> R.string.lyric_mode_both
            LyricMode.ZH -> R.string.lyric_mode_cjk
            LyricMode.EN -> R.string.lyric_mode_latin
        },
    )

    fun lyricSource(c: Context, src: String) = when (src) {
        "lrc" -> c.getString(R.string.lyrics_src_lrc)
        "embedded" -> c.getString(R.string.lyrics_src_embedded)
        else -> src
    }

    fun outputMode(c: Context, code: String) =
        c.getString(if (code == "standard") R.string.out_mode_standard else R.string.out_mode_power)

    /** C++ 引擎返回的「错误码[:详情]」 */
    fun nativeError(c: Context, raw: String): String {
        if (raw.isEmpty()) return ""
        val code = raw.substringBefore(':')
        val arg = raw.substringAfter(':', "")
        return when (code) {
            "io" -> c.getString(R.string.err_io)
            "corrupt" -> c.getString(R.string.err_corrupt, arg)
            "unsupported" -> c.getString(R.string.err_unsupported, arg)
            "unknown_format" -> c.getString(R.string.err_unknown_format)
            "no_audio_track" -> c.getString(R.string.err_no_audio_track)
            "no_decoder" -> c.getString(R.string.err_no_decoder, arg)
            "bad_format" -> c.getString(R.string.err_bad_format)
            "bad_rate" -> c.getString(R.string.err_bad_rate)
            "rate_ratio" -> c.getString(R.string.err_rate_ratio, arg)
            "aaudio_unavailable" -> c.getString(R.string.err_aaudio)
            "output_open" -> c.getString(R.string.err_output_open, arg)
            "output_start" -> c.getString(R.string.err_output_start, arg)
            else -> c.getString(R.string.err_unknown, raw)
        }
    }
}
