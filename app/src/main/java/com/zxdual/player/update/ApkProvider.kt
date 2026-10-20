package com.zxdual.player.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 只把缓存目录里下载好的更新包交给系统安装器读取（不依赖 AndroidX FileProvider）。
 * content://com.zxdual.player.apk/<文件名>
 */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.contains("..") || !name.endsWith(".apk")) throw FileNotFoundException()
        val f = File(File(context!!.cacheDir, DIR), name)
        if (!f.exists()) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = MIME

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.zxdual.player.apk"
        const val DIR = "updates"
        const val MIME = "application/vnd.android.package-archive"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${f.name}")
    }
}
