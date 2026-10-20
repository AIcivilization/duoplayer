package com.zxdual.player.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.zxdual.player.R

/**
 * 检查更新的界面流程：
 * 检查 GitHub 最新 Release → 提示 → 允许安装未知应用 → 下载 → 校验签名 → 调起系统安装器
 */
class UpdateUi(private val activity: Activity, private val dialogTheme: Int) {
    private val prefs = activity.getSharedPreferences("update", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    val currentVersion: String get() = Updater.currentVersionName(activity)

    /** 打开应用时调用：开启自动检查且距上次检查超过 20 小时才联网，失败时不打扰 */
    fun checkOnLaunch() {
        if (!autoCheck) return
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last < 20 * 3600_000L) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast(str(R.string.upd_checking))
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            main.post {
                busy = false
                if (activity.isFinishing) return@post
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                result.onSuccess { r ->
                    val skipped = prefs.getString("skip", null)
                    when {
                        r == null -> if (manual) toast(str(R.string.upd_none_published))
                        !Updater.isNewer(r.tag, currentVersion) -> if (manual) toast(str(R.string.upd_latest, currentVersion))
                        !manual && r.tag == skipped -> Unit
                        else -> offer(r)
                    }
                }.onFailure { e ->
                    if (manual) {
                        AlertDialog.Builder(activity, dialogTheme).setTitle(str(R.string.upd_check_failed))
                            .setMessage(str(R.string.upd_check_failed_msg, describe(e)))
                            .setPositiveButton(str(R.string.upd_open_mirror)) { _, _ -> openMirror() }
                            .setNeutralButton(str(R.string.upd_open_page)) { _, _ -> openReleases() }
                            .setNegativeButton(str(R.string.close), null).show()
                    }
                }
            }
        }.start()
    }

    private fun offer(r: Updater.Release) {
        val size = if (r.apkSize > 0) " · %.1f MB".format(r.apkSize / 1048576.0) else ""
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle(str(R.string.upd_found, r.tag))
            .setMessage(str(R.string.upd_current, currentVersion, size) + "\n\n" + plainNotes(r.notes).take(1500).ifEmpty { r.title })
            .setPositiveButton(str(R.string.upd_download_install)) { _, _ -> ensureInstallAllowed { download(r) } }
            .setNeutralButton(str(R.string.upd_skip)) { _, _ -> prefs.edit().putString("skip", r.tag).apply() }
            .setNegativeButton(str(R.string.upd_later), null)
            .show()
    }

    /** Android 8+ 需要用户允许本应用「安装未知应用」 */
    private fun ensureInstallAllowed(then: () -> Unit) {
        if (activity.packageManager.canRequestPackageInstalls()) { then(); return }
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle(str(R.string.upd_need_perm_title))
            .setMessage(str(R.string.upd_need_perm_msg, str(R.string.app_name)))
            .setPositiveButton(str(R.string.upd_go_settings)) { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            .setNegativeButton(str(R.string.cancel), null).show()
    }

    private fun download(r: Updater.Release) {
        if (busy) return
        busy = true
        val dlg = AlertDialog.Builder(activity, dialogTheme)
            .setTitle(str(R.string.upd_downloading, r.tag))
            .setMessage(str(R.string.upd_preparing))
            .setCancelable(false)
            .show()
        Thread {
            val result = runCatching {
                val f = Updater.download(activity, r) { done, total, host ->
                    val text = (if (total > 0) str(R.string.upd_progress, "%.1f".format(done / 1048576.0), "%.1f".format(total / 1048576.0), (done * 100 / total).toInt())
                    else str(R.string.upd_progress_unknown, "%.1f".format(done / 1048576.0))) + "\n" + str(R.string.upd_source, host)
                    main.post { dlg.setMessage(text) }
                }
                Updater.verify(activity, f)?.let { throw UpdateException(it) }
                f
            }
            main.post {
                busy = false
                dlg.dismiss()
                result.onSuccess { f ->
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(ApkProvider.uriFor(f), ApkProvider.MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { activity.startActivity(i) }.onFailure { toast(str(R.string.upd_installer_failed, it.message ?: "")) }
                }.onFailure { e ->
                    AlertDialog.Builder(activity, dialogTheme).setTitle(str(R.string.upd_failed))
                        .setMessage(describe(e))
                        .setPositiveButton(str(R.string.upd_open_mirror)) { _, _ -> openMirror() }
                        .setNeutralButton(str(R.string.upd_open_page)) { _, _ -> openReleases() }
                        .setNegativeButton(str(R.string.close), null).show()
                }
            }
        }.start()
    }

    /** 把发布说明的 Markdown 简化成纯文本：去掉标题符号、反引号和末尾的校验信息 */
    private fun plainNotes(md: String): String = md.substringBefore("\n---").lines()
        .joinToString("\n") { it.trimEnd().replace(Regex("^#{1,6}\\s*"), "").replace("`", "").replace("**", "") }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    /** 国内一般能打开的 jsDelivr 镜像目录，里面有最新的 APK */
    fun openMirror() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.MIRROR_PAGE))) }
            .onFailure { toast(str(R.string.upd_no_browser, Updater.MIRROR_PAGE)) }
    }

    fun openReleases() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE))) }
            .onFailure { toast(str(R.string.upd_no_browser, Updater.RELEASES_PAGE)) }
    }

    private fun str(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun describe(e: Throwable): String =
        if (e is UpdateException) (if (e.arg.isEmpty()) str(e.res) else str(e.res, e.arg)) else e.message ?: e.javaClass.simpleName

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}
