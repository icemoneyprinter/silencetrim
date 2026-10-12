package com.icemoneyprinter.track.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.icemoneyprinter.track.data.NarrationInfo
import com.icemoneyprinter.track.ui.theme.TRACKTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: TrackViewModel,
    onGuidedTap: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::onFolderPicked) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "TRACK",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Narration slideshow builder — fully on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Button(
                onClick = { folderPicker.launch(null) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pick folder (images + narration WAV)") }

            if (state.needsNarrationPick) {
                AudioRolesSheet(
                    candidates = state.audioCandidates,
                    onPick = vm::pickNarration,
                    onDismiss = vm::dismissNarrationPick,
                )
            }

            if (state.imageCount > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Folder summary", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Images: ${state.imageCount}")
                        Text("Narration: ${state.narrationName ?: "—"}")
                        if (!state.narrationSupported) {
                            Text(
                                "Narration must be a WAV file (Gemini TTS output)",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.scriptPresent) {
                            Text("script.txt: found (tapping screen will show it)")
                        }
                        if (state.audioDurationMs > 0) {
                            Text("Narration length: ${state.audioDurationMs / 1000}s")
                            Text(
                                "Detected speech spans: ${state.structure?.spans?.size ?: 0}",
                            )
                            if (state.estimatedSegmentation) {
                                Text(
                                    "Note: little/no speech detected — segments estimated",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            // Mapping reuse: one-tap re-render of a previous guided session.
            if (state.mappingAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Previous tapping session found", fontWeight = FontWeight.Bold)
                        Text(
                            "Same folder and same narration — re-render instantly.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                if (!vm.reuseMapping()) vm.enterTapSession()
                                onGuidedTap()
                            }) { Text("Reuse → preview") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.enterTapSession(); onGuidedTap() }) {
                                Text("Start fresh")
                            }
                        }
                    }
                }
            }

            if (state.structure != null && state.narrationSupported) {
                Button(
                    onClick = { vm.enterTapSession(); onGuidedTap() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Guided tapping (recommended)") }

                Text(
                    text = "Listen to the narration once and tap each image at the moment it should start. " +
                        "Auto mode instead shows images in folder order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Button(
                    onClick = vm::runPipeline,
                    enabled = !state.busy && state.mode == RunMode.AUTO,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Quick build (auto order)") }
            }

            StageCard(state)

            if (state.stage is Stage.Done) {
                val outputUri = (state.stage as Stage.Done).outputUri
                Button(
                    onClick = {
                        outputUri?.let { uri ->
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Play video"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open finished video") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRolesSheet(
    candidates: List<NarrationInfo>,
    onPick: (NarrationInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("Which audio is the narration?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            candidates.forEach { info ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                    ) {
                        Column {
                            Text(info.displayName)
                            Text(
                                text = if (info.isWav) "WAV — supported" else "not WAV yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Button(
                            onClick = { onPick(info) },
                            enabled = info.isWav,
                        ) { Text("Pick") }
                    }
                }
            }
            Text(
                text = "Extra audio files are ignored for now (music-bed mixing lands later).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun StageCard(state: TrackUiState) {
    val stage = state.stage
    when (stage) {
        is Stage.ScanningImages -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Scanning images… ${stage.scanned}/${stage.total}")
                    LinearProgressIndicator(
                        progress = {
                            if (stage.total > 0) stage.scanned.toFloat() / stage.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is Stage.AnalyzingAudio -> Text(stage.message)
        is Stage.Planning -> Text(stage.message)
        is Stage.Rendering -> Text("Rendering video… ${stage.percent}%")
        is Stage.Exporting -> Text(stage.message)
        is Stage.Done -> Text("Done — saved to Movies/TRACK", fontWeight = FontWeight.Bold)
        is Stage.Failed -> Text(stage.message, color = MaterialTheme.colorScheme.error)
        Stage.Idle -> Unit
    }
}
