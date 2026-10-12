package com.icemoneyprinter.track.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.icemoneyprinter.track.model.ImageItem
import kotlinx.coroutines.delay

/**
 * Guided tapping session (the product's core surface).
 *
 * The narration plays; the user taps each image at the moment it should start. The tap
 * time is corrected for reaction lag (-250ms) and snapped to a nearby pause edge, so
 * sloppy taps land on real boundaries.
 */
@Composable
fun TapSessionScreen(
    vm: TrackViewModel,
    pool: List<ImageItem>,
    narrationUri: Uri?,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalView.current.context
    val view = LocalView.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            narrationUri?.let {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    val durationMs = state.audioDurationMs.coerceAtLeast(1L)

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            player.release()
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition
            delay(200)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("Guided tapping", style = MaterialTheme.typography.titleMedium)
        }

        // Current word highlight when words.json timings are available, else the
        // sentence aligned to the current detected span.
        val currentWord = vm.wordAt(positionMs)
        if (currentWord != null) {
            Text(
                text = "▶ $currentWord",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = vm.sentenceText(vm.sentenceIndexAt(positionMs)) ?: " ",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(64.dp),
        )

        // Transport
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                playing = !playing
                if (playing) player.play() else player.pause()
            }) {
                Text(if (playing) "❚❚ Pause" else "▶ Play")
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = {
                player.seekTo(maxOf(0, player.currentPosition - 5_000))
                positionMs = player.currentPosition
            }) { Text("-5s") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${fmt(positionMs)} / ${fmt(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { positionMs.toFloat() / durationMs.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Tapped ${state.taps.size}/${pool.size} — tap an image at the moment it should start. Taps auto-correct to the nearest pause.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Thumbnail strip
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pool, key = { it.contentHash }) { item ->
                val tapOrdinal = state.taps.indexOfFirst { it.imageHash == item.contentHash }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        AsyncImage(
                            model = Uri.parse(item.uriString),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .alpha(if (tapOrdinal >= 0) 0.45f else 1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        if (tapOrdinal >= 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Text(
                                    text = "${tapOrdinal + 1}",
                                    color = Color.Black,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        vm.addTap(item, player.currentPosition)
                    }) {
                        Text(if (tapOrdinal >= 0) "Tap ✓" else "Tap")
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::undoTap, enabled = state.taps.isNotEmpty()) { Text("Undo") }
            TextButton(onClick = vm::clearTaps, enabled = state.taps.isNotEmpty()) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (vm.finishTapSession()) onPreview()
                },
                enabled = state.taps.isNotEmpty(),
            ) { Text("Preview mapping") }
        }

        if (state.droppedTaps > 0) {
            Text(
                text = "${state.droppedTaps} tap(s) were too close together and dropped (tap again further apart)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
