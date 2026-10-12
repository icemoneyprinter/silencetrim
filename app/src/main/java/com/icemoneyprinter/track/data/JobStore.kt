package com.icemoneyprinter.track.data

import android.content.Context
import com.icemoneyprinter.track.model.Slot
import java.io.File

/**
 * Persisted render job so the pipeline survives process death (plan D9).
 * Text format, same style as MappingStore — no JSON dependency.
 */
data class PersistedJob(
    val folderUri: String,
    val audioUri: String,
    val narrationName: String,
    val audioDurationMs: Long,
    val tappedMode: Boolean,
    val slots: List<Slot>,
    val completedArcs: List<Int>,
)

object JobStore {

    private const val VERSION = 1

    fun save(context: Context, job: PersistedJob) {
        val dir = File(context.filesDir, "jobs").apply { mkdirs() }
        val file = File(dir, "current.txt")
        val text = buildString {
            appendLine("v=$VERSION")
            appendLine("folder=${job.folderUri}")
            appendLine("audio=${job.audioUri}")
            appendLine("narration=${job.narrationName}")
            appendLine("duration=${job.audioDurationMs}")
            appendLine("tapped=${job.tappedMode}")
            appendLine("arcsDone=${job.completedArcs.joinToString(",")}")
            job.slots.forEach { s ->
                appendLine("slot=${s.imageUriString}@${s.startMs}-${s.endMs}")
            }
        }
        file.writeText(text)
    }

    fun load(context: Context): PersistedJob? {
        val file = File(File(context.filesDir, "jobs"), "current.txt")
        if (!file.exists()) return null
        return runCatching {
            var folder = ""
            var audio = ""
            var narration = ""
            var duration = 0L
            var tapped = false
            var arcsDone: List<Int> = emptyList()
            val slots = ArrayList<Slot>()
            file.forEachLine { line ->
                when {
                    line == "v=$VERSION" -> {}
                    line.startsWith("folder=") -> folder = line.removePrefix("folder=")
                    line.startsWith("audio=") -> audio = line.removePrefix("audio=")
                    line.startsWith("narration=") -> narration = line.removePrefix("narration=")
                    line.startsWith("duration=") -> duration = line.removePrefix("duration=").toLong()
                    line.startsWith("tapped=") -> tapped = line.removePrefix("tapped=").toBoolean()
                    line.startsWith("arcsDone=") -> {
                        arcsDone = line.removePrefix("arcsDone=")
                            .split(",")
                            .mapNotNull { it.trim().toIntOrNull() }
                    }
                    line.startsWith("slot=") -> {
                        val body = line.removePrefix("slot=")
                        val at = body.indexOf('@')
                        val dash = body.indexOf('-', at)
                        if (at > 0 && dash > at) {
                            val uri = body.substring(0, at)
                            val start = body.substring(at + 1, dash).toLong()
                            val end = body.substring(dash + 1).toLong()
                            slots.add(Slot(uri, uri, start, end))
                        }
                    }
                }
            }
            PersistedJob(folder, audio, narration, duration, tapped, slots, arcsDone)
        }.getOrNull()
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "jobs"), "current.txt").delete()
    }
}
