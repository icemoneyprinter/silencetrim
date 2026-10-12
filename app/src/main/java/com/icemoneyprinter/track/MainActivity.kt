package com.icemoneyprinter.track

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.icemoneyprinter.track.ui.TrackApp
import com.icemoneyprinter.track.ui.theme.TRACKTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TRACKTheme {
                TrackApp()
            }
        }
    }
}
