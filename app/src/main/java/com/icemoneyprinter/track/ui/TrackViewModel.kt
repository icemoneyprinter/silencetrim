package com.icemoneyprinter.track.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.icemoneyprinter.track.audio.EnergyAnalyzer
import com.icemoneyprinter.track.audio.ScriptAligner
import com.icemoneyprinter.track.audio.ScriptLoader
import com.icemoneyprinter.track.audio.WavParser
import com.icemoneyprinter.track.audio.WordsData
import com.icemoneyprinter.track.audio.WordsLoader
import com.icemoneyprinter.track.data.FolderScanner
import com.icemoneyprinter.track.data.JobStore
import com.icemoneyprinter.track.data.MappingStore
import com.icemoneyprinter.track.data.NarrationInfo
import com.icemoneyprinter.track.data.PersistedJob
import com.icemoneyprinter.track.data.SavedMapping
import com.icemoneyprinter.track.data.ScanResult
import com.icemoneyprinter.track.data.SampleProject
import com.icemoneyprinter.track.data.UriStreams
import com.icemoneyprinter.track.model.AudioStructure
import com.icemoneyprinter.track.model.ImageItem
import com.icemoneyprinter.track.model.PacingConfig
import com.icemoneyprinter.track.model.Timeline
import com.icemoneyprinter.track.pacing.TapAssigner
import com.icemoneyprinter.track.pacing.TapAssignment
import com.icemoneyprinter.track.pacing.TapRecord
import com.icemoneyprinter.track.pacing.TimelineBuilder
import com.icemoneyprinter.track.render.FreeSpace
import com.icemoneyprinter.track.render.PipelineBus
import com.icemoneyprinter.track.render.PipelineStatus
import com.icemoneyprinter.track.render.RenderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RunMode { AUTO, TAPPED }

/** Pipeline stages surfaced to the UI. */
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
    val busy: Boolean = false,
    // folder summary
    val imageCount: Int = 0,
    val narrationName: String? = null,
    val narrationSupported: Boolean = true,
    val scriptPresent: Boolean = false,
    val audioDurationMs: Long = 0,
    // analysis
    val structure: AudioStructure? = null,
    val pool: List<ImageItem> = emptyList(),
    val sentencePerSpan: List<String> = emptyList(),
    val words: WordsData? = null,
    val estimatedSegmentation: Boolean = false,
    // tap session
    val mode: RunMode = RunMode.AUTO,
    val taps: List<TapRecord> = emptyList(),
    val tapAssignment: TapAssignment? = null,
    val droppedTaps: Int = 0,
    // mapping reuse
    val mappingAvailable: Boolean = false,
    // audio roles
    val audioCandidates: List<NarrationInfo> = emptyList(),
    val musicCandidates: List<NarrationInfo> = emptyList(),
    val music: String = "",
    val narrationUri: Uri? = null,
    val needsNarrationPick: Boolean = false,
    val needsMusicPick: Boolean = false,
    // export
    val outputUri: Uri? = null,
    val lastTimeline: Timeline? = null,
)

