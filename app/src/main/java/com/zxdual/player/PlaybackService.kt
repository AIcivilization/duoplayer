package com.zxdual.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.zxdual.player.audio.NativeEngine
import com.zxdual.player.model.Bookmark
import com.zxdual.player.model.Combo
import com.zxdual.player.model.FileRef
import com.zxdual.player.model.Library
import com.zxdual.player.model.QueueMode
import com.zxdual.player.model.Relinker
import com.zxdual.player.model.Repeat
import com.zxdual.player.model.SlotConfig
import org.json.JSONObject

data class EngineSnapshot(
    val outputRate: Int,
    val outputRunning: Boolean,
    val outputMode: String,
    val bufferMs: Int,
    val limiterHits: Int,
    val xruns: Int,
    val outputError: String,
    val standardMode: Boolean,
    val playing: Boolean,
    val slots: List<SlotSnapshot>,
    val comboName: String,
    val bookmarkName: String?,
    val bookmarkDirty: Boolean,
    val queueMode: QueueMode,
    val queueIndex: Int,
    val queueSize: Int,
    val repeat: Repeat,
    val lib: Library,
    val libVersion: Int,
    val message: String?,
    val messageId: Int,
)

/**
 * 前台播放服务：持有 C++ 引擎和 A/B 两路，管理组合、书签、连续播放列表与备份。
 * 所有状态只在 engine 线程修改，UI 线程从不阻塞。
 */
