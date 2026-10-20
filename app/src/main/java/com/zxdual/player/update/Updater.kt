package com.zxdual.player.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 检查并下载新版本。
 * 发布约定：tag 为 v<versionName>（如 v1.3），附件里有一个 .apk。
 */
object Updater {
    const val REPO = "AIcivilization/duoplayer"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

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

    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return Release(
                    tag = o.optString("tag_name"),
                    title = o.optString("name").ifEmpty { o.optString("tag_name") },
                    notes = o.optString("body").trim(),
                    apkUrl = a.optString("browser_download_url"),
                    apkName = name,
                    apkSize = a.optLong("size"),
                )
            }
        }
        return null
    }

    /** 阻塞调用，请在后台线程执行 */
    fun fetchLatest(): Release? {
        val c = open(API_LATEST)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> return parseRelease(c.inputStream.bufferedReader().use { it.readText() })
                404 -> return null  // 还没有发布任何版本
                403, 429 -> throw IOException("GitHub 访问频率受限，请稍后再试")
                else -> throw IOException("GitHub 返回 ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** 下载到缓存目录；progress(已下载, 总大小)。阻塞调用 */
    fun download(ctx: Context, r: Release, progress: (Long, Long) -> Unit): File {
        val dir = File(ctx.cacheDir, ApkProvider.DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // 只保留这一次的安装包
        val out = File(dir, "duoplayer-${r.tag}.apk")
        val tmp = File(dir, out.name + ".part")
        var url = r.apkUrl
        // GitHub 附件会 302 跳到另一个域名，HttpURLConnection 不跟随跨协议/跨域跳转时手动跟
        repeat(5) {
            val c = open(url)
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw IOException("下载跳转失败")
                c.disconnect()
                return@repeat
            }
            if (code != 200) { c.disconnect(); throw IOException("下载失败：HTTP $code") }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 128 * 1024) { progress(done, total); lastReport = done }
                    }
                    progress(done, total)
                }
            }
            c.disconnect()
            if (!tmp.renameTo(out)) throw IOException("保存安装包失败")
            return out
        }
        throw IOException("下载跳转次数过多")
    }

    /**
     * 安装前校验：包名一致、版本更高、签名与当前安装的完全相同。
     * 返回 null 表示通过，否则返回原因。
     */
    @Suppress("DEPRECATION")
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val newInfo = pm.getPackageArchiveInfo(apk.path, flags) ?: return "安装包已损坏"
        val cur = pm.getPackageInfo(ctx.packageName, flags)
        if (newInfo.packageName != ctx.packageName) return "安装包不是本应用"
        if (versionCode(newInfo) <= versionCode(cur)) return "安装包版本不比当前新"
        val a = signatures(newInfo).map { it.toCharsString() }.toSet()
        val b = signatures(cur).map { it.toCharsString() }.toSet()
        if (a.isEmpty() || a != b) {
            return "签名与当前安装的不一致，无法覆盖安装。\n（1.2 及更早版本用的是临时签名：请先在「列表」页备份，卸载旧版，再从 GitHub 手动安装一次新版，以后即可自动更新。）"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo): List<Signature> =
        if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toList().orEmpty() else p.signatures?.toList().orEmpty()

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    fun currentVersionName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "duoplayer-updater")
    }
}
