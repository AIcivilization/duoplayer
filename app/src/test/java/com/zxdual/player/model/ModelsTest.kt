package com.zxdual.player.model

import com.zxdual.player.followMove
import com.zxdual.player.fixIndex
import com.zxdual.player.moved
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val a = FileRef("content://x/a", "雨声.flac", "primary:Music/白噪音/雨声.flac", FileRef("content://x/a.lrc", "雨声.lrc", "primary:Music/白噪音/雨声.lrc"))
    private val b = FileRef("content://x/b", "Song.dsf", "1234-5678:DSD/Album/Song.dsf")
    private val combo = Combo(SlotConfig(a, -6.0, 0.25, loop = true), SlotConfig(b, 0.0, 0.0, enabled = false))
    private val lib = Library(
        current = combo,
        currentBookmarkId = "id1",
        bookmarks = listOf(Bookmark("id1", "睡前", combo), Bookmark("id2", "学习", Combo(SlotConfig(b)))),
        bookmarkQueue = listOf("id2", "id1"),
        fileQueue = listOf(a, b),
        queueMode = QueueMode.FILES,
        queueIndex = 1,
        repeat = Repeat.ALL,
    )

    @Test fun backupRoundTrip() {
        val json = lib.toJson().toString(2)
        val back = Library.fromJson(JSONObject(json))
        assertEquals(lib, back)
        assertTrue(Library.isBackup(JSONObject(json)))
    }

    @Test fun mergeDedupes() {
        val other = Library(bookmarks = listOf(Bookmark("id1", "dup", combo), Bookmark("id3", "新", combo)), bookmarkQueue = listOf("id3", "nope"), fileQueue = listOf(a))
        val m = lib.merge(other)
        assertEquals(listOf("id1", "id2", "id3"), m.bookmarks.map { it.id })
        assertEquals(listOf("id2", "id1", "id3"), m.bookmarkQueue)
        assertEquals(2, m.fileQueue.size)
    }

    @Test fun relinkByPathAndName() {
        // 重装后：同一张 SD 卡换了卷号，文件 URI 全变了
        val entries = listOf(
            Triple("primary:Music/白噪音/雨声.flac", "content://new/a", "雨声.flac"),
            Triple("primary:Music/白噪音/雨声.lrc", "content://new/a.lrc", "雨声.lrc"),
            Triple("ABCD-0000:DSD/Album/Song.dsf", "content://new/b", "Song.dsf"),
            Triple("ABCD-0000:Other/Song.dsf", "content://new/b2", "Song.dsf"),
        )
        val r = Relinker(entries)
        assertEquals("content://new/a", r.find(a)!!.uri)
        assertEquals("content://new/b", r.find(b)!!.uri)  // 同名两个，按末尾路径选中 DSD/Album
        assertNull(r.find(FileRef("u", "unknown.flac")))
        val map = HashMap<String, FileRef>()
        lib.allFiles().forEach { f -> r.find(f)?.let { map[f.uri] = it } }
        val fixed = lib.remap { map[it.uri] }
        assertEquals("content://new/a", fixed.current.a.file!!.uri)
        assertEquals("content://new/a.lrc", fixed.current.a.file!!.lrc!!.uri)
        assertEquals("content://new/b", fixed.bookmarks[1].combo.a.file!!.uri)
        assertEquals(listOf("content://new/a", "content://new/b"), fixed.fileQueue.map { it.uri })
    }

    @Test fun queueEditing() {
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(1, -1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))
        assertEquals(0, lib.followMove(QueueMode.FILES, 1, -1).queueIndex)
        assertEquals(1, lib.followMove(QueueMode.FILES, 1, 1).queueIndex)  // 越界不动
        assertEquals(0, lib.fixIndex(QueueMode.FILES, 0).queueIndex)
        assertEquals(-1, lib.fixIndex(QueueMode.FILES, 1).queueIndex)
    }

    @Test fun displayName() {
        assertEquals("雨声 + Song", Combo.displayName("雨声", "Song"))
        assertEquals("雨声", Combo.displayName("雨声", null))
        assertEquals("", Combo.displayName(null, ""))
    }
}