class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val engineThread = HandlerThread("zx-engine").apply { start() }
    private val engineHandler = Handler(engineThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var engine: NativeEngine
    private lateinit var slots: List<SlotController>
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var focusRequest: AudioFocusRequest? = null

    @Volatile var snapshot: EngineSnapshot? = null
        private set
    var listener: ((EngineSnapshot) -> Unit)? = null

    // ---- engine 线程状态 ----
    private var lib = Library()
    private var libVersion = 0
    private var playing = false
    private var resumeOnFocusGain = false
    private var message: String? = null
    private var messageId = 0
    private var isForeground = false
    private var lastPlayingPublished: Boolean? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 拔耳机：立即暂停
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) post { pause() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZXDual:playback").apply { setReferenceCounted(false) }
        createChannel()
        setupSession()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        post {
            engine = NativeEngine()
            slots = listOf(SlotController(this, engine, 0), SlotController(this, engine, 1))
            restoreState()
            engine.setOutputMode(lib.standardOutput)
            tickLoop()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> post { togglePlayInternal() }
            ACTION_NEXT -> post { next(auto = false) }
            ACTION_PREV -> post { prev() }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (snapshot?.playing != true) stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        session.release()
        if (wakeLock.isHeld) wakeLock.release()
        engineHandler.removeCallbacksAndMessages(null)
        engineHandler.post {
            saveState()
            engine.release()
            engineThread.quitSafely()
        }
        super.onDestroy()
    }

    fun post(block: () -> Unit) {
        engineHandler.post(block)
    }

    // =====================================================================
    // 对外接口（UI 调用，全部转发到 engine 线程）
    // =====================================================================

    fun togglePlay() = post { togglePlayInternal() }
    fun toggleSlot(i: Int) = post { setSlotEnabled(i, !lib.current.slot(i).enabled) }
    fun setSlotFile(i: Int, f: FileRef?) = post { updateSlot(i) { it.copy(file = f, enabled = it.enabled || f != null) } }
    fun setSlotGain(i: Int, db: Double) = post { updateSlot(i, save = false) { it.copy(gainDb = db) }; saveStateLater() }
    fun setSlotBalance(i: Int, b: Double) = post { updateSlot(i, save = false) { it.copy(balance = b) }; saveStateLater() }
    fun setSlotLoop(i: Int, on: Boolean) = post { updateSlot(i) { it.copy(loop = on) } }
    fun seek(i: Int, ms: Long) = post { slots[i].seek(ms); publish() }

    fun attachLrc(i: Int, lrc: FileRef) = post {
        val f = lib.current.slot(i).file ?: return@post
        val withLrc = f.copy(lrc = lrc)
        // 同一个文件在书签和文件列表里的引用也一并更新
        lib = lib.remap { if (it.uri == f.uri) withLrc else null }
        applyCurrent()
        changed()
    }

    fun saveBookmark(name: String) = post {
        val bm = Bookmark(Bookmark.newId(), name.ifBlank { comboName() }, lib.current)
        lib = lib.copy(bookmarks = lib.bookmarks + bm, currentBookmarkId = bm.id)
        toast("已保存书签「${bm.name}」")
        changed()
    }

    fun overwriteBookmark(id: String) = post {
        lib = lib.copy(bookmarks = lib.bookmarks.map { if (it.id == id) it.copy(combo = lib.current) else it }, currentBookmarkId = id)
        toast("已用当前组合覆盖书签")
        changed()
    }

    fun renameBookmark(id: String, name: String) = post {
        lib = lib.copy(bookmarks = lib.bookmarks.map { if (it.id == id) it.copy(name = name) else it })
        changed()
    }

    fun deleteBookmark(id: String) = post {
        lib = lib.copy(
            bookmarks = lib.bookmarks.filter { it.id != id },
            bookmarkQueue = lib.bookmarkQueue.filter { it != id },
            currentBookmarkId = lib.currentBookmarkId.takeIf { it != id },
        )
        changed()
    }

    fun moveBookmark(pos: Int, delta: Int) = post { lib = lib.copy(bookmarks = lib.bookmarks.moved(pos, delta)); changed() }

    /** 点书签：载入组合；play=true 时立即播放 */
    fun loadBookmark(id: String, play: Boolean) = post {
        val bm = lib.bookmarks.find { it.id == id } ?: return@post
        lib = lib.copy(queueMode = QueueMode.NONE, queueIndex = -1)
        loadCombo(bm.combo, bm.id)
        if (play) startPlay() else pause()
        changed()
    }

    // ---- 书签列表 ----
    fun addToBookmarkQueue(ids: List<String>) = post { lib = lib.copy(bookmarkQueue = lib.bookmarkQueue + ids); changed() }
    fun removeFromBookmarkQueue(pos: Int) = post { lib = lib.copy(bookmarkQueue = lib.bookmarkQueue.removedAt(pos)).fixIndex(QueueMode.BOOKMARKS, pos); changed() }
    fun moveInBookmarkQueue(pos: Int, delta: Int) = post { lib = lib.copy(bookmarkQueue = lib.bookmarkQueue.moved(pos, delta)).followMove(QueueMode.BOOKMARKS, pos, delta); changed() }
    fun clearBookmarkQueue() = post { lib = lib.copy(bookmarkQueue = emptyList()).leaveQueue(QueueMode.BOOKMARKS); changed() }
    fun playBookmarkQueue(pos: Int) = post { playQueueItem(QueueMode.BOOKMARKS, pos) }

    // ---- 文件列表 ----
    fun addToFileQueue(files: List<FileRef>) = post { lib = lib.copy(fileQueue = lib.fileQueue + files); toast("已加入 ${files.size} 个文件"); changed() }
    fun removeFromFileQueue(pos: Int) = post { lib = lib.copy(fileQueue = lib.fileQueue.removedAt(pos)).fixIndex(QueueMode.FILES, pos); changed() }
    fun moveInFileQueue(pos: Int, delta: Int) = post { lib = lib.copy(fileQueue = lib.fileQueue.moved(pos, delta)).followMove(QueueMode.FILES, pos, delta); changed() }
    fun clearFileQueue() = post { lib = lib.copy(fileQueue = emptyList()).leaveQueue(QueueMode.FILES); changed() }
    fun playFileQueue(pos: Int) = post { playQueueItem(QueueMode.FILES, pos) }

    fun nextItem() = post { next(auto = false) }
    fun prevItem() = post { prev() }
    fun cycleRepeat() = post { lib = lib.copy(repeat = Repeat.values()[(lib.repeat.ordinal + 1) % Repeat.values().size]); changed() }
    fun exitQueue() = post { lib = lib.copy(queueMode = QueueMode.NONE, queueIndex = -1); slots[0].forceNoLoop = false; changed() }
    fun setStandardMode(on: Boolean) = post { lib = lib.copy(standardOutput = on); engine.setOutputMode(on); changed() }

    // ---- 备份 ----
    fun exportJson(cb: (String) -> Unit) = post {
        val o = lib.toJson().put("exportedAt", System.currentTimeMillis())
        val s = o.toString(2)
        mainHandler.post { cb(s) }
    }

    fun importJson(text: String, merge: Boolean) = post {
        val o = try { JSONObject(text) } catch (e: Exception) { null }
        if (o == null || !Library.isBackup(o)) {
            toast("不是有效的备份文件")
            publish()
            return@post
        }
        val imported = Library.fromJson(o)
        pause()
        lib = if (merge) lib.merge(imported) else imported.copy(standardOutput = lib.standardOutput)
        if (!merge) applyCurrent()
        val missing = lib.allFiles().distinctBy { it.uri }.count { !canOpen(it.uri) }
        toast(
            (if (merge) "已合并：共 ${lib.bookmarks.size} 个书签" else "已恢复：${lib.bookmarks.size} 个书签、${lib.fileQueue.size} 个文件") +
                if (missing > 0) "\n有 $missing 个文件无法访问，请用「重新关联文件夹」找回" else "",
        )
        changed()
    }

    /** entries: (docId, uri, name)，来自用户选中的根文件夹 */
    fun relink(entries: List<Triple<String, String, String>>) = post {
        val r = Relinker(entries)
        var fixed = 0
        val broken = lib.allFiles().distinctBy { it.uri }.filter { !canOpen(it.uri) }
        val map = HashMap<String, FileRef>()
        for (f in broken) r.find(f)?.let { map[f.uri] = it; fixed++ }
        lib = lib.remap { map[it.uri]?.let { n -> n.copy(lrc = it.lrc) } }
        applyCurrent()
        val left = broken.size - fixed
        toast(if (broken.isEmpty()) "授权已恢复，所有文件都能正常访问" else "已找回 $fixed 个文件" + if (left > 0) "，仍有 $left 个找不到" else "")
        changed()
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private fun comboName(): String = Combo.displayName(
        slots[0].displayName.takeIf { lib.current.a.file != null },
        slots[1].displayName.takeIf { lib.current.b.file != null },
    )

    private fun updateSlot(i: Int, save: Boolean = true, f: (SlotConfig) -> SlotConfig) {
        val before = lib.current.slot(i)
        val after = f(before)
        lib = lib.copy(current = lib.current.with(i, after))
        slots[i].apply(after)
        if (playing && after.enabled && before.file?.uri != after.file?.uri) slots[i].play()
        if (save) changed() else publish()
    }

    private fun setSlotEnabled(i: Int, on: Boolean) {
        updateSlot(i) { it.copy(enabled = on) }
        if (on && playing) {
            if (slots[i].state().ended) slots[i].seek(0)
            slots[i].play()
        }
        if (!on && playing && slots.none { it.config.enabled && it.loaded }) playing = false
        publish()
    }

    private fun applyCurrent() {
        slots[0].forceNoLoop = lib.queueMode == QueueMode.FILES
        slots.forEachIndexed { i, s -> s.apply(lib.current.slot(i)) }
    }

    private fun loadCombo(c: Combo, bookmarkId: String?) {
        lib = lib.copy(current = c, currentBookmarkId = bookmarkId)
        applyCurrent()
        slots.forEach { if (it.loaded) it.seek(0) }
    }

    private fun togglePlayInternal() = if (playing) pause() else startPlay()

    private fun startPlay() {
        val playable = slots.filter { it.config.enabled && it.loaded }
        if (playable.isEmpty()) {
            toast(if (slots.any { it.config.file != null }) "A、B 都已关闭：点亮至少一路再播放" else "请先在 A / B 中选择音频文件")
            playing = false
            publish()
            return
        }
        if (!requestFocus()) return
        // 整个组合都已放完 → 从头开始
        val allEnded = playable.all { it.state().ended }
        playable.forEach { s ->
            if (allEnded || s.state().ended) s.seek(0)
            s.play()
        }
        playing = true
        publish()
    }

    private fun pause() {
        playing = false
        slots.forEach { it.pause() }
        publish()
    }

    private fun playQueueItem(mode: QueueMode, pos: Int) {
        val size = if (mode == QueueMode.BOOKMARKS) lib.bookmarkQueue.size else lib.fileQueue.size
        if (pos !in 0 until size) return
        lib = lib.copy(queueMode = mode, queueIndex = pos)
        when (mode) {
            QueueMode.BOOKMARKS -> {
                val bm = lib.bookmarks.find { it.id == lib.bookmarkQueue[pos] }
                if (bm == null) { next(auto = true); return }
                loadCombo(bm.combo, bm.id)
            }
            QueueMode.FILES -> {
                val f = lib.fileQueue[pos]
                // 文件列表：列表里的文件依次放进 A；B 保持当前设置（可以关掉 B 只听 A）
                loadCombo(lib.current.with(0, lib.current.a.copy(file = f, enabled = true)), null)
            }
            QueueMode.NONE -> return
        }
        startPlay()
        changed()
    }

    private fun queueSize() = when (lib.queueMode) {
        QueueMode.BOOKMARKS -> lib.bookmarkQueue.size
        QueueMode.FILES -> lib.fileQueue.size
        QueueMode.NONE -> 0
    }

    private fun next(auto: Boolean) {
        val n = queueSize()
        if (lib.queueMode == QueueMode.NONE || n == 0) {
            if (!auto) toast("当前不在列表播放中")
            publish()
            return
        }
        var i = lib.queueIndex + 1
        if (i >= n) {
            if (auto && lib.repeat == Repeat.OFF) {
                pause()
                toast("列表已播放完毕")
                return
            }
            i = 0
        }
        playQueueItem(lib.queueMode, i)
    }

    private fun prev() {
        val n = queueSize()
        val pos = slots.filter { it.loaded }.maxOfOrNull { it.state().positionMs } ?: 0
        if (lib.queueMode == QueueMode.NONE || n == 0 || pos > 3000) {
            slots.forEach { if (it.loaded) it.seek(0) }
            publish()
            return
        }
        playQueueItem(lib.queueMode, if (lib.queueIndex - 1 < 0) n - 1 else lib.queueIndex - 1)
    }

    /** 组合播放结束：所有开启且不循环的那几路都放完了 */
    private fun onComboFinished() {
        slots.forEach { it.pause() }
        when {
            lib.repeat == Repeat.ONE || (lib.queueMode == QueueMode.NONE && lib.repeat == Repeat.ALL) -> {
                slots.forEach { if (it.loaded) it.seek(0) }
                playing = false
                startPlay()
            }
            lib.queueMode == QueueMode.NONE -> { playing = false; publish() }
            else -> next(auto = true)
        }
    }

    private fun tickLoop() {
        engine.tick()
        if (playing) {
            val active = slots.filter { it.config.enabled && it.loaded }
            if (active.isEmpty()) {
                playing = false
            } else {
                val finite = active.filter { !it.effectiveLoop() }
                if (finite.isNotEmpty() && finite.all { it.state().ended }) onComboFinished()
            }
        }
        publish()
        engineHandler.postDelayed({ tickLoop() }, 250)
    }

    private fun toast(s: String) {
        message = s
        messageId++
    }

    private fun changed() {
        libVersion++
        saveState()
        publish()
    }

    private fun publish() {
        val out = engine.outputState()
        val states = slots.map { it.state() }
        val snaps = slots.mapIndexed { i, s -> s.snapshot(states[i], out.sampleRate, states[1 - i].playing) }
        val bm = lib.currentBookmarkId?.let { id -> lib.bookmarks.find { it.id == id } }
        val snap = EngineSnapshot(
            outputRate = out.sampleRate,
            outputRunning = out.running,
            outputMode = out.mode,
            bufferMs = if (out.sampleRate > 0) out.bufferFrames * 1000 / out.sampleRate else 0,
            limiterHits = out.limiterHits,
            xruns = out.xruns,
            outputError = out.error,
            standardMode = lib.standardOutput,
            playing = playing,
            slots = snaps,
            comboName = comboName(),
            bookmarkName = bm?.name,
            bookmarkDirty = bm != null && bm.combo != lib.current,
            queueMode = lib.queueMode,
            queueIndex = lib.queueIndex,
            queueSize = queueSize(),
            repeat = lib.repeat,
            lib = lib,
            libVersion = libVersion,
            message = message,
            messageId = messageId,
        )
        snapshot = snap
        val playingChanged = lastPlayingPublished != playing
        lastPlayingPublished = playing
        mainHandler.post {
            listener?.invoke(snap)
            if (playingChanged) updateForeground(snap)
            updateSession(snap)
        }
    }

    private fun canOpen(uri: String): Boolean = try {
        contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close() != null
    } catch (_: Exception) {
        false
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, engineHandler)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onFocusChange(change: Int) {
        Log.i(SlotController.TAG, "focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = playing
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) startPlay()
                resumeOnFocusGain = false
            }
        }
    }

    // ---------------- MediaSession / 通知（ZX500 侧面实体键）----------------

    private fun setupSession() {
        session = MediaSession(this, "ZXDual").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = post { if (!playing) startPlay() }
                override fun onPause() = post { pause() }
                override fun onSkipToNext() = post { next(auto = false) }
                override fun onSkipToPrevious() = post { prev() }
                override fun onStop() = post { pause() }
            })
            isActive = true
        }
    }

    private fun updateSession(s: EngineSnapshot) {
        val a = s.slots.firstOrNull { it.enabled && it.hasFile } ?: s.slots[0]
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP,
                )
                .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, a.positionMs, if (s.playing) 1f else 0f)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.comboName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.bookmarkName ?: a.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, a.durationMs)
                .build(),
        )
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "播放", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(s: EngineSnapshot): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val on = s.slots.filter { it.enabled && it.hasFile }.joinToString("  ") { "${"AB"[it.index]}✓" }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.comboName)
            .setContentText(listOfNotNull(s.bookmarkName?.let { "书签：$it" }, on, SlotController.khz(s.outputRate)).joinToString(" · "))
            .setContentIntent(open)
            .setOngoing(s.playing)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "上一项", pi(ACTION_PREV, 1)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    if (s.playing) "暂停" else "播放", pi(ACTION_TOGGLE, 2),
                ).build(),
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "下一项", pi(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateForeground(s: EngineSnapshot) {
        val n = buildNotification(s)
        val nm = getSystemService(NotificationManager::class.java)
        if (s.playing) {
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!isForeground) {
                startService(Intent(this, PlaybackService::class.java))
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIF_ID, n)
                isForeground = true
            } else {
                nm.notify(NOTIF_ID, n)
            }
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            nm.notify(NOTIF_ID, n)
        }
    }

    // ---------------- 持久化 ----------------

    private val prefs by lazy { getSharedPreferences("state", MODE_PRIVATE) }
    private var saveScheduled = false

    private fun saveStateLater() {
        if (saveScheduled) return
        saveScheduled = true
        engineHandler.postDelayed({ saveScheduled = false; saveState() }, 1500)
    }

    private fun saveState() {
        prefs.edit().putString("library", lib.toJson().toString()).apply()
    }

    private fun restoreState() {
        val s = prefs.getString("library", null) ?: return
        runCatching { lib = Library.fromJson(JSONObject(s)) }
        applyCurrent()
    }

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.zxdual.player.TOGGLE"
        const val ACTION_NEXT = "com.zxdual.player.NEXT"
        const val ACTION_PREV = "com.zxdual.player.PREV"
    }
}

