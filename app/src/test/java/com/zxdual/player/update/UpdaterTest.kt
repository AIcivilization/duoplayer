package com.zxdual.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertEquals(listOf(1, 3, 1), Updater.parseVersion("v1.3.1"))
        assertTrue(Updater.isNewer("v1.4", "1.3"))
        assertTrue(Updater.isNewer("v1.3.1", "1.3"))
        assertTrue(Updater.isNewer("v1.10", "1.9"))
        assertFalse(Updater.isNewer("v1.3", "1.3"))
        assertFalse(Updater.isNewer("v1.3.0", "1.3"))
        assertFalse(Updater.isNewer("v1.2", "1.3"))
        assertFalse(Updater.isNewer("latest", "1.3"))
    }

    @Test fun parseGithubRelease() {
        val json = """{"tag_name":"v1.4","name":"双轨 Hi-Fi 1.4","body":"- 修复\n- 新功能","draft":false,"prerelease":false,
            "assets":[{"name":"notes.txt","browser_download_url":"x","size":1},
                      {"name":"duoplayer-1.4.apk","browser_download_url":"https://github.com/a/b/releases/download/v1.4/duoplayer-1.4.apk","size":3227345}]}"""
        val r = Updater.parseRelease(json)!!
        assertEquals("v1.4", r.tag)
        assertEquals("duoplayer-1.4.apk", r.apkName)
        assertEquals(3227345L, r.apkSize)
        assertTrue(r.notes.contains("新功能"))
        assertNull(Updater.parseRelease("""{"tag_name":"v2","draft":true,"assets":[{"name":"a.apk"}]}"""))
        assertNull(Updater.parseRelease("""{"tag_name":"v2","assets":[]}"""))
    }
}
