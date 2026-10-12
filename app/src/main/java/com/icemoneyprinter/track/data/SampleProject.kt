package com.icemoneyprinter.track.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Bundled sample project: a small folder of generated images + a narration WAV,
 * copied out of assets on first use so the owner can try TRACK end to end without
 * hunting for files.
 */
object SampleProject {

    private const val DIR = "sample-project"
    private const val ASSET_DIR = "sample"

    /** Copies (or refreshes) the sample folder into the cache. Returns the directory. */
    fun ensure(context: Context): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        context.assets.list(ASSET_DIR)?.forEach { name ->
            val target = File(dir, name)
            runCatching {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    FileOutputStream(target).use { input.copyTo(it, 64 * 1024) }
                }
            }
        }
        return dir
    }
}
