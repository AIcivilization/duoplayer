package com.zxdual.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.zxdual.player.lyrics.LyricMode
import com.zxdual.player.lyrics.LyricText
import com.zxdual.player.lyrics.Lyrics

/**
 * 全屏滚动歌词：当前句高亮并自动居中，点击任意一句跳转到该位置。
 */
class LyricsDialog(
    private val activity: Activity,
    val deck: Int,
    private val accent: Int,
    private val onSeek: (Long) -> Unit,
    private val onPickLrc: () -> Unit,
    private val onModeChange: (LyricMode) -> Unit,
) : Dialog(activity, android.R.style.Theme_Material_NoActionBar) {

    private val textColor = activity.getColor(R.color.text)
    private val muted = activity.getColor(R.color.muted)
    private var lyrics: Lyrics? = null
    private var mode = LyricMode.BOTH
    private var current = -1
    private var lastTouch = 0L

    private val title = TextView(activity).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val source = TextView(activity).apply {
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    private val modeBtn = smallButton("双语") {
        mode = LyricMode.values()[(mode.ordinal + 1) % LyricMode.values().size]
        onModeChange(mode)
        applyMode()
    }
    private val empty = TextView(activity).apply {
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(48), dp(24), dp(48))
        text = "这首没有找到歌词。\n\n· 用「＋文件夹」导入时，同目录下同名的 .lrc 会自动关联\n· 用「＋文件」时把同名 .lrc 一起选中\n· 或点上方「选择歌词」手动指定\n· 也会读取 FLAC / MP3 / DSF 里内嵌的歌词"
    }
    private val list = ListView(activity).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) lastTouch = SystemClock.uptimeMillis()
            false
        }
        setOnItemClickListener { _, _, pos, _ ->
            val l = lyrics ?: return@setOnItemClickListener
            if (l.synced) onSeek(l.lines[pos].timeMs)
        }
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = lyrics?.lines?.size ?: 0
        override fun getItem(p: Int) = lyrics!!.lines[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(activity).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(9), dp(20), dp(9))
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            val line = lyrics!!.lines[p]
            val texts = LyricText.pick(line, mode)
            tv.text = if (texts.isEmpty()) "♪" else texts.joinToString("\n")
            val active = p == current
            tv.setTextColor(if (active) accent else if (lyrics!!.synced) muted else textColor)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (active) 19f else 16f)
            tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            return tv
        }
    }

    init {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.bg))
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(6))
        }
        val badge = TextView(activity).apply {
            text = "AB"[deck].toString()
            setTextColor(activity.getColor(R.color.bg))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(accent) }
        }
        bar.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            addView(title)
            addView(source)
        }
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(modeBtn, LinearLayout.LayoutParams(dp(74), dp(36)).apply { marginEnd = dp(4) })
        bar.addView(smallButton("选择歌词") { onPickLrc() }, LinearLayout.LayoutParams(dp(78), dp(36)).apply { marginEnd = dp(4) })
        bar.addView(smallButton("关闭") { dismiss() }, LinearLayout.LayoutParams(dp(56), dp(36)))
        root.addView(bar)
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.adapter = adapter
        setContentView(root)
    }

    fun setMode(m: LyricMode) {
        if (m == mode) return
        mode = m
        applyMode()
    }

    private fun applyMode() {
        modeBtn.text = mode.label
        adapter.notifyDataSetChanged()
    }

    /** 每 100ms 由界面刷新调用 */
    fun update(trackTitle: String, l: Lyrics?, posMs: Long) {
        title.text = trackTitle
        if (l !== lyrics) {
            lyrics = l
            current = -1
            adapter.notifyDataSetChanged()
            list.setSelection(0)
            source.text = when {
                l == null -> "无歌词"
                !l.synced -> "${l.source} · 未同步歌词"
                l.isBilingual -> "${l.source} · 双语 · ${l.lines.size} 句"
                else -> "${l.source} · ${l.lines.size} 句"
            }
            modeBtn.text = mode.label
        }
        empty.visibility = if (l == null) View.VISIBLE else View.GONE
        list.visibility = if (l == null) View.GONE else View.VISIBLE
        if (l == null || !l.synced) return
        val idx = l.indexAt(posMs)
        if (idx != current) {
            current = idx
            adapter.notifyDataSetChanged()
            // 用户刚手动滚动过的 3 秒内不抢滚动位置
            if (idx >= 0 && SystemClock.uptimeMillis() - lastTouch > 3000) {
                list.smoothScrollToPositionFromTop(idx, list.height / 3, 300)
            }
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        background = activity.getDrawable(R.drawable.btn_bg)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
