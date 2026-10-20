package com.zxdual.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    private val sha = "a".repeat(64)

    @Test fun githubReleaseAddsMirrorsAndSha() {
        val json = """{"tag_name":"v1.7","name":"DuoPlayer 1.7","body":"- fix\n\n---\nAPK SHA-256: `$sha`\n签名证书 SHA-256: `${"b".repeat(64)}`","draft":false,"prerelease":false,
            "assets":[{"name":"notes.txt","browser_download_url":"x","size":1},
                      {"name":"duoplayer-1.7.apk","browser_download_url":"https://github.com/a/b/releases/download/v1.7/duoplayer-1.7.apk","size":3494493}]}"""
        val r = Updater.parseRelease(json)!!
        assertEquals("v1.7", r.tag)
        assertEquals(sha, r.sha256)  // 取 APK 那一行，不是签名证书那一行
        assertEquals("https://github.com/a/b/releases/download/v1.7/duoplayer-1.7.apk", r.apkUrls[0])
        assertEquals(Updater.MIRRORS.map { it + "duoplayer-1.7.apk" }, r.apkUrls.drop(1))
        assertNull(Updater.parseRelease("""{"tag_name":"v2","draft":true,"assets":[{"name":"a.apk"}]}"""))
        assertNull(Updater.parseRelease("""{"tag_name":"v2","assets":[]}"""))
    }

    @Test fun distJsonStartsFromWorkingMirror() {
        val json = """{"tag":"v1.7","versionName":"1.7","versionCode":8,"apk":"duoplayer-1.7.apk","size":3500000,"sha256":"$sha","notes":"## 1.7\n- 国内镜像"}"""
        val base = Updater.MIRRORS[1]
        val r = Updater.parseDist(json, base)!!
        assertEquals("v1.7", r.tag)
        assertEquals(sha, r.sha256)
        assertEquals(base + "duoplayer-1.7.apk", r.apkUrls.first())     // 先用刚读通的镜像
        assertTrue(r.apkUrls.last().startsWith("https://github.com/"))  // GitHub 放最后
        assertEquals(Updater.MIRRORS.size + 1, r.apkUrls.size)
        assertTrue(r.notes.contains("国内镜像"))
        assertNull(Updater.parseDist("""{"tag":"","apk":"x.apk"}""", base))
    }

    @Test fun sha256OfFile() {
        val f = File.createTempFile("sha", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
    }
}
