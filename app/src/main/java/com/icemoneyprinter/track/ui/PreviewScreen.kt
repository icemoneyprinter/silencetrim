package com.icemoneyprinter.track.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.icemoneyprinter.track.model.Slot

/**
 * Filmstrip preview of the mapped timeline: every slot as a thumbnail + its time
 * window (+ the sentence that covers it when the script is available). This is where
 * the user catches a wrong image↔sentence pairing before spending the render.
 */
@Composable
fun PreviewScreen(
    vm: TrackViewModel,
    slots: List<Slot>,
    onBuild: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val done = state.stage
    if (done is Stage.Exporting || done is Stage.Rendering || done is Stage.Failed || done is Stage.Done) {
        // Render-in-progress / result stays on this screen.
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back to tapping") }
            Spacer(Modifier.weight(1f))
            Text("${slots.size} slots", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(slots) { index, slot ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = Uri.parse(slot.imageUriString),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(80.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "#${index + 1}  ${fmt(slot.startMs)} → ${fmt(slot.endMs)}  (${slot.durationMs / 1000.0}s)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            val sentence = vm.sentenceText(vm.sentenceIndexAt(slot.startMs))
                            if (!sentence.isNullOrBlank()) {
                                Text(
                                    text = sentence,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2,
                                )
                            }
                        }
                        if (slot.explicit) {
                            Text(
                                text = "tap",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        when (done) {
            is Stage.Rendering -> {
                Text("Rendering video… ${done.percent}%")
                LinearProgressIndicator(
                    progress = { done.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is Stage.Exporting -> Text(done.message)
            is Stage.Done -> {
                Text("Done — saved to Movies/TRACK", fontWeight = FontWeight.Bold)
            }
            is Stage.Failed -> Text(done.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }

        Button(
            onClick = onBuild,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Build slideshow video") }
        Spacer(Modifier.height(4.dp))
    }
}
