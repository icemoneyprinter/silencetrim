package com.icemoneyprinter.track.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.icemoneyprinter.track.ui.theme.TRACKTheme

@Composable
fun TrackApp(vm: TrackViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
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
                    Text("script.txt: ${if (state.scriptPresent) "found" else "not present (optional)"}")
                    if (state.audioDurationMs > 0) {
                        Text("Narration length: ${state.audioDurationMs / 1000}s")
                        Text("Planned slots: ${state.slotCount}")
                        if (state.estimatedSegmentation) {
                            Text(
                                "Note: little/no speech detected — segmentation estimated",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = vm::runPipeline,
                enabled = !state.busy && state.imageCount > 0 && state.narrationSupported,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Build slideshow video") }

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

@Composable
private fun StageCard(state: TrackUiState) {
    val stage = state.stage
    if (stage is Stage.Idle) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (stage) {
                is Stage.ScanningImages -> {
                    Text("Scanning images… ${stage.scanned}/${stage.total}")
                    LinearProgressIndicator(
                        progress = {
                            if (stage.total > 0) stage.scanned.toFloat() / stage.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is Stage.AnalyzingAudio -> Text(stage.message)
                is Stage.Planning -> Text(stage.message)
                is Stage.Rendering -> {
                    Text("Rendering video… ${stage.percent}%")
                    LinearProgressIndicator(
                        progress = { stage.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is Stage.Exporting -> Text(stage.message)
                is Stage.Done -> Text("Done — saved to Movies/TRACK", fontWeight = FontWeight.Bold)
                is Stage.Failed -> Text(stage.message, color = MaterialTheme.colorScheme.error)
                Stage.Idle -> Unit
            }
        }
    }
}

@Composable
fun TrackAppPreview() {
    TRACKTheme(dynamicColor = false) {
        TrackApp()
    }
}
