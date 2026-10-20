package com.zxdual.player.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

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
        if (manual) toast("正在检查更新…")
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            main.post {
                busy = false
                if (activity.isFinishing) return@post
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                result.onSuccess { r ->
                    val skipped = prefs.getString("skip", null)
                    when {
                        r == null -> if (manual) toast("GitHub 上还没有发布版本")
                        !Updater.isNewer(r.tag, currentVersion) -> if (manual) toast("已是最新版本（$currentVersion）")
                        !manual && r.tag == skipped -> Unit
                        else -> offer(r)
                    }
                }.onFailure { e ->
                    if (manual) {
                        AlertDialog.Builder(activity, dialogTheme).setTitle("检查更新失败")
                            .setMessage("${e.message ?: e.javaClass.simpleName}\n\n国内网络访问 GitHub 可能不稳定，可以稍后再试，或用浏览器打开发布页手动下载。")
                            .setPositiveButton("打开发布页") { _, _ -> openReleases() }
                            .setNegativeButton("关闭", null).show()
                    }
                }
            }
        }.start()
    }

    private fun offer(r: Updater.Release) {
        val size = if (r.apkSize > 0) " · %.1f MB".format(r.apkSize / 1048576.0) else ""
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle("发现新版本 ${r.tag}")
            .setMessage("当前版本 $currentVersion$size\n\n" + r.notes.take(1500).ifEmpty { r.title })
            .setPositiveButton("下载并安装") { _, _ -> ensureInstallAllowed { download(r) } }
            .setNeutralButton("跳过此版本") { _, _ -> prefs.edit().putString("skip", r.tag).apply() }
            .setNegativeButton("以后", null)
            .show()
    }

    /** Android 8+ 需要用户允许本应用「安装未知应用」 */
    private fun ensureInstallAllowed(then: () -> Unit) {
        if (activity.packageManager.canRequestPackageInstalls()) { then(); return }
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle("需要一次授权")
            .setMessage("安卓要求允许「双轨 Hi-Fi」安装应用，才能自己更新。\n\n点「去设置」→ 打开「允许来自此来源的应用」→ 返回，再点一次「检查更新」。")
            .setPositiveButton("去设置") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            .setNegativeButton("取消", null).show()
    }

    private fun download(r: Updater.Release) {
        if (busy) return
        busy = true
        val dlg = AlertDialog.Builder(activity, dialogTheme)
            .setTitle("正在下载 ${r.tag}")
            .setMessage("准备中…")
            .setCancelable(false)
            .show()
        Thread {
            val result = runCatching {
                val f = Updater.download(activity, r) { done, total ->
                    val text = if (total > 0) "%.1f / %.1f MB（%d%%）".format(done / 1048576.0, total / 1048576.0, done * 100 / total)
                    else "%.1f MB".format(done / 1048576.0)
                    main.post { dlg.setMessage(text) }
                }
                Updater.verify(activity, f)?.let { throw IllegalStateException(it) }
                f
            }
            main.post {
                busy = false
                dlg.dismiss()
                result.onSuccess { f ->
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(ApkProvider.uriFor(f), ApkProvider.MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { activity.startActivity(i) }.onFailure { toast("无法打开系统安装器：${it.message}") }
                }.onFailure { e ->
                    AlertDialog.Builder(activity, dialogTheme).setTitle("更新失败")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("打开发布页") { _, _ -> openReleases() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }.start()
    }

    fun openReleases() {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE))) }
            .onFailure { toast("没有可用的浏览器：${Updater.RELEASES_PAGE}") }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}
