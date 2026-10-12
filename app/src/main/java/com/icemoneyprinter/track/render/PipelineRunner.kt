package com.icemoneyprinter.track.render

import android.net.Uri
import com.icemoneyprinter.track.audio.AacEncoder
import com.icemoneyprinter.track.audio.AudioDecoder
import com.icemoneyprinter.track.audio.WavParser
import com.icemoneyprinter.track.data.JobStore
import com.icemoneyprinter.track.data.PersistedJob
import com.icemoneyprinter.track.model.Timeline
import com.icemoneyprinter.track.pacing.Arc
import com.icemoneyprinter.track.pacing.ArcPlanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** Progress of the service-run pipeline; the UI maps this to its own Stage type. */
sealed interface PipelineStatus {
    data object Starting : PipelineStatus
    data class RenderingArc(val done: Int, val total: Int) : PipelineStatus
    data object Concatting : PipelineStatus
    data class EncodingAudio(val message: String) : PipelineStatus
    data class Exporting(val message: String) : PipelineStatus
    data class Finished(val outputUri: Uri?, val outputPath: String) : PipelineStatus
    data class Failed(val message: String) : PipelineStatus
    data object Cancelled : PipelineStatus
}

/** Shared bus between the render service and the UI. */
object PipelineBus {
    private val _status = kotlinx.coroutines.flow.MutableStateFlow<PipelineStatus?>(null)
    val status: kotlinx.coroutines.flow.StateFlow<PipelineStatus?> = _status
    fun publish(status: PipelineStatus) { _status.value = status }
    fun clear() { _status.value = null }
}

/**
 * The batch engine: renders arcs video-only, QA's each one, concats by stream copy
 * with a single audio mux, and exports. Resumes from the last completed arc when the
 * job is restarted; falls back to single-pass re-render if arc profiles don't match
 * (concat safety gate, plan D7).
 */
class PipelineRunner(private val context: android.content.Context) {

