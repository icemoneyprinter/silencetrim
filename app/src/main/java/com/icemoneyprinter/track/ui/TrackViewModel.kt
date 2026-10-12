package com.icemoneyprinter.track.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icemoneyprinter.track.audio.EnergyAnalyzer
import com.icemoneyprinter.track.audio.WavParser
import com.icemoneyprinter.track.data.FolderScanner
import com.icemoneyprinter.track.data.ScanResult
import com.icemoneyprinter.track.model.PacingConfig
import com.icemoneyprinter.track.model.Timeline
import com.icemoneyprinter.track.pacing.TimelineBuilder
import com.icemoneyprinter.track.render.FreeSpace
import com.icemoneyprinter.track.render.MediaStoreExporter
import com.icemoneyprinter.track.render.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pipeline stages surfaced to the UI (M1: single-pass, no arcs yet). */
sealed interface Stage {
    data object Idle : Stage
    data class ScanningImages(val scanned: Int, val total: Int) : Stage
    data class AnalyzingAudio(val message: String) : Stage
    data class Planning(val message: String) : Stage
    data class Rendering(val percent: Int) : Stage
    data class Exporting(val message: String) : Stage
    data class Done(val outputUri: Uri?, val outputPath: String) : Stage
    data class Failed(val message: String) : Stage
}

data class TrackUiState(
    val stage: Stage = Stage.Idle,
    val imageCount: Int = 0,
    val narrationName: String? = null,
    val narrationSupported: Boolean = true,
    val scriptPresent: Boolean = false,
    val audioDurationMs: Long = 0,
    val slotCount: Int = 0,
    val estimatedSegmentation: Boolean = false,
    val busy: Boolean = false,
)

/** Orchestrates the M1 pipeline; the service-based/resumable version lands in M3. */
class TrackViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TrackUiState())
    val state: StateFlow<TrackUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var folderUri: Uri? = null
    private var lastTimeline: Timeline? = null

    fun onFolderPicked(uri: Uri) {
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        folderUri = uri
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = Stage.ScanningImages(0, 0))
            val scan: ScanResult = FolderScanner.scan(
                context,
                uri,
                onProgress = { scanned, total ->
                    _state.value = _state.value.copy(stage = Stage.ScanningImages(scanned, total))
                },
            )
            _state.value = _state.value.copy(
                busy = false,
                stage = Stage.Idle,
                imageCount = scan.images.size,
                narrationName = scan.narration?.displayName,
                narrationSupported = scan.narration?.isWav == true,
                scriptPresent = scan.scriptPresent,
            )
        }
    }

    fun runPipeline() {
        val context = getApplication<Application>()
        val uri = folderUri ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val scan = FolderScanner.scan(context, uri) { _, _ -> }
                if (scan.images.isEmpty()) fail("No images found in the folder")
                val narration = scan.narration ?: fail("No narration audio found in the folder")
                if (!narration.isWav) fail("Narration must be a WAV file (Gemini TTS output)")

                _state.value = _state.value.copy(stage = Stage.AnalyzingAudio("Reading narration…"))
                val pcm = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(narration.uri)!!.use { it.readBytes() }
                    WavParser.parse(bytes)
                } ?: fail("Could not parse the narration WAV")

                val structure = withContext(Dispatchers.Default) {
                    EnergyAnalyzer().analyze(pcm)
                }

                _state.value = _state.value.copy(stage = Stage.Planning("Building timeline…"))
                val timeline = withContext(Dispatchers.Default) {
                    TimelineBuilder().buildAuto(scan.images, structure, PacingConfig())
                }
                lastTimeline = timeline

                val cacheDir = File(context.cacheDir, "render").apply { mkdirs() }
                val outputFile = File(cacheDir, "track_render.mp4")
                val needed = FreeSpace.estimatedOutputBytes(timeline.durationMs)
                val available = FreeSpace.availableBytes(cacheDir)
                if (available < needed) {
                    fail("Not enough disk space: need ~${needed / 1_000_000}MB, have ${available / 1_000_000}MB")
                }

                _state.value = _state.value.copy(
                    stage = Stage.Rendering(0),
                    audioDurationMs = structure.durationMs,
                    slotCount = timeline.slots.size,
                    estimatedSegmentation = structure.estimatedSegmentation,
                )
                val result = VideoRenderer(context).render(timeline, narration.uri, outputFile) { pct ->
                    _state.value = _state.value.copy(stage = Stage.Rendering(pct))
                }
                val rendered = result.getOrElse { fail("Render failed: ${it.message ?: it::class.simpleName}") }

                _state.value = _state.value.copy(stage = Stage.Exporting("Saving to Movies/TRACK…"))
                val exportedUri = withContext(Dispatchers.IO) {
                    MediaStoreExporter.exportMp4(
                        context,
                        rendered,
                        "TRACK_${System.currentTimeMillis()}.mp4",
                    )
                }
                _state.value = _state.value.copy(
                    busy = false,
                    stage = Stage.Done(exportedUri, rendered.absolutePath),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PipelineCancelled) {
                // stage already recorded by fail()
            } catch (e: Exception) {
                fail("Unexpected error: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun clearError() {
        if (_state.value.stage is Stage.Failed) {
            _state.value = _state.value.copy(busy = false, stage = Stage.Idle)
        }
    }

    private fun fail(message: String): Nothing {
        _state.value = _state.value.copy(busy = false, stage = Stage.Failed(message))
        throw PipelineCancelled(message)
    }

    private class PipelineCancelled(message: String) : RuntimeException(message)
}
