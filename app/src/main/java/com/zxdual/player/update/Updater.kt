package com.zxdual.player.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.zxdual.player.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 带本地化文字资源的更新错误（界面层用 getString 显示） */
class UpdateException(val res: Int, val arg: String = "") : IOException(arg)

/**
 * 检查并下载新版本。两条通道，任一可用即可：
 *  1. GitHub Releases（tag 为 v<versionName>，附件里有一个 .apk）
 *  2. 国内镜像：仓库 dist 分支的 latest.json + APK，经 jsDelivr CDN 分发（GitHub 下载在国内常连不上）
 * 下载时依次尝试所有地址；装前校验 SHA-256（若已知）和签名，镜像无法篡改安装包。
 */
object Updater {
    const val REPO = "AIcivilization/duoplayer"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    /** 可在测试里改成不可达地址，模拟「GitHub 连不上」 */
    internal var apiLatest = "https://api.github.com/repos/$REPO/releases/latest"

    /** jsDelivr 的几个入口，按顺序尝试 */
    val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@dist/",
        "https://fastly.jsdelivr.net/gh/$REPO@dist/",
        "https://testingcf.jsdelivr.net/gh/$REPO@dist/",
    )

    /** 给浏览器手动下载用的镜像页（国内一般能打开） */
    const val MIRROR_PAGE = "https://cdn.jsdelivr.net/gh/$REPO@dist/"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkName: String,
        val apkSize: Long,
        val apkUrls: List<String>,
        val sha256: String? = null,
        val source: String = "",
    ) {
        val apkUrl: String get() = apkUrls.first()
    }

    /** "v1.3.1" / "1.3" → [1, 3, 1] */
    fun parseVersion(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V").split('.', '-', '_').map { part -> part.takeWhile { it.isDigit() } }
            .takeWhile { it.isNotEmpty() }.map { it.toInt() }

    fun isNewer(remote: String, current: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private val SHA_IN_NOTES = Regex("APK SHA-256:\\s*`?([0-9a-fA-F]{64})`?")

    /** GitHub API 的 release JSON；APK 地址后面自动追加镜像地址作备用 */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val body = o.optString("body").trim()
                return Release(
                    tag = o.optString("tag_name"),
                    title = o.optString("name").ifEmpty { o.optString("tag_name") },
                    notes = body,
                    apkName = name,
                    apkSize = a.optLong("size"),
                    apkUrls = listOf(a.optString("browser_download_url")) + MIRRORS.map { it + name },
                    sha256 = SHA_IN_NOTES.find(body)?.groupValues?.get(1)?.lowercase(),
                    source = "GitHub",
                )
            }
        }
        return null
    }

    /** 镜像的 latest.json；从读成功的那个镜像开始下载，最后才用 GitHub */
    fun parseDist(json: String, mirrorBase: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag")
        val apk = o.optString("apk")
        if (tag.isEmpty() || !apk.endsWith(".apk")) return null
        val bases = listOf(mirrorBase) + MIRRORS.filter { it != mirrorBase }
        return Release(
            tag = tag,
            title = tag,
            notes = o.optString("notes"),
            apkName = apk,
            apkSize = o.optLong("size"),
            apkUrls = bases.map { it + apk } + "https://github.com/$REPO/releases/download/$tag/$apk",
            sha256 = o.optString("sha256").takeIf { it.length == 64 }?.lowercase(),
            source = URL(mirrorBase).host,
        )
    }

    /** 阻塞调用，请在后台线程执行。GitHub 不通时自动改用镜像 */
    fun fetchLatest(): Release? {
        val first = runCatching { fetchGitHub() }
        first.getOrNull()?.let { return it }
        if (first.isSuccess) return null  // GitHub 可达，只是还没有发布任何版本
        for (base in MIRRORS) {
            val r = runCatching { fetchDist(base) }.getOrNull()
            if (r != null) return r
        }
        throw first.exceptionOrNull()!!
    }

    private fun fetchGitHub(): Release? {
        val c = open(apiLatest, connectMs = 8000, readMs = 10000)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            return when (c.responseCode) {
                200 -> parseRelease(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                403, 429 -> throw UpdateException(R.string.upd_err_rate_limited)
                else -> throw UpdateException(R.string.upd_err_http, c.responseCode.toString())
            }
        } finally {
            c.disconnect()
        }
    }

    private fun fetchDist(base: String): Release? {
        // 加时间戳，避开中间缓存拿到旧的 latest.json
        val c = open(base + "latest.json?t=" + System.currentTimeMillis() / 60000, connectMs = 8000, readMs = 10000)
        try {
            if (c.responseCode != 200) throw UpdateException(R.string.upd_err_http, c.responseCode.toString())
            return parseDist(c.inputStream.bufferedReader().use { it.readText() }, base)
        } finally {
            c.disconnect()
        }
    }

    /**
     * 依次尝试每个下载地址，保存到缓存目录。
     * progress(已下载, 总大小, 当前来源域名)。阻塞调用。
     */
    fun download(ctx: Context, r: Release, progress: (Long, Long, String) -> Unit): File =
        downloadTo(File(ctx.cacheDir, ApkProvider.DIR), r, progress)

    internal fun downloadTo(dirIn: File, r: Release, progress: (Long, Long, String) -> Unit): File {
        val dir = dirIn.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // 只保留这一次的安装包
        val out = File(dir, "duoplayer-${r.tag}.apk")
        var lastError: IOException = UpdateException(R.string.upd_err_download, "-")
        for (u in r.apkUrls) {
            try {
                downloadOne(u, r, out, progress)
                if (r.sha256 != null && sha256(out) != r.sha256) {
                    out.delete()
                    lastError = UpdateException(R.string.upd_err_sha)
                    continue
                }
                return out
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun downloadOne(start: String, r: Release, out: File, progress: (Long, Long, String) -> Unit) {
        val tmp = File(out.path + ".part")
        var url = start
        // GitHub 附件会 302 跳到另一个域名，手动跟随
        repeat(5) {
            val c = open(url, connectMs = 10000, readMs = 30000)
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } ?: throw UpdateException(R.string.upd_err_redirect)
                c.disconnect()
                return@repeat
            }
            if (code != 200) { c.disconnect(); throw UpdateException(R.string.upd_err_download, code.toString()) }
            val host = URL(start).host
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    progress(0, total, host)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 128 * 1024) { progress(done, total, host); lastReport = done }
                    }
                    progress(done, total, host)
                }
            }
            c.disconnect()
            if (!tmp.renameTo(out)) throw UpdateException(R.string.upd_err_save)
            return
        }
        throw UpdateException(R.string.upd_err_redirects)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 安装前校验：包名一致、版本更高、签名与当前安装的完全相同。
     * 返回 null 表示通过，否则返回原因（文字资源 id）。
     */
    @Suppress("DEPRECATION")
    fun verify(ctx: Context, apk: File): Int? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val newInfo = pm.getPackageArchiveInfo(apk.path, flags) ?: return R.string.upd_err_damaged
        val cur = pm.getPackageInfo(ctx.packageName, flags)
        if (newInfo.packageName != ctx.packageName) return R.string.upd_err_wrong_pkg
        if (versionCode(newInfo) <= versionCode(cur)) return R.string.upd_err_not_newer
        val a = signatures(newInfo).map { it.toCharsString() }.toSet()
        val b = signatures(cur).map { it.toCharsString() }.toSet()
        if (a.isEmpty() || a != b) {
            return R.string.upd_err_signature
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo): List<Signature> =
        if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toList().orEmpty() else p.signatures?.toList().orEmpty()

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    fun currentVersionName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    private fun open(url: String, connectMs: Int = 15000, readMs: Int = 30000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", "duoplayer-updater")
        }
}
