package com.icemoneyprinter.track.render

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Free-space estimation for a render of [durationMs] at default encode settings. */
object FreeSpace {
    private const val VIDEO_BPS = 8_000_000L
    private const val AUDIO_BPS = 192_000L

    fun estimatedOutputBytes(durationMs: Long): Long =
        (durationMs / 1000.0 * (VIDEO_BPS + AUDIO_BPS) / 8.0).toLong() + 1024 * 1024

    fun availableBytes(path: File): Long = runCatching {
        val stat = android.os.StatFs(path.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE / 4)
}

/**
 * Export the finished MP4 where the owner can find and share it:
 * - API 29+: MediaStore `Movies/TRACK` (gallery-visible, no permissions)
 * - API 26–28: app-external movies dir + media scan + share (legacy path)
 */
object MediaStoreExporter {

    fun exportMp4(context: Context, source: File, displayName: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, displayName)
        } else {
            exportLegacy(context, source, displayName)
        }
    } catch (_: Exception) {
        null
    }

    private fun exportViaMediaStore(context: Context, source: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TRACK")
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { it.copyTo(out, 64 * 1024) }
        } ?: throw IllegalStateException("could not open output stream")
        return uri
    }

    private fun exportLegacy(context: Context, source: File, displayName: String): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("no external files dir")
        val dest = File(dir, displayName)
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) }
        }
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf("video/mp4"),
            null,
        )
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            dest,
        )
    }
}