/** Orchestrates ingest → analysis → (auto | tapped) mapping → render → export (M2). */
class TrackViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TrackUiState())
    val state: StateFlow<TrackUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var folderUri: Uri? = null
    private var narration: NarrationInfo? = null
    private var audioHash: String = ""
    private var folderKey: String? = null

    fun onFolderPicked(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folderUri = uri
        folderKey = MappingStore.folderKey(uri.toString())
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = Stage.ScanningImages(0, 0))
            val scan: ScanResult = FolderScanner.scan(context, uri) { scanned, total ->
                _state.value = _state.value.copy(stage = Stage.ScanningImages(scanned, total))
            }
            narration = scan.narration
            val remaining = scan.audioCandidates.filter { it.uri != scan.narration?.uri }
            _state.value = _state.value.copy(
                busy = false,
                stage = Stage.Idle,
                imageCount = scan.images.size,
                pool = scan.images,
                narrationName = scan.narration?.displayName,
                narrationSupported = scan.narration?.isWav == true,
                scriptPresent = scan.scriptPresent,
                audioCandidates = scan.audioCandidates,
                narrationUri = scan.narration?.uri,
                musicCandidates = remaining,
                music = "",
                needsNarrationPick = scan.audioCandidates.size > 1,
                taps = emptyList(),
                tapAssignment = null,
                mode = RunMode.AUTO,
                lastTimeline = null,
            )
            if (scan.narration != null && !(scan.audioCandidates.size > 1)) {
                nar(scan.narration)
            }
        }
    }

    /** User picked which audio file is the narration (roles sheet, >1 audio). */
    fun pickNarration(info: NarrationInfo) {
        narration = info
        val remaining = _state.value.audioCandidates.filter { it.uri != info.uri }
        _state.value = _state.value.copy(
            needsNarrationPick = false,
            narrationName = info.displayName,
            narrationSupported = info.isWav,
            narrationUri = info.uri,
            musicCandidates = remaining,
            needsMusicPick = remaining.isNotEmpty(),
        )
        if (info.isWav) nar(info)
    }

    /** Optional music bed: mixed under the narration at -35% level. */
    fun pickMusic(info: NarrationInfo?) {
        _state.value = _state.value.copy(
            music = info?.uri?.toString() ?: "",
            needsMusicPick = false,
            musicCandidates = emptyList(),
        )
    }

    fun dismissNarrationPick() {
        _state.value = _state.value.copy(needsNarrationPick = false)
    }

    /** Loads the bundled sample project (assets → cache) and runs analysis on it. */
    fun useSample() {
        val context = getApplication<Application>()
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = Stage.ScanningImages(0, 0))
            try {
                val dir = withContext(Dispatchers.IO) { SampleProject.ensure(context) }
                val scan = FolderScanner.scanLocal(dir) { scanned, total ->
                    _state.value = _state.value.copy(stage = Stage.ScanningImages(scanned, total))
                }
                narration = scan.narration
                folderUri = null
                folderKey = null
                _state.value = _state.value.copy(
                    busy = false,
                    stage = Stage.Idle,
                    imageCount = scan.images.size,
                    pool = scan.images,
                    narrationName = scan.narration?.displayName,
                    narrationSupported = scan.narration != null,
                    scriptPresent = scan.scriptPresent,
                    audioCandidates = scan.audioCandidates,
                    narrationUri = scan.narration?.uri,
                    musicCandidates = emptyList(),
                    music = "",
                    mode = RunMode.AUTO,
                )
                if (scan.narration != null) nar(scan.narration)
            } catch (e: Exception) {
                fail("Sample project failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun nar(info: NarrationInfo) {
        val context = getApplication<Application>()
        val treeUri = folderUri ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = Stage.AnalyzingAudio("Reading narration…"))
            try {
                val bytes = withContext(Dispatchers.IO) {
                    UriStreams.open(context, info.uri)!!.use { it.readBytes() }
                }
                audioHash = md5(bytes)
                val pcm = WavParser.parse(bytes)
                if (pcm == null) {
                    fail("Could not parse narration as a WAV file")
                    return@launch
                }
                val structure = EnergyAnalyzer().analyze(pcm)
                val localDir = if (folderUri == null && info.uri.scheme == "file") {
                    info.uri.path?.let { java.io.File(it).parentFile }
                } else {
                    null
                }
                val sentences = folderUri?.let { ScriptLoader.load(context, it) } ?: ScriptLoader.loadDir(localDir)
                val perSpan = sentences?.let { ScriptAligner.align(it, structure.spans) } ?: emptyList()
                val words = folderUri?.let { WordsLoader.load(context, it) } ?: WordsLoader.loadDir(localDir)

                val mapping = folderKey?.let { MappingStore.load(context, it) }
                val pool = _state.value.pool
                val mappingValid = mapping != null &&
                    mapping.audioHash == audioHash &&
                    mapping.taps.isNotEmpty() &&
                    pool.map { it.contentHash }.containsAll(mapping.taps.map { it.first })

                _state.value = _state.value.copy(
                    busy = false,
                    stage = Stage.Idle,
                    structure = structure,
                    audioDurationMs = structure.durationMs,
                    sentencePerSpan = perSpan,
                    words = words,
                    estimatedSegmentation = structure.estimatedSegmentation,
                    mappingAvailable = mappingValid,
                )
            } catch (e: Exception) {
                fail("Narration analysis failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Reuse a saved mapping: seed the tap session and skip to preview. */
    fun reuseMapping(): Boolean {
        val context = getApplication<Application>()
        val key = folderKey ?: return false
        val mapping = MappingStore.load(context, key) ?: return false
        val pool = _state.value.pool
        val uriByHash = pool.associate { it.contentHash to it.uriString }
        val taps = mapping.taps.mapNotNull { (hash, ms) ->
            uriByHash[hash]?.let { TapRecord(it, hash, ms) }
        }
        if (taps.isEmpty()) return false
        _state.value = _state.value.copy(
            mode = RunMode.TAPPED,
            taps = taps,
            mappingAvailable = false,
        )
        refreshTapAssignment()
        return true
    }

    fun enterTapSession() {
        if (_state.value.structure == null) return
        _state.value = _state.value.copy(
            mode = RunMode.TAPPED,
            taps = emptyList(),
            tapAssignment = null,
        )
    }

    fun addTap(item: ImageItem, tapMs: Long) {
        if (_state.value.mode != RunMode.TAPPED) return
        val taps = _state.value.taps + TapRecord(item.uriString, item.contentHash, tapMs)
        _state.value = _state.value.copy(taps = taps)
    }

    fun undoTap() {
        val taps = _state.value.taps
        if (taps.isEmpty()) return
        _state.value = _state.value.copy(taps = taps.dropLast(1))
    }

    fun clearTaps() {
        _state.value = _state.value.copy(taps = emptyList(), tapAssignment = null)
    }

    /** Recompute the assignment from taps + current structure (fresh pauses each time). */
    fun refreshTapAssignment() {
        val structure = _state.value.structure ?: return
        val assignment = TapAssigner().assignFromTaps(_state.value.taps, structure, _state.value.words)
        _state.value = _state.value.copy(tapAssignment = assignment, droppedTaps = assignment.droppedTapCount)
    }

    fun finishTapSession(): Boolean {
        refreshTapAssignment()
        return _state.value.tapAssignment?.timeline?.slots?.isNotEmpty() == true
    }

    /** Which script sentence covers the given playback position (tap screen highlight). */
    fun sentenceIndexAt(positionMs: Long): Int {
        val structure = _state.value.structure ?: return 0
        val spanIndex = structure.spans.indexOfFirst { positionMs < it.endMs }
        return if (spanIndex >= 0) spanIndex else structure.spans.lastIndex.coerceAtLeast(0)
    }

    fun sentenceText(spanIndex: Int): String? =
        _state.value.sentencePerSpan.getOrNull(spanIndex)

    /** The word currently being spoken, when words.json timings are loaded. */
    fun wordAt(positionMs: Long): String? {
        val words = _state.value.words ?: return null
        val idx = words.wordIndexAt(positionMs)
        return if (idx >= 0) words.words[idx].word else null
    }

    fun runPipeline() {
        val context = getApplication<Application>()
        val uri = folderUri ?: return
        val info = narration ?: return
        viewModelScope.launch {
            try {
                val pool = _state.value.pool
                if (pool.isEmpty()) fail("No images found in the folder")

                val settings = TrackPrefs.load(context)
                val timeline: Timeline
                val tappedMode: Boolean
                when (_state.value.mode) {
                    RunMode.AUTO -> {
                        val structure = _state.value.structure
                            ?: fail("Narration not analyzed yet — re-pick the folder or pick the narration file")
                        timeline = withContext(Dispatchers.Default) {
                            TimelineBuilder().buildAuto(pool, structure, settings.pac)
                        }
                        tappedMode = false
                    }
                    RunMode.TAPPED -> {
                        val assignment = _state.value.tapAssignment
                            ?: fail("Guided tap session not finished — tap images or reuse the saved mapping")
                        timeline = assignment.timeline
                        tappedMode = true
                    }
                }
                _state.value = _state.value.copy(lastTimeline = timeline)

                // Persist mapping for one-tap re-render (tapped mode only).
                if (_state.value.mode == RunMode.TAPPED && _state.value.taps.isNotEmpty()) {
                    val key = folderKey
                    if (key != null) {
                        MappingStore.save(
                            context,
                            SavedMapping(
                                folderKey = key,
                                audioHash = audioHash,
                                audioDurationMs = timeline.audioDurationMs,
                                taps = _state.value.taps.map { it.imageHash to it.tapMs },
                            ),
                        )
                    }
                }

                // Persist the job and hand rendering to the foreground service (plan D9).
                JobStore.save(
                    context,
                    PersistedJob(
                        folderUri = uri.toString(),
                        audioUri = info.uri.toString(),
                        narrationName = info.displayName,
                        audioDurationMs = timeline.audioDurationMs,
                        tappedMode = tappedMode,
                        slots = timeline.slots,
                        completedArcs = emptyList(),
                        musicUri = _state.value.music,
                        musicGain = settings.musicGain,
                    ),
                )
                RenderService.start(context)
                _state.value = _state.value.copy(busy = true, stage = Stage.Rendering(0))
            } catch (e: PipelineCancelled) {
                // recorded by fail()
            } catch (e: Exception) {
                fail("Unexpected error: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Maps the service pipeline's status bus onto the UI stage state. */
    fun observePipeline() {
        viewModelScope.launch {
            PipelineBus.status.collect { status ->
                when (status) {
                    is PipelineStatus.Starting ->
                        _state.value = _state.value.copy(busy = true, stage = Stage.Rendering(0))
                    is PipelineStatus.RenderingArc ->
                        _state.value = _state.value.copy(
                            busy = true,
                            stage = Stage.Rendering(
                                if (status.total > 0) status.done * 100 / status.total else 0,
                            ),
                        )
                    is PipelineStatus.EncodingAudio ->
                        _state.value = _state.value.copy(busy = true, stage = Stage.Planning(status.message))
                    is PipelineStatus.Concatting ->
                        _state.value = _state.value.copy(busy = true, stage = Stage.Exporting("Stitching arcs…"))
                    is PipelineStatus.Exporting ->
                        _state.value = _state.value.copy(busy = true, stage = Stage.Exporting(status.message))
                    is PipelineStatus.Finished ->
                        _state.value = _state.value.copy(
                            busy = false,
                            stage = Stage.Done(status.outputUri, status.outputPath),
                        )
                    is PipelineStatus.Failed ->
                        _state.value = _state.value.copy(busy = false, stage = Stage.Failed(status.message))
                    is PipelineStatus.Cancelled ->
                        _state.value = _state.value.copy(busy = false, stage = Stage.Failed("Render cancelled"))
                    null -> Unit
                }
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

    private fun md5(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class PipelineCancelled(message: String) : RuntimeException(message)
}