// ---------------- 列表工具 ----------------

fun <T> List<T>.moved(pos: Int, delta: Int): List<T> {
    val to = pos + delta
    if (pos !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(pos)) }
}

fun <T> List<T>.removedAt(pos: Int): List<T> = if (pos in indices) toMutableList().apply { removeAt(pos) } else this

/** 删除列表项后修正正在播放的位置 */
fun Library.fixIndex(mode: QueueMode, removed: Int): Library {
    if (queueMode != mode) return this
    return when {
        removed < queueIndex -> copy(queueIndex = queueIndex - 1)
        removed == queueIndex -> copy(queueIndex = -1)
        else -> this
    }
}

/** 移动列表项后让「正在播放」的标记跟着走 */
fun Library.followMove(mode: QueueMode, pos: Int, delta: Int): Library {
    val size = if (mode == QueueMode.BOOKMARKS) bookmarkQueue.size else fileQueue.size
    if (queueMode != mode || pos + delta !in 0 until size || pos !in 0 until size) return this
    return when (queueIndex) {
        pos -> copy(queueIndex = pos + delta)
        pos + delta -> copy(queueIndex = pos)
        else -> this
    }
}

fun Library.leaveQueue(mode: QueueMode) = if (queueMode == mode) copy(queueMode = QueueMode.NONE, queueIndex = -1) else this
