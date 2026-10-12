package com.icemoneyprinter.track.render

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.icemoneyprinter.track.model.Timeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Frame rate for the still-image output. */
const val OUTPUT_FRAME_RATE = 30

/**
 * M1 renderer: single-pass Media3 Transformer export of an image sequence + narration.
 *
 * Deferred to M3 on purpose: fixed bitrate/encoder settings (needed for arc concat
 * equivalence) and per-arc rendering. M1's gate is "a real MP4 comes out of the
 * phone", so this uses stock H.264/AAC mime selection with default encoder settings.
 */
class VideoRenderer(private val context: Context) {

    suspend fun render(
        timeline: Timeline,
        audioUri: Uri?,
        outputFile: File,
        onProgress: (percent: Int) -> Unit,
    ): Result<File> {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        return coroutineScope {
            val done = CompletableDeferred<Result<File>>()
            val holder = ProgressHolder()
            var lastReported = -1
            var transformer: Transformer? = null

            try {
                val imageItems = timeline.slots.map { slot ->
                    EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.parse(slot.imageUriString)),
                    )
                        .setDurationUs(slot.durationMs * 1000L)
                        .setFrameRate(OUTPUT_FRAME_RATE)
                        .build()
                }
                // Arcs are video-only (audio is muxed once in the final pass, plan D8).
                // audioUri == null renders a silent video-only segment.
                val sequences = buildList {
                    add(EditedMediaItemSequence(imageItems))
                    audioUri?.let {
                        add(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(it)).build())))
                    }
                }
                val composition = Composition.Builder(
                    sequences.first(),
                    *sequences.drop(1).toTypedArray(),
                ).build()

                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()
                transformer = t

                t.addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Result.success(outputFile))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            done.complete(Result.failure(exportException))
                        }
                    },
                )

                t.start(composition, outputFile.absolutePath)

                // Progress poller on this scope; dies with the caller.
                launch(Dispatchers.Default) {
                    while (isActive && !done.isCompleted) {
                        try {
                            val state = t.getProgress(holder)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                val pct = holder.progress
                                if (pct > lastReported) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        } catch (_: IllegalStateException) {
                            // not started yet
                        }
                        delay(300)
                    }
                }
            } catch (e: Exception) {
                done.complete(Result.failure(e))
            }

            val result = done.await()
            if (result.isFailure) {
                runCatching { transformer?.cancel() }
                outputFile.delete()
            }
            result
        }
    }
}
