package com.icemoneyprinter.track

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.icemoneyprinter.track.ui.HelpScreen
import com.icemoneyprinter.track.ui.HomeScreen
import com.icemoneyprinter.track.ui.PreviewScreen
import com.icemoneyprinter.track.ui.SettingsScreen
import com.icemoneyprinter.track.ui.TapSessionScreen
import com.icemoneyprinter.track.ui.TrackViewModel
import com.icemoneyprinter.track.ui.theme.TRACKTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: TrackViewModel = viewModel()
            val state by vm.state.collectAsState()
            val navController = rememberNavController()

            // Pick up service pipeline status (render progress, done, failures).
            DisposableEffect(vm) {
                vm.observePipeline()
                onDispose { }
            }

            TRACKTheme {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = vm,
                            onGuidedTap = { navController.navigate("tap") },
                            onSettings = { navController.navigate("settings") },
                            onHelp = { navController.navigate("help") },
                        )
                    }
                    composable("tap") {
                        TapSessionScreen(
                            vm = vm,
                            pool = state.pool,
                            narrationUri = state.narrationUri,
                            onPreview = { navController.navigate("preview") },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("preview") {
                        PreviewScreen(
                            vm = vm,
                            slots = state.tapAssignment?.timeline?.slots ?: emptyList(),
                            onBuild = vm::runPipeline,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("help") {
                        HelpScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
