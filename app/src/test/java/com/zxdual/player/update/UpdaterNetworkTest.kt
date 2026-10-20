package com.zxdual.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files

/**
 * 联网测试（默认跳过）：DUO_NET_TEST=1 ./gradlew testDebugUnitTest
 * 模拟 GitHub 不可达，确认能从 jsDelivr 镜像拿到最新版本并通过 SHA-256 校验。
 */
class UpdaterNetworkTest {
    @Test fun mirrorWorksWhenGithubIsBlocked() {
        assumeTrue(System.getenv("DUO_NET_TEST") == "1")
        System.getenv("HTTPS_PROXY")?.let { p ->
            val u = URI(p)
            System.setProperty("https.proxyHost", u.host)
            System.setProperty("https.proxyPort", u.port.toString())
        }
        Updater.apiLatest = "https://api.github.invalid/repos/x/y/releases/latest"
        val r = Updater.fetchLatest()
        assertNotNull(r)
        r!!
        println("found ${r.tag} via ${r.source}, sha=${r.sha256}")
        assertEquals(true, r.source.endsWith("jsdelivr.net"))
        val dir = Files.createTempDirectory("duo").toFile()
        var host = ""
        val f = Updater.downloadTo(dir, r) { _, _, h -> host = h }
        println("downloaded ${f.length()} bytes from $host")
        assertEquals(r.sha256, Updater.sha256(f))
    }
}
