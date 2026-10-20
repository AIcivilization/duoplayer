package com.zxdual.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.zxdual.player.lyrics.LyricMode
import com.zxdual.player.lyrics.LyricText
import com.zxdual.player.lyrics.Lyrics
import com.zxdual.player.model.Bookmark
import com.zxdual.player.model.FileRef
import com.zxdual.player.model.QueueMode
import com.zxdual.player.update.UpdateUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文字没变就不重设：TextView 重设文字会让跑马灯从头开始滚，首页每 250ms 刷新一次就会「抖」 */
private var TextView.txt: CharSequence
    get() = text
    set(v) { if (!android.text.TextUtils.equals(text, v)) text = v }

class MainActivity : Activity() {

    private var service: PlaybackService? = null
    private var last: EngineSnapshot? = null
    private var lastMessageId = -1
    private var seenLibVersion = -1
    private var lyricMode = LyricMode.BOTH
    private var lyricsDialog: LyricsDialog? = null
    private var selectedSlot = 0
    private var panelOpen = false
    private var segment = 0  // 0 = 书签列表, 1 = 文件列表
    private var bound = false
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val updater by lazy { UpdateUi(this, DIALOG_THEME) }

    private val colA by lazy { getColor(R.color.accent) }
    private val colB by lazy { getColor(R.color.accentB) }
    private val colText by lazy { getColor(R.color.text) }
    private val colMuted by lazy { getColor(R.color.muted) }
    private val colBtn by lazy { getColor(R.color.btn) }
    private val colBg by lazy { getColor(R.color.bg) }
    private val colGood by lazy { getColor(R.color.good) }
    private val colBad by lazy { getColor(R.color.bad) }
    private fun slotColor(i: Int) = if (i == 0) colA else colB

    private lateinit var pages: List<View>
    private lateinit var navs: List<Button>
    private lateinit var chips: List<ChipUi>

