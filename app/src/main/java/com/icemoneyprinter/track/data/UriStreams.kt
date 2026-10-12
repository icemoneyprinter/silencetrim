package com.icemoneyprinter.track.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/** Opens a stream for both SAF (content://) and local file:// URIs (sample project). */
object UriStreams {
    fun open(context: Context, uri: Uri): InputStream? {
        return when (uri.scheme) {
            "file" -> uri.path
                ?.takeIf { java.io.File(it).exists() }
                ?.let { java.io.FileInputStream(it) }
            else -> context.contentResolver.openInputStream(uri)
        }
    }
}
