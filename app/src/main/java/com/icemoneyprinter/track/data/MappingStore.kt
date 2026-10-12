package com.icemoneyprinter.track.data

/** Persisted guided-tap mapping for a folder (plan D-refinement: keyed by folder + audio). */
data class SavedMapping(
    val folderKey: String,
    val audioHash: String,
    val audioDurationMs: Long,
    /** imageHash → tap time in ms, in tap order. URIs are re-resolved from the pool on reuse. */
    val taps: List<Pair<String, Long>>,
)

/**
 * Tiny append-style text store, hand-rolled to keep the pure-Kotlin core free of
 * JSON dependencies and unit-testable. Lives in app-private storage; the user's
 * folder is never written to.
 */
object MappingStore {

    private const val VERSION = 1

    fun save(context: android.content.Context, mapping: SavedMapping) {
        val dir = java.io.File(context.filesDir, "mappings").apply { mkdirs() }
        val file = java.io.File(dir, "${mapping.folderKey}.txt")
        val text = buildString {
            appendLine("v=$VERSION")
            appendLine("folder=${mapping.folderKey}")
            appendLine("audio=${mapping.audioHash}")
            appendLine("duration=${mapping.audioDurationMs}")
            mapping.taps.forEach { (hash, ms) ->
                appendLine("tap=$hash@$ms")
            }
        }
        file.writeText(text)
    }

    fun load(context: android.content.Context, folderKey: String): SavedMapping? {
        val file = java.io.File(java.io.File(context.filesDir, "mappings"), "$folderKey.txt")
        if (!file.exists()) return null
        return runCatching {
            var audioHash = ""
            var duration = 0L
            val taps = ArrayList<Pair<String, Long>>()
            file.forEachLine { line ->
                when {
                    line == "v=$VERSION" -> {}
                    line.startsWith("audio=") -> audioHash = line.removePrefix("audio=")
                    line.startsWith("duration=") -> duration = line.removePrefix("duration=").toLong()
                    line.startsWith("tap=") -> {
                        val body = line.removePrefix("tap=")
                        val idx = body.lastIndexOf('@')
                        if (idx > 0) {
                            taps.add(body.substring(0, idx) to body.substring(idx + 1).toLong())
                        }
                    }
                }
            }
            SavedMapping(folderKey, audioHash, duration, taps)
        }.getOrNull()
    }

    fun folderKey(treeUri: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
            .digest(treeUri.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }
}
