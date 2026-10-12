package com.icemoneyprinter.track.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val TrackNavy = Color(0xFF0B0E14)
private val TrackBlue = Color(0xFF7FD1FF)
private val TrackBlueDark = Color(0xFF2A6E8E)
private val TrackSurface = Color(0xFF151B26)
private val TrackBlueContainer = Color(0xFF1F3446)

private val DarkColors = darkColorScheme(
    primary = TrackBlue,
    onPrimary = TrackNavy,
    primaryContainer = TrackBlueContainer,
    onPrimaryContainer = TrackBlue,
    secondary = TrackBlue,
    background = TrackNavy,
    onBackground = Color(0xFFE6ECF5),
    surface = TrackSurface,
    onSurface = Color(0xFFE6ECF5),
)

private val LightColors = lightColorScheme(
    primary = TrackBlueDark,
    background = Color(0xFFF7FAFD),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun TRACKTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