    suspend fun run(job: PersistedJob, arcs: List<Arc>, timeline: Timeline) {
        try {
            PipelineBus.publish(PipelineStatus.Starting)
            val cacheDir = File(context.cacheDir, "render").apply { mkdirs() }

            // Encode audio first so a broken narration kills the job before rendering.
            PipelineBus.publish(PipelineStatus.EncodingAudio("Encoding narration…"))
            val pcmBytes = context.contentResolver.openInputStream(Uri.parse(job.audioUri))?.use { it.readBytes() }
                ?: error("could not read narration")
            val pcm = WavParser.parse(pcmBytes) ?: error("could not parse narration WAV")
            val musicPcm = job.musicUri.takeIf { it.isNotBlank() }?.let {
                AudioDecoder.decodeToMonoPcm(context, Uri.parse(it))
            }
            val (audioFormat, audioSamples) = AacEncoder().encode(pcm, musicPcm, job.musicGain)
                ?: error("AAC encoding failed on this device")

            // Determine which arcs still need rendering (resume).
            val completed = job.completedArcs.toMutableSet()
            arcs.indices.forEach { i ->
                val file = arcFile(cacheDir, i)
                if (i in completed && file.exists() && ArcQa.firstFrameIsBlack(file).not()) {
                    return@forEach
                }
                completed.remove(i)
            }

            var arcsDone = 0
            var firstProfile: ArcProfile? = null
            var profileMismatch = false
            for ((index, arc) in arcs.withIndex()) {
                coroutineContext.ensureActive()
                if (index in completed) {
                    firstProfile = firstProfile ?: ArcQa.probe(arcFile(cacheDir, index))
                    arcsDone++
                    PipelineBus.publish(PipelineStatus.RenderingArc(arcsDone, arcs.size))
                    continue
                }
                PipelineBus.publish(PipelineStatus.RenderingArc(arcsDone, arcs.size))

                val arcFile = arcFile(cacheDir, index)
                var attempt = 0
                var ok = false
                while (attempt < 2 && !ok) {
                    attempt++
                    val arcTimeline = Timeline(arc.slots, arc.endMs)
                    val result = VideoRenderer(context).render(arcTimeline, null, arcFile) { }
                    if (result.isFailure) {
                        if (attempt == 2) error("arc $index render failed: ${result.exceptionOrNull()?.message}")
                        continue
                    }
                    val profile = ArcQa.probe(arcFile)
                    if (profile == null) error("arc $index produced no video track")
                    if (firstProfile == null) {
                        firstProfile = profile
                    } else if (!ArcQa.sameProfile(firstProfile, profile)) {
                        profileMismatch = true
                        break
                    }
                    if (ArcQa.firstFrameIsBlack(arcFile)) {
                        if (attempt == 2) error("arc $index rendered a black first frame twice")
                        arcFile.delete()
                        continue
                    }
                    ok = true
                }
                if (profileMismatch) break
                arcsDone++
                completed.add(index)
                persistJob(context, job, completed.sorted())
                PipelineBus.publish(PipelineStatus.RenderingArc(arcsDone, arcs.size))
            }

            // Build the final file: stream-copy concat, or single-pass fallback.
            val finalFile = File(cacheDir, "track_final.mp4")
            val needFallback = profileMismatch

            if (needFallback) {
                PipelineBus.publish(PipelineStatus.Exporting("Profiles differ — re-rendering in one pass…"))
                if (!singlePassFallback(context, timeline, job, finalFile)) {
                    error("arc profiles differed and single-pass fallback failed")
                }
            } else {
                PipelineBus.publish(PipelineStatus.Concatting)
                val missing = arcs.indices.filter { !arcFile(cacheDir, it).exists() }
                if (missing.isNotEmpty()) {
                    PipelineBus.publish(PipelineStatus.Exporting("Concat incomplete — re-rendering in one pass…"))
                    if (!singlePassFallback(context, timeline, job, finalFile)) {
                        error("arcs missing ($missing) and single-pass fallback failed")
                    }
                } else {
                    val concatResult = VideoConcat.concat(
                        arcFiles = arcs.indices.map { arcFile(cacheDir, it) },
                        audioFormat = audioFormat,
                        audioSamples = audioSamples,
                        out = finalFile,
                    )
                    if (concatResult.isFailure) {
                        // Fallback: single-pass render (slower, but robust).
                        PipelineBus.publish(PipelineStatus.Exporting("Concat failed — re-rendering in one pass…"))
                        if (!singlePassFallback(context, timeline, job, finalFile)) {
                            error("single-pass fallback failed")
                        }
                    }
                }
            }

            // Delete consumed arcs to free ~half the working space.
            arcs.indices.forEach { arcFile(cacheDir, it).delete() }

            PipelineBus.publish(PipelineStatus.Exporting("Saving to Movies/TRACK…"))
            val uri = MediaStoreExporter.exportMp4(context, finalFile, "TRACK_${System.currentTimeMillis()}.mp4")
            JobStore.clear(context)
            PipelineBus.publish(PipelineStatus.Finished(uri, finalFile.absolutePath))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PipelineBus.publish(PipelineStatus.Failed(e.message ?: e::class.simpleName ?: "render failed"))
        }
    }

    /** Removes temp render files after a user cancel. */
    fun cleanup() {
        File(context.cacheDir, "render").deleteRecursively()
    }

    private fun arcFile(cacheDir: File, index: Int) = File(cacheDir, "arc_%03d.mp4".format(index))

    private fun persistJob(context: android.content.Context, job: PersistedJob, completed: List<Int>) {
        JobStore.save(context, job.copy(completedArcs = completed))
    }

    private suspend fun singlePassFallback(
        context: android.content.Context,
        timeline: Timeline,
        job: PersistedJob,
        finalFile: File,
    ): Boolean {
        val single = File(context.cacheDir, "render/track_single.mp4")
        single.parentFile?.mkdirs()
        val result = VideoRenderer(context).render(timeline, Uri.parse(job.audioUri), single) { }
        val out = result.getOrElse { return false }
        out.copyTo(finalFile, overwrite = true)
        return true
    }
}
