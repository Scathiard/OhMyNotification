package io.github.prince_dulb.ohmynotification.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.util.UUID

internal data class DownloadSnapshot(val status: Int, val bytes: Long, val total: Long, val reason: Int)

/** Small platform boundary so failure/retry tests do not require a real network download. */
internal interface UpdateDownloads {
    fun enqueue(url: String, version: String): Long
    fun snapshot(id: Long): DownloadSnapshot?
    fun open(id: Long): InputStream
    fun remove(id: Long)
}

internal class AndroidUpdateDownloads(private val context: Context) : UpdateDownloads {
    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, version: String): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(url))
            .setTitle("OMN $version")
            .setDescription("下载后请回到 OMN 验证并安装")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "omn-update-${UUID.randomUUID()}.apk"),
    )

    override fun snapshot(id: Long): DownloadSnapshot? = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if (!cursor.moveToFirst()) null else DownloadSnapshot(
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
        )
    }

    override fun open(id: Long): InputStream = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id))
    override fun remove(id: Long) { manager.remove(id) }
}
