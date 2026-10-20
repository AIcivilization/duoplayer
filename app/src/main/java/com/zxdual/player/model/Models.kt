package com.zxdual.player.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 文件引用。uri 为 SAF 的 content:// 地址；docId 形如 "primary:Music/专辑/01.flac"，
 * 用于重装应用或换设备后「重新关联文件夹」时按路径/文件名找回文件。
 */
data class FileRef(
    val uri: String,
    val name: String,
    val docId: String? = null,
    val lrc: FileRef? = null,
) {
    /** docId 去掉存储卷前缀后的相对路径 */
    val relPath: String? get() = docId?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("name", name).apply {
        docId?.let { put("doc", it) }
        lrc?.let { put("lrc", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject?): FileRef? {
            if (o == null || !o.has("uri")) return null
            return FileRef(
                uri = o.getString("uri"),
                name = o.optString("name"),
                docId = o.optString("doc").takeIf { it.isNotEmpty() },
                lrc = fromJson(o.optJSONObject("lrc")),
            )
        }
    }
}

/** A 或 B 一路的设置 */
data class SlotConfig(
    val file: FileRef? = null,
    val gainDb: Double = 0.0,
    val balance: Double = 0.0,
    val loop: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gainDb", gainDb).put("balance", balance).put("loop", loop).put("enabled", enabled)
        .apply { file?.let { put("file", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject?): SlotConfig {
            if (o == null) return SlotConfig()
            return SlotConfig(
                file = FileRef.fromJson(o.optJSONObject("file")),
                gainDb = o.optDouble("gainDb", 0.0),
                balance = o.optDouble("balance", 0.0),
                loop = o.optBoolean("loop", false),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/** A+B 组合 */
data class Combo(val a: SlotConfig = SlotConfig(), val b: SlotConfig = SlotConfig()) {
    fun slot(i: Int) = if (i == 0) a else b
    fun with(i: Int, s: SlotConfig) = if (i == 0) copy(a = s) else copy(b = s)

    fun toJson(): JSONObject = JSONObject().put("a", a.toJson()).put("b", b.toJson())

    companion object {
        fun fromJson(o: JSONObject?) = Combo(SlotConfig.fromJson(o?.optJSONObject("a")), SlotConfig.fromJson(o?.optJSONObject("b")))

        /** 「A名 + B名」 */
        fun displayName(a: String?, b: String?): String = listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() })
            .joinToString(" + ").ifEmpty { "未选择音频" }
    }
}

data class Bookmark(val id: String, val name: String, val combo: Combo) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("combo", combo.toJson())

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun fromJson(o: JSONObject) = Bookmark(o.optString("id").ifEmpty { newId() }, o.optString("name"), Combo.fromJson(o.optJSONObject("combo")))
    }
}

enum class QueueMode { NONE, BOOKMARKS, FILES }

enum class Repeat(val label: String) { OFF("顺序播放"), ALL("列表循环"), ONE("单项循环") }

/**
 * 应用的全部可持久化数据；内部保存与「备份」用同一种 JSON 格式。
 */
data class Library(
    val current: Combo = Combo(),
    val currentBookmarkId: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkQueue: List<String> = emptyList(),  // 书签 id 列表
    val fileQueue: List<FileRef> = emptyList(),
    val queueMode: QueueMode = QueueMode.NONE,
    val queueIndex: Int = -1,
    val repeat: Repeat = Repeat.OFF,
    val standardOutput: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("app", "ZXDualPlayer").put("format", FORMAT)
        .put("current", current.toJson())
        .put("currentBookmarkId", currentBookmarkId ?: "")
        .put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.toJson()) } })
        .put("bookmarkQueue", JSONArray().apply { bookmarkQueue.forEach { put(it) } })
        .put("fileQueue", JSONArray().apply { fileQueue.forEach { put(it.toJson()) } })
        .put("queueMode", queueMode.name).put("queueIndex", queueIndex)
        .put("repeat", repeat.name).put("standardOutput", standardOutput)

    /** 合并另一份备份：书签按 id 去重追加，列表追加 */
    fun merge(other: Library): Library {
        val ids = bookmarks.map { it.id }.toSet()
        val added = other.bookmarks.filter { it.id !in ids }
        return copy(
            bookmarks = bookmarks + added,
            bookmarkQueue = bookmarkQueue + other.bookmarkQueue.filter { id -> (bookmarks + added).any { it.id == id } },
            fileQueue = fileQueue + other.fileQueue.filter { f -> fileQueue.none { it.uri == f.uri } },
        )
    }

    /** 所有出现过的文件（含歌词），用于检查可访问性与重新关联 */
    fun allFiles(): List<FileRef> {
        val out = mutableListOf<FileRef>()
        fun add(f: FileRef?) { if (f != null) { out.add(f); f.lrc?.let { out.add(it) } } }
        add(current.a.file); add(current.b.file)
        bookmarks.forEach { add(it.combo.a.file); add(it.combo.b.file) }
        fileQueue.forEach { add(it) }
        return out
    }

    /** 用映射表替换文件地址（重新关联） */
    fun remap(map: (FileRef) -> FileRef?): Library {
        fun f(x: FileRef?): FileRef? = x?.let { r -> (map(r) ?: r).copy(lrc = r.lrc?.let { l -> map(l) ?: l }) }
        fun s(x: SlotConfig) = x.copy(file = f(x.file))
        fun c(x: Combo) = Combo(s(x.a), s(x.b))
        return copy(
            current = c(current),
            bookmarks = bookmarks.map { it.copy(combo = c(it.combo)) },
            fileQueue = fileQueue.map { f(it)!! },
        )
    }

    companion object {
        const val FORMAT = 2

        fun fromJson(o: JSONObject): Library {
            val bms = o.optJSONArray("bookmarks") ?: JSONArray()
            val bq = o.optJSONArray("bookmarkQueue") ?: JSONArray()
            val fq = o.optJSONArray("fileQueue") ?: JSONArray()
            return Library(
                current = Combo.fromJson(o.optJSONObject("current")),
                currentBookmarkId = o.optString("currentBookmarkId").takeIf { it.isNotEmpty() },
                bookmarks = (0 until bms.length()).map { Bookmark.fromJson(bms.getJSONObject(it)) },
                bookmarkQueue = (0 until bq.length()).map { bq.getString(it) },
                fileQueue = (0 until fq.length()).mapNotNull { FileRef.fromJson(fq.getJSONObject(it)) },
                queueMode = runCatching { QueueMode.valueOf(o.optString("queueMode")) }.getOrDefault(QueueMode.NONE),
                queueIndex = o.optInt("queueIndex", -1),
                repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.OFF),
                standardOutput = o.optBoolean("standardOutput", false),
            )
        }

        fun isBackup(o: JSONObject) = o.optString("app") == "ZXDualPlayer"
    }
}

/**
 * 重新关联：已知用户选中的根文件夹下所有文件 (docId, uri, name)，
 * 先按相对路径后缀匹配，再按唯一文件名匹配。
 */
class Relinker(entries: List<Triple<String, String, String>>) {
    private val byName = HashMap<String, MutableList<Triple<String, String, String>>>()

    init {
        for (e in entries) byName.getOrPut(e.third.lowercase()) { mutableListOf() }.add(e)
    }

    /** 同名文件中挑「末尾路径段重合最多」的那个；并列则放弃，避免关联错 */
    fun find(ref: FileRef): FileRef? {
        val same = byName[ref.name.lowercase()] ?: return null
        if (same.size == 1) return ref.copy(uri = same[0].second, docId = same[0].first)
        val want = ref.relPath?.lowercase()?.split('/')?.reversed() ?: return null
        val scored = same.map { e ->
            val have = e.first.substringAfter(':').lowercase().split('/').reversed()
            e to want.zip(have).takeWhile { it.first == it.second }.count()
        }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return if (top.size == 1 && best >= 2) ref.copy(uri = top[0].first.second, docId = top[0].first.first) else null
    }
}
