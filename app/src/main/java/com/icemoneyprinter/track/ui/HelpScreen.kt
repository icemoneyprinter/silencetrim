package com.icemoneyprinter.track.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * In-app help (plan M4): the questions that actually come up. Fully offline,
 * static content — no webviews, no network.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
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
                Text("How TRACK works", style = MaterialTheme.typography.titleLarge)
            }

            HelpCard(
                "What do I put in the folder?",
                "Put your images and your narration WAV in one folder. Any names, any order, " +
                    "any sizes — nothing needs renaming. The app finds the audio by itself " +
                    "(if there are several, it asks). Optionally drop in script.txt (for the " +
                    "scrolling script) or words.json (word timestamps for perfect snapping).",
            )
            HelpCard(
                "Which way should I build?",
                "Quick build (auto): images show in folder order — good when you collected them " +
                    "in script order. Guided tapping: listen to the narration once and tap each " +
                    "image when it should start — best quality. Post-render, tapping sessions are " +
                    "saved so you can re-render the same video in one tap.",
            )
            HelpCard(
                "How does tapping work?",
                "Play the narration. When the moment for the next image arrives, tap that image " +
                    "in the strip. Your tap is corrected for reaction time and snapped to the " +
                    "nearest natural pause, so rough taps land on clean cuts. Tap mid-sentence " +
                    "to split a sentence across two images. Undo removes the last tap.",
            )
            HelpCard(
                "How long does it take?",
                "Analysis is seconds; guided tapping takes as long as your narration (you listen " +
                    "once); rendering runs in the background as batches of 45–90 seconds with a " +
                    "progress notification you can watch. Expect roughly the length of the video " +
                    "itself, longer on older phones. You can lock the screen — the render " +
                    "continues and resumes if interrupted.",
            )
            HelpCard(
                "Something looks wrong",
                "Images in the wrong place? Re-run guided tapping or reuse a previous session. " +
                    "Video longer than the audio or ending early? The last image is held to the " +
                    "narration's end — check the narration file is complete. Render keeps failing " +
                    "with a storage message? Free space; the finished video is saved to " +
                    "Movies/TRACK in your gallery.",
            )
            HelpCard(
                "Your files stay yours",
                "Everything happens on this phone. The app never uploads anything, never writes " +
                    "into your folder, and never deletes your images or audio.",
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
