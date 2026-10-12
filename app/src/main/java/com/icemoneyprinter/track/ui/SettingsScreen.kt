package com.icemoneyprinter.track.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(TrackPrefs.load(context)) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pacing (auto mode)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    StepperRow(
                        label = "Minimum time per image",
                        value = settings.minAutoSlotMs / 1000,
                        unit = "s",
                        range = 1..10,
                        onChange = { settings = settings.copy(minAutoSlotMs = it * 1000L) },
                    )
                    StepperRow(
                        label = "Maximum before an image is split",
                        value = settings.maxAutoSlotMs / 1000,
                        unit = "s",
                        range = 3..15,
                        onChange = { settings = settings.copy(maxAutoSlotMs = it * 1000L) },
                    )
                    StepperRow(
                        label = "Tap boundary minimum",
                        value = settings.explicitFloorMs,
                        unit = "ms",
                        range = 300..1500 step 100,
                        onChange = { settings = settings.copy(explicitFloorMs = it.toLong()) },
                    )
                    Text(
                        text = "Auto mode keeps images on screen between the min and max. " +
                            "Longer sentences get split across several images.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Quality preset", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.highQuality,
                            onClick = { settings = settings.copy(highQuality = true) },
                            label = { Text("1080p") },
                        )
                        FilterChip(
                            selected = !settings.highQuality,
                            onClick = { settings = settings.copy(highQuality = false) },
                            label = { Text("720p (cooler/faster)") },
                        )
                    }
                    Text(
                        text = "Rendering happens on-device in batches; 720p is easier on older phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Button(
                onClick = {
                    TrackPrefs.save(context, settings)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Long,
    unit: String,
    range: IntProgression,
    onChange: (Int) -> Unit,
) {
    val list = range.toList()
    val index = list.indexOf(value.toInt()).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { if (index > 0) onChange(list[index - 1]) },
            enabled = index > 0,
        ) { Text("−") }
        Text("$value $unit")
        TextButton(
            onClick = { if (index < list.lastIndex) onChange(list[index + 1]) },
            enabled = index < list.lastIndex,
        ) { Text("+") }
    }
}