    // 播放页 / 组合卡片
    private lateinit var outputLine: TextView
    private lateinit var outputDetail: TextView
    private lateinit var warning: TextView
    private lateinit var btnMode: Button
    private lateinit var comboTitle: TextView
    private lateinit var comboSub: TextView
    private lateinit var tabs: List<Button>
    private lateinit var panel: View
    private lateinit var setFile: TextView
    private lateinit var setFormat: TextView
    private lateinit var setPath: TextView
    private lateinit var setLyrics: TextView
    private lateinit var setError: TextView
    private lateinit var gain: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var balance: SeekBar
    private lateinit var balanceLabel: TextView
    private lateinit var btnLoop: Button
    private lateinit var btnSlotOn: Button
    private lateinit var btnSave: Button
    private lateinit var btnPickLrc: Button
    private lateinit var btnPlay: Button
    private lateinit var queueLine: TextView
    private lateinit var btnRepeat: Button
    private lateinit var btnExitQueue: Button
    // 书签页 / 列表页
    private lateinit var bookmarkList: ListView
    private lateinit var bookmarkEmpty: TextView
    private lateinit var segs: List<Button>
    private lateinit var listHint: TextView
    private lateinit var btnQueueAdd: Button
    private lateinit var btnQueueFolder: Button
    private lateinit var queueList: ListView
    private lateinit var queueEmpty: TextView
    private var draggingGain = false
    private var draggingBalance = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as PlaybackService.LocalBinder).service
            service = s
            s.listener = { render(it) }
            s.snapshot?.let { lastMessageId = it.messageId; render(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // 100ms 刷新歌词与进度：在两次引擎快照（250ms）之间按时钟插值播放位置
    private val ticker = object : Runnable {
        override fun run() {
            chips.forEach { it.tick() }
            ui.postDelayed(this, 100)
        }
    }

    /**
     * 仅调试版：adb shell am start -n com.zxdual.player/.MainActivity --es testLocale de
     * 用于在不改系统语言的情况下检查各语言界面排版。正式版（不可调试）忽略此参数。
     */
    override fun attachBaseContext(base: android.content.Context) {
        val debuggable = base.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val tag = if (debuggable) base.getSharedPreferences("ui", MODE_PRIVATE).getString("testLocale", null) else null
        if (tag.isNullOrEmpty()) return super.attachBaseContext(base)
        val conf = android.content.res.Configuration(base.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(tag))
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent?.hasExtra("testLocale") == true) {
            val tag = intent.getStringExtra("testLocale").orEmpty()
            val cur = getSharedPreferences("ui", MODE_PRIVATE).getString("testLocale", "")
            if (tag != cur) {
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("testLocale", tag).commit()
                super.onCreate(savedInstanceState)
                recreate()  // 重建后 attachBaseContext 读到新语言
                return
            }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lyricMode = runCatching { LyricMode.valueOf(prefs.getString("lyricMode", "BOTH")!!) }.getOrDefault(LyricMode.BOTH)
        segment = prefs.getInt("segment", 0)
        bindViews()
        showPage(prefs.getInt("page", 0))
        updater.checkOnLaunch()
        startService(Intent(this, PlaybackService::class.java))
        bound = bindService(Intent(this, PlaybackService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.hasExtra("testLocale") == true) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("testLocale", intent.getStringExtra("testLocale")).commit()
            setIntent(intent)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::chips.isInitialized) ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        lyricsDialog?.dismiss()
        service?.listener = null
        if (bound) unbindService(conn)
        super.onDestroy()
    }

    // =====================================================================
    // 视图绑定
    // =====================================================================

    private fun bindViews() {
        pages = listOf(findViewById(R.id.pagePlay), findViewById(R.id.pageBookmarks), findViewById(R.id.pageLists))
        navs = listOf(findViewById(R.id.navPlay), findViewById(R.id.navBookmarks), findViewById(R.id.navLists))
        navs.forEachIndexed { i, b -> b.setOnClickListener { showPage(i) } }

        outputLine = findViewById(R.id.outputLine)
        outputDetail = findViewById(R.id.outputDetail)
        warning = findViewById(R.id.warning)
        btnMode = findViewById(R.id.btnMode)
        btnMode.setOnClickListener { service?.setStandardMode(!(last?.standardMode ?: false)) }

        // ---- 组合卡片 ----
        comboTitle = findViewById(R.id.comboTitle)
        comboSub = findViewById(R.id.comboSub)
        tabs = listOf(findViewById(R.id.tabA), findViewById(R.id.tabB))
        tabs.forEach { it.isSelected = true }  // 名字太长时跑马灯滚动
        tabs.forEachIndexed { i, b ->
            b.setOnClickListener {
                // 点 A 显示 A 的设置，点 B 显示 B 的设置；再点一次当前标签收起
                if (panelOpen && selectedSlot == i) panelOpen = false else { panelOpen = true; selectedSlot = i }
                last?.let { render(it) }
            }
        }
        panel = findViewById(R.id.settingsPanel)
        setFile = findViewById(R.id.setFile)
        setFormat = findViewById(R.id.setFormat)
        setPath = findViewById(R.id.setPath)
        setLyrics = findViewById(R.id.setLyrics)
        setError = findViewById(R.id.setError)
        gain = findViewById(R.id.gain)
        gainLabel = findViewById(R.id.gainLabel)
        balance = findViewById(R.id.balance)
        balanceLabel = findViewById(R.id.balanceLabel)
        btnLoop = findViewById(R.id.btnLoop)
        btnSlotOn = findViewById(R.id.btnSlotOn)
        btnSave = findViewById(R.id.btnSave)
        btnPickLrc = findViewById(R.id.btnPickLrc)
        findViewById<Button>(R.id.btnPickFile).setOnClickListener { chooseAudioForSlot(selectedSlot) }
        btnPickLrc.setOnClickListener { pickLrc(selectedSlot) }
        findViewById<Button>(R.id.btnClearSlot).setOnClickListener { service?.setSlotFile(selectedSlot, null) }
        btnLoop.setOnClickListener { last?.let { service?.setSlotLoop(selectedSlot, !it.slots[selectedSlot].loop) } }
        btnSlotOn.setOnClickListener { service?.toggleSlot(selectedSlot) }
        btnSave.setOnClickListener {
            // 保存 = 收起设置，标题显示「A + B」；收起后按钮变为「编辑」
            if (!panelOpen) selectedSlot = 0
            panelOpen = !panelOpen
            last?.let { render(it) }
        }
        findViewById<Button>(R.id.btnSaveBookmark).setOnClickListener { askSaveBookmark() }
        gain.setOnSeekBarChangeListener(seekListener(
            onChange = { p -> val db = progressToDb(p); gainLabel.text = gainText(db); service?.setSlotGain(selectedSlot, db) },
            onDrag = { draggingGain = it },
        ))
        gainLabel.setOnClickListener { service?.setSlotGain(selectedSlot, 0.0) }
        balance.setOnSeekBarChangeListener(seekListener(
            onChange = { p ->
                val b = if (kotlin.math.abs(p - 100) <= 3) 0.0 else (p - 100) / 100.0  // 居中吸附
                balanceLabel.text = balanceText(b)
                service?.setSlotBalance(selectedSlot, b)
            },
            onDrag = { draggingBalance = it },
        ))
        balanceLabel.setOnClickListener { service?.setSlotBalance(selectedSlot, 0.0) }

        // ---- 播放 ----
        chips = listOf(ChipUi(0, findViewById(R.id.chipA)), ChipUi(1, findViewById(R.id.chipB)))
        btnPlay = findViewById(R.id.btnPlay)
        btnPlay.setOnClickListener { service?.togglePlay() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { service?.prevItem() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { service?.nextItem() }
        queueLine = findViewById(R.id.queueLine)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnRepeat.setOnClickListener { service?.cycleRepeat() }
        btnExitQueue = findViewById(R.id.btnExitQueue)
        btnExitQueue.setOnClickListener { service?.exitQueue() }

        // ---- 书签页 ----
        bookmarkList = findViewById(R.id.bookmarkList)
        bookmarkEmpty = findViewById(R.id.bookmarkEmpty)
        bookmarkList.adapter = bookmarkAdapter
        bookmarkList.setOnItemClickListener { _, _, pos, _ ->
            val bm = last?.lib?.bookmarks?.getOrNull(pos) ?: return@setOnItemClickListener
            service?.loadBookmark(bm.id, play = true)
            showPage(0)
        }
        bookmarkList.setOnItemLongClickListener { _, _, pos, _ -> bookmarkMenu(pos); true }
        findViewById<Button>(R.id.btnAddBookmark).setOnClickListener { askSaveBookmark() }

        // ---- 列表页 ----
        segs = listOf(findViewById(R.id.segBookmarks), findViewById(R.id.segFiles))
        segs.forEachIndexed { i, b ->
            b.setOnClickListener {
                segment = i
                prefs.edit().putInt("segment", i).apply()
                seenLibVersion = -1
                last?.let { render(it) }
            }
        }
        listHint = findViewById(R.id.listHint)
        btnQueueAdd = findViewById(R.id.btnQueueAdd)
        btnQueueFolder = findViewById(R.id.btnQueueFolder)
        btnQueueAdd.setOnClickListener { if (segment == 0) addBookmarksToQueue() else startPicker(audioPicker(multiple = true), REQ_QUEUE_FILES) }
        btnQueueFolder.setOnClickListener { startPicker(treePicker(), REQ_QUEUE_FOLDER) }
        findViewById<Button>(R.id.btnQueueClear).setOnClickListener {
            confirm(getString(if (segment == 0) R.string.confirm_clear_bookmark_list else R.string.confirm_clear_file_list)) { if (segment == 0) service?.clearBookmarkQueue() else service?.clearFileQueue() }
        }
        findViewById<Button>(R.id.btnQueuePlay).setOnClickListener { playQueueFrom(0) }
        queueList = findViewById(R.id.queueList)
        queueEmpty = findViewById(R.id.queueEmpty)
        queueList.adapter = queueAdapter
        queueList.setOnItemClickListener { _, _, pos, _ -> playQueueFrom(pos) }
        queueList.setOnItemLongClickListener { _, _, pos, _ -> queueMenu(pos); true }
        findViewById<Button>(R.id.btnBackup).setOnClickListener { startBackup() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            startPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "application/octet-stream"))
            }, REQ_RESTORE)
        }
        findViewById<Button>(R.id.btnRelink).setOnClickListener {
            AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.relink_title)
                .setMessage(R.string.relink_message)
                .setPositiveButton(R.string.relink_pick) { _, _ -> startPicker(treePicker(), REQ_RELINK) }
                .setNegativeButton(R.string.cancel, null).show()
        }

        // ---- 更新 ----
        val versionText = findViewById<TextView>(R.id.versionText)
        val btnAuto = findViewById<Button>(R.id.btnAutoUpdate)
        versionText.text = getString(R.string.version_current, updater.currentVersion)
        fun bindAuto() { btnAuto.setText(if (updater.autoCheck) R.string.auto_update_on else R.string.auto_update_off) }
        bindAuto()
        btnAuto.setOnClickListener { updater.autoCheck = !updater.autoCheck; bindAuto() }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { updater.check(manual = true) }
        versionText.setOnClickListener { updater.openReleases() }
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        navs.forEachIndexed { k, b ->
            b.setTextColor(if (k == i) colA else colMuted)
            b.typeface = if (k == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        prefs.edit().putInt("page", i).apply()
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private fun render(s: EngineSnapshot) {
        last = s
        // ---- 输出 ----
        val rate = if (s.outputRate > 0) SlotController.khz(s.outputRate) else "—"
        outputLine.txt = getString(R.string.out_line, rate, Labels.outputMode(this, s.outputMode), getString(if (s.outputRunning) R.string.out_running else R.string.out_idle))
        outputDetail.txt = getString(R.string.out_detail, s.bufferMs, s.limiterHits, s.xruns)
        val warn = when {
            s.outputError.isNotEmpty() -> Labels.nativeError(this, s.outputError)
            s.outputRate in 1..48000 -> getString(R.string.out_warn_48k, SlotController.khz(s.outputRate))
            else -> ""
        }
        warning.txt = warn
        warning.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE
        btnMode.setText(if (s.standardMode) R.string.btn_output_standard else R.string.btn_output_power)

        // ---- 组合卡片 ----
        comboTitle.txt = s.comboName
        comboSub.txt = listOfNotNull(
            s.bookmarkName?.let { getString(R.string.combo_bookmark, it) + if (s.bookmarkDirty) getString(R.string.combo_modified) else "" } ?: getString(R.string.combo_unsaved),
            queueText(s),
        ).joinToString(" · ")
        tabs.forEachIndexed { i, b ->
            val sl = s.slots[i]
            b.txt = getString(R.string.tab_label, "AB"[i].toString(), if (sl.hasFile) sl.title else getString(R.string.not_selected))
            val sel = panelOpen && selectedSlot == i
            b.backgroundTintList = ColorStateList.valueOf(if (sel) slotColor(i) else colBtn)
            b.setTextColor(if (sel) colBg else if (sl.enabled) slotColor(i) else colMuted)
        }
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        btnSave.setText(if (panelOpen) R.string.btn_save else R.string.btn_edit)
        if (panelOpen) bindSettings(s.slots[selectedSlot])

        // ---- 播放 ----
        s.slots.forEachIndexed { i, sl -> chips[i].bind(sl) }
        btnPlay.txt = if (s.playing) "❚❚" else "▶"
        queueLine.txt = queueText(s) ?: getString(R.string.single_combo)
        btnRepeat.txt = Labels.repeat(this, s.repeat)
        btnExitQueue.visibility = if (s.queueMode == QueueMode.NONE) View.GONE else View.VISIBLE

        // ---- 书签 / 列表 ----
        if (s.libVersion != seenLibVersion) {
            seenLibVersion = s.libVersion
            bookmarkAdapter.notifyDataSetChanged()
            queueAdapter.notifyDataSetChanged()
        }
        bookmarkEmpty.visibility = if (s.lib.bookmarks.isEmpty()) View.VISIBLE else View.GONE
        segs.forEachIndexed { i, b ->
            b.backgroundTintList = ColorStateList.valueOf(if (segment == i) colA else colBtn)
            b.setTextColor(if (segment == i) colBg else colText)
        }
        btnQueueAdd.setText(if (segment == 0) R.string.btn_add_bookmark_q else R.string.btn_add_files)
        btnQueueFolder.visibility = if (segment == 1) View.VISIBLE else View.GONE
        listHint.setText(if (segment == 0) R.string.hint_bookmark_list else R.string.hint_file_list)
        val qEmpty = if (segment == 0) s.lib.bookmarkQueue.isEmpty() else s.lib.fileQueue.isEmpty()
        queueEmpty.visibility = if (qEmpty) View.VISIBLE else View.GONE
        queueEmpty.setText(if (segment == 0) R.string.empty_bookmark_list else R.string.empty_file_list)

        if (s.messageId != lastMessageId) {
            lastMessageId = s.messageId
            s.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun queueText(s: EngineSnapshot): String? = when (s.queueMode) {
        QueueMode.NONE -> null
        QueueMode.BOOKMARKS -> getString(R.string.queue_bookmarks_pos, s.queueIndex + 1, s.queueSize)
        QueueMode.FILES -> getString(R.string.queue_files_pos, s.queueIndex + 1, s.queueSize)
    }

    private fun bindSettings(sl: SlotSnapshot) {
        val c = slotColor(sl.index)
        setFile.txt = if (sl.hasFile) sl.fileName else getString(R.string.set_no_file)
        setFile.setTextColor(if (sl.hasFile) colText else colMuted)
        setFormat.txt = sl.formatLine
        setFormat.visibility = if (sl.formatLine.isEmpty()) View.GONE else View.VISIBLE
        setPath.txt = sl.pathLine
        setPath.setTextColor(if (sl.bitPerfect) colGood else colMuted)
        val ly = sl.lyrics
        setLyrics.txt = when {
            !sl.hasFile -> ""
            ly == null -> getString(R.string.lyrics_none_hint)
            else -> getString(R.string.lyrics_info, Labels.lyricSource(this, ly.source)) + (if (ly.isBilingual) getString(R.string.lyrics_bilingual_suffix) else "") + if (!ly.synced) getString(R.string.lyrics_unsynced_suffix) else ""
        }
        setError.txt = sl.error
        setError.visibility = if (sl.error.isEmpty()) View.GONE else View.VISIBLE
        val tint = ColorStateList.valueOf(c)
        listOf(gain, balance).forEach { it.progressTintList = tint; it.thumbTintList = tint }
        if (!draggingGain) { gain.progress = dbToProgress(sl.gainDb); gainLabel.txt = gainText(sl.gainDb) }
        if (!draggingBalance) { balance.progress = (sl.balance * 100 + 100).toInt(); balanceLabel.txt = balanceText(sl.balance) }
        btnLoop.setText(if (sl.loop) R.string.loop_on else R.string.loop_off)
        btnLoop.setTextColor(if (sl.loop) c else colText)
        btnSlotOn.setText(if (sl.enabled) R.string.slot_on else R.string.slot_off)
        btnSlotOn.setTextColor(if (sl.enabled) c else colMuted)
        btnPickLrc.isEnabled = sl.hasFile
    }

    // =====================================================================
    // A / B 播放条：亮 = 播放，灰 = 不播放
    // =====================================================================

    private inner class ChipUi(val index: Int, val root: View) {
        val bar: View = root.findViewById(R.id.chipBar)
        val badge: TextView = root.findViewById(R.id.chipBadge)
        val title: TextView = root.findViewById(R.id.chipTitle)
        val state: TextView = root.findViewById(R.id.chipState)
        val path: TextView = root.findViewById(R.id.chipPath)
        val lyricBox: View = root.findViewById(R.id.chipLyric)
        val lyric1: TextView = root.findViewById(R.id.lyric1)
        val lyric2: TextView = root.findViewById(R.id.lyric2)
        val lyric3: TextView = root.findViewById(R.id.lyric3)
        val seek: SeekBar = root.findViewById(R.id.chipSeek)
        val pos: TextView = root.findViewById(R.id.chipPos)
        val dur: TextView = root.findViewById(R.id.chipDur)
        val color = slotColor(index)
        val badgeBg = GradientDrawable().apply { cornerRadius = 6f * resources.displayMetrics.density }
        var snap: SlotSnapshot? = null
        var snapTime = 0L
        var dragging = false
        var lastLyricKey = ""
        var lastLyrics: Lyrics? = null
        var lastColor = 0

        init {
            badge.txt = "AB"[index].toString()
            badge.background = badgeBg
            lyric1.setTextColor(color)
            title.isSelected = true
            // 有文件：点一下切换亮/灰；还没选文件：点一下直接选音频
            root.setOnClickListener { if (snap?.hasFile == true) service?.toggleSlot(index) else chooseAudioForSlot(index) }
            lyricBox.setOnClickListener { showLyrics(index) }
            lyricBox.setOnLongClickListener {
                setLyricMode(LyricMode.values()[(lyricMode.ordinal + 1) % LyricMode.values().size])
                Toast.makeText(this@MainActivity, getString(R.string.toast_lyric_mode, Labels.lyricMode(this@MainActivity, lyricMode)), Toast.LENGTH_SHORT).show()
                true
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) pos.txt = fmt((snap?.durationMs ?: 0) * p / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val d = snap?.durationMs ?: return
                    service?.seek(index, d * sb.progress / 1000)
                }
            })
        }

        fun bind(sl: SlotSnapshot) {
            snap = sl
            snapTime = SystemClock.uptimeMillis()
            val on = sl.enabled && sl.hasFile
            val c = if (on) color else colMuted
            if (c != lastColor) {
                lastColor = c
                bar.setBackgroundColor(c)
                badgeBg.setColor(c)
                seek.progressTintList = ColorStateList.valueOf(c)
                seek.thumbTintList = ColorStateList.valueOf(c)
            }
            root.alpha = if (on) 1f else 0.45f
            seek.isEnabled = sl.hasFile
            title.txt = if (sl.hasFile) sl.title else getString(R.string.chip_no_file, "AB"[index].toString())
            state.txt = when {
                !sl.hasFile -> ""
                !sl.enabled -> getString(R.string.state_off)
                sl.error.isNotEmpty() -> getString(R.string.state_error)
                sl.playing -> getString(R.string.state_playing)
                sl.ended -> getString(R.string.state_ended)
                else -> getString(R.string.state_paused)
            }
            state.setTextColor(if (sl.error.isNotEmpty()) colBad else if (sl.playing) c else colMuted)
            path.txt = sl.error.ifEmpty { listOf(sl.formatLine, sl.pathLine).filter { it.isNotEmpty() }.joinToString(" · ") }
            path.setTextColor(if (sl.error.isNotEmpty()) colBad else if (sl.bitPerfect) colGood else colMuted)
            path.visibility = if (path.text.isEmpty()) View.GONE else View.VISIBLE
            dur.txt = fmt(sl.durationMs)
            tick()
        }

        private fun positionNow(): Long {
            val d = snap ?: return 0
            if (!d.playing || dragging) return d.positionMs
            val p = d.positionMs + (SystemClock.uptimeMillis() - snapTime)
            return if (d.durationMs > 0) p.coerceAtMost(d.durationMs) else p
        }

        fun tick() {
            val d = snap ?: return
            val p = positionNow()
            if (!dragging) {
                seek.progress = if (d.durationMs > 0) (p * 1000 / d.durationMs).toInt() else 0
                pos.txt = fmt(p)
            }
            val l = d.lyrics
            lyricsDialog?.takeIf { it.deck == index && it.isShowing }?.update(d.title, l, p)
            if (l !== lastLyrics) { lastLyrics = l; lastLyricKey = "" }
            if (!d.hasFile || l == null) { lyricBox.visibility = View.GONE; return }
            lyricBox.visibility = View.VISIBLE
            val idx = l.indexAt(p)
            val key = "$idx/${lyricMode.ordinal}"
            if (key == lastLyricKey) return
            lastLyricKey = key
            if (!l.synced) {
                show(l.lines.firstOrNull()?.let { LyricText.pick(it, lyricMode) }.orEmpty(), getString(R.string.lyrics_unsynced_tap))
                return
            }
            val cur = if (idx >= 0) LyricText.pick(l.lines[idx], lyricMode) else emptyList()
            val next = l.lines.getOrNull(idx + 1)?.let { LyricText.pick(it, lyricMode).firstOrNull() }.orEmpty()
            show(cur.ifEmpty { listOf("♪") }, next)
        }

        private fun show(cur: List<String>, next: String) {
            lyric1.txt = cur.getOrElse(0) { "" }
            val second = cur.getOrNull(1)
            lyric2.visibility = if (second == null) View.GONE else View.VISIBLE
            lyric2.txt = second ?: ""
            lyric3.txt = next
            lyric3.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // =====================================================================
    // 书签
    // =====================================================================

    private val bookmarkAdapter = object : BaseAdapter() {
        override fun getCount() = last?.lib?.bookmarks?.size ?: 0
        override fun getItem(p: Int) = last!!.lib.bookmarks[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val bm = getItem(p)
            val current = bm.id == last?.lib?.currentBookmarkId
            return twoLine(convert, bm.name, comboFiles(bm), current, "${p + 1}")
        }
    }

    private fun comboFiles(bm: Bookmark): String {
        fun n(f: FileRef?, on: Boolean) = f?.name?.substringBeforeLast('.')?.let { if (on) it else getString(R.string.bm_slot_off, it) }
        return listOfNotNull(
            n(bm.combo.a.file, bm.combo.a.enabled)?.let { getString(R.string.bm_slot_a, it) },
            n(bm.combo.b.file, bm.combo.b.enabled)?.let { getString(R.string.bm_slot_b, it) },
        ).joinToString("   ").ifEmpty { getString(R.string.bm_empty) }
    }

    private fun askSaveBookmark() {
        val s = last ?: return
        if (s.slots.none { it.hasFile }) {
            Toast.makeText(this, R.string.need_audio_first, Toast.LENGTH_SHORT).show()
            return
        }
        askText(getString(R.string.save_bookmark_title), getString(R.string.bookmark_name_hint), s.comboName) { name -> service?.saveBookmark(name) }
    }

    private fun bookmarkMenu(pos: Int) {
        val bm = last?.lib?.bookmarks?.getOrNull(pos) ?: return
        val items = resources.getStringArray(R.array.bookmark_menu)
        AlertDialog.Builder(this, DIALOG_THEME).setTitle(bm.name).setItems(items) { _, w ->
            when (w) {
                0 -> { service?.loadBookmark(bm.id, play = true); showPage(0) }
                1 -> { service?.loadBookmark(bm.id, play = false); showPage(0) }
                2 -> service?.addToBookmarkQueue(listOf(bm.id))
                3 -> askText(getString(R.string.rename_title), getString(R.string.name_hint), bm.name) { service?.renameBookmark(bm.id, it) }
                4 -> confirm(getString(R.string.confirm_overwrite, bm.name)) { service?.overwriteBookmark(bm.id) }
                5 -> service?.moveBookmark(pos, -1)
                6 -> service?.moveBookmark(pos, 1)
                7 -> confirm(getString(R.string.confirm_delete_bm, bm.name)) { service?.deleteBookmark(bm.id) }
            }
        }.show()
    }

    // =====================================================================
    // 连续播放列表
    // =====================================================================

    private val queueAdapter = object : BaseAdapter() {
        override fun getCount(): Int {
            val l = last?.lib ?: return 0
            return if (segment == 0) l.bookmarkQueue.size else l.fileQueue.size
        }
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val s = last!!
            val mode = if (segment == 0) QueueMode.BOOKMARKS else QueueMode.FILES
            val current = s.queueMode == mode && s.queueIndex == p
            val num = if (current) "▶" else "${p + 1}"
            return if (segment == 0) {
                val bm = s.lib.bookmarks.find { it.id == s.lib.bookmarkQueue[p] }
                if (bm == null) twoLine(convert, getString(R.string.bm_deleted), "", false, num)
                else twoLine(convert, bm.name, comboFiles(bm), current, num)
            } else {
                val f = s.lib.fileQueue[p]
                twoLine(convert, f.name.substringBeforeLast('.'), (f.relPath ?: f.name) + if (f.lrc != null) getString(R.string.has_lyrics_suffix) else "", current, num)
            }
        }
    }

    private fun playQueueFrom(pos: Int) {
        val l = last?.lib ?: return
        if (segment == 0) {
            if (l.bookmarkQueue.isEmpty()) return
            service?.playBookmarkQueue(pos)
        } else {
            if (l.fileQueue.isEmpty()) return
            service?.playFileQueue(pos)
        }
        showPage(0)
    }

    private fun addBookmarksToQueue() {
        val bms = last?.lib?.bookmarks ?: return
        if (bms.isEmpty()) {
            Toast.makeText(this, R.string.no_bookmarks_yet, Toast.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(bms.size)
        AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.pick_bookmarks_title)
            .setMultiChoiceItems(bms.map { it.name }.toTypedArray(), checked) { _, w, c -> checked[w] = c }
            .setPositiveButton(R.string.add) { _, _ -> service?.addToBookmarkQueue(bms.filterIndexed { i, _ -> checked[i] }.map { it.id }) }
            .setNeutralButton(R.string.add_all) { _, _ -> service?.addToBookmarkQueue(bms.map { it.id }) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun queueMenu(pos: Int) {
        val l = last?.lib ?: return
        val files = segment == 1
        val items = resources.getStringArray(if (files) R.array.queue_menu_files else R.array.queue_menu_bookmarks)
        AlertDialog.Builder(this, DIALOG_THEME).setItems(items) { _, w ->
            val action = if (files) w else listOf(0, 3, 4, 5)[w]
            when (action) {
                0 -> playQueueFrom(pos)
                1 -> { service?.setSlotFile(0, l.fileQueue[pos]); showPage(0) }
                2 -> { service?.setSlotFile(1, l.fileQueue[pos]); showPage(0) }
                3 -> if (files) service?.moveInFileQueue(pos, -1) else service?.moveInBookmarkQueue(pos, -1)
                4 -> if (files) service?.moveInFileQueue(pos, 1) else service?.moveInBookmarkQueue(pos, 1)
                5 -> if (files) service?.removeFromFileQueue(pos) else service?.removeFromBookmarkQueue(pos)
            }
        }.show()
    }

    // =====================================================================
    // 选择文件 / 备份
    // =====================================================================

    private fun chooseAudioForSlot(slot: Int) {
        val files = last?.lib?.fileQueue.orEmpty()
        if (files.isEmpty()) {
            startPicker(audioPicker(multiple = true), slot * 10 + REQ_SLOT_AUDIO)
            return
        }
        AlertDialog.Builder(this, DIALOG_THEME).setTitle(getString(R.string.choose_audio_for, "AB"[slot].toString()))
            .setItems(arrayOf(getString(R.string.choose_from_device), getString(R.string.choose_from_list, files.size))) { _, w ->
                if (w == 0) startPicker(audioPicker(multiple = true), slot * 10 + REQ_SLOT_AUDIO)
                else AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.segment_files)
                    .setItems(files.map { it.name }.toTypedArray()) { _, k -> service?.setSlotFile(slot, files[k]) }
                    .show()
            }.show()
    }

    private fun audioPicker(multiple: Boolean) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/octet-stream", "text/*"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun treePicker() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun pickLrc(slot: Int) {
        startPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, slot * 10 + REQ_LRC)
    }

    private fun startPicker(i: Intent, code: Int) {
        try {
            startActivityForResult(i, code)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.picker_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun startBackup() {
        val name = getString(R.string.backup_file_prefix) + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        startPicker(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQ_BACKUP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_QUEUE_FILES -> {
                val audio = pairLyrics(pickedRefs(data))
                if (audio.isNotEmpty()) service?.addToFileQueue(audio.sortedBy { it.name.lowercase() })
            }
            REQ_QUEUE_FOLDER -> {
                val tree = data.data ?: return
                persist(tree)
                Toast.makeText(this, R.string.scanning_folder, Toast.LENGTH_SHORT).show()
                Thread {
                    val files = scanTree(tree).first
                    runOnUiThread {
                        if (files.isEmpty()) Toast.makeText(this, R.string.folder_no_audio, Toast.LENGTH_SHORT).show()
                        else service?.addToFileQueue(files)
                    }
                }.start()
            }
            REQ_RELINK -> {
                val tree = data.data ?: return
                persist(tree)
                Toast.makeText(this, R.string.scanning_relink, Toast.LENGTH_SHORT).show()
                Thread {
                    val entries = scanTree(tree).second
                    runOnUiThread { service?.relink(entries) }
                }.start()
            }
            REQ_BACKUP -> {
                val uri = data.data ?: return
                service?.exportJson { text ->
                    val ok = runCatching {
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    }.isSuccess
                    Toast.makeText(this, if (ok) R.string.backup_done else R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            }
            REQ_RESTORE -> {
                val uri = data.data ?: return
                val text = runCatching { contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }.getOrNull()
                if (text == null) {
                    Toast.makeText(this, R.string.restore_read_failed, Toast.LENGTH_LONG).show()
                    return
                }
                AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.restore_title)
                    .setMessage(R.string.restore_message)
                    .setPositiveButton(R.string.restore_replace) { _, _ -> service?.importJson(text, merge = false) }
                    .setNeutralButton(R.string.restore_merge) { _, _ -> service?.importJson(text, merge = true) }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            else -> {
                val slot = requestCode / 10
                when (requestCode % 10) {
                    REQ_SLOT_AUDIO -> pairLyrics(pickedRefs(data)).firstOrNull()?.let { service?.setSlotFile(slot, it) }
                    REQ_LRC -> data.data?.let { u -> persist(u); service?.attachLrc(slot, refOf(u, displayName(u))) }
                }
            }
        }
    }

    private fun pickedRefs(data: Intent): List<FileRef> {
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        data.data?.let { if (uris.isEmpty()) uris.add(it) }
        return uris.map { u -> persist(u); refOf(u, displayName(u)) }
    }

    /** 把选中的 .lrc 按文件名配给同名音频；只选了一首歌和一个 .lrc 时直接配对 */
    private fun pairLyrics(refs: List<FileRef>): List<FileRef> {
        val (lrcs, audio) = refs.partition { it.name.lowercase().endsWith(".lrc") }
        val byBase = lrcs.associateBy { baseName(it.name) }
        return audio.map { a -> a.copy(lrc = byBase[baseName(a.name)] ?: if (audio.size == 1 && lrcs.size == 1) lrcs[0] else null) }
    }

    private fun refOf(u: Uri, name: String): FileRef {
        val doc = runCatching { if (DocumentsContract.isDocumentUri(this, u)) DocumentsContract.getDocumentId(u) else null }.getOrNull()
        return FileRef(u.toString(), name, doc)
    }

    private fun persist(u: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun displayName(u: Uri): String {
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: u.toString()
    }

    /** 递归扫描文件夹：返回 (音频列表[已配好同名歌词], 全部条目 (docId, uri, name)) */
    private fun scanTree(tree: Uri): Pair<List<FileRef>, List<Triple<String, String, String>>> {
        val out = mutableListOf<FileRef>()
        val all = mutableListOf<Triple<String, String, String>>()
        fun walk(docId: String, depth: Int) {
            if (depth > 16) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = mutableListOf<String>()
            val files = mutableListOf<FileRef>()
            val lrcs = HashMap<String, FileRef>()
            runCatching {
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id); continue }
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in AUDIO_EXT && ext != "lrc") continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        all.add(Triple(id, uri.toString(), name))
                        val ref = FileRef(uri.toString(), name, id)
                        if (ext == "lrc") lrcs[baseName(name)] = ref else files.add(ref)
                    }
                }
            }
            out.addAll(files.map { it.copy(lrc = lrcs[baseName(it.name)]) }.sortedBy { it.name.lowercase() })
            dirs.sorted().forEach { walk(it, depth + 1) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out to all
    }

    // =====================================================================
    // 歌词
    // =====================================================================

    private fun showLyrics(slot: Int) {
        lyricsDialog?.dismiss()
        lyricsDialog = LyricsDialog(
            this, slot, slotColor(slot),
            onSeek = { ms -> service?.seek(slot, ms) },
            onPickLrc = { pickLrc(slot) },
            onModeChange = { m -> setLyricMode(m) },
        ).apply {
            setMode(lyricMode)
            setOnDismissListener { if (lyricsDialog === this) lyricsDialog = null }
            show()
        }
        chips[slot].tick()
    }

    private fun setLyricMode(m: LyricMode) {
        lyricMode = m
        prefs.edit().putString("lyricMode", m.name).apply()
        lyricsDialog?.setMode(m)
        chips.forEach { it.tick() }
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private fun twoLine(convert: View?, title: String, sub: String, highlight: Boolean, num: String): View {
        val row = (convert as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), dp(10), dp(10))
            addView(TextView(this@MainActivity).apply {
                gravity = android.view.Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(colMuted)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val n = row.getChildAt(0) as TextView
        val col = row.getChildAt(1) as LinearLayout
        n.text = num
        n.setTextColor(if (highlight) colA else colMuted)
        (col.getChildAt(0) as TextView).apply { text = title; setTextColor(if (highlight) colA else colText) }
        (col.getChildAt(1) as TextView).apply { text = sub; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
        return row
    }

    private fun askText(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, DIALOG_THEME).setTitle(title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(et.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirm(msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, DIALOG_THEME).setMessage(msg)
            .setPositiveButton(R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun seekListener(onChange: (Int) -> Unit, onDrag: (Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar) = onDrag(true)
        override fun onStopTrackingTouch(sb: SeekBar) = onDrag(false)
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun gainText(db: Double) =
        if (db <= -60.0) getString(R.string.gain_mute) else getString(R.string.gain_db, "%+.1f".format(db).replace("+0.0", "0.0"))

    private fun balanceText(b: Double) = when {
        b == 0.0 -> getString(R.string.balance_center)
        b < 0 -> getString(R.string.balance_left, (-b * 100).toInt())
        else -> getString(R.string.balance_right, (b * 100).toInt())
    }

    companion object {
        const val REQ_SLOT_AUDIO = 1
        const val REQ_LRC = 3
        const val REQ_QUEUE_FILES = 51
        const val REQ_QUEUE_FOLDER = 52
        const val REQ_BACKUP = 60
        const val REQ_RESTORE = 61
        const val REQ_RELINK = 62
        val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert
        val AUDIO_EXT = setOf("flac", "wav", "wave", "aif", "aiff", "aifc", "dsf", "mp3", "m4a", "aac", "ogg", "oga", "opus", "w64", "rf64")

        fun baseName(n: String) = n.substringBeforeLast('.').lowercase()

        // 进度条 0..132 ↔ -60..+6 dB，步进 0.5 dB；0 dB 在 120
        fun progressToDb(p: Int) = -60.0 + p * 0.5
        fun dbToProgress(db: Double) = ((db + 60.0) / 0.5).toInt().coerceIn(0, 132)
        fun fmt(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
