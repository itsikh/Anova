package com.template.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.template.app.bugreport.ScreenshotHolder
import com.template.app.ui.components.DebugOverlayViewModel
import com.template.app.ui.components.FloatingBugButton
import com.template.app.ui.screens.bugreport.BugReportScreen
import com.template.app.ui.screens.bugreport.ReportMode
import com.template.app.ui.screens.history.HistoryScreen
import com.template.app.ui.screens.monitor.MonitorScreen
import com.template.app.ui.screens.schedule.ScheduleScreen
import com.template.app.ui.screens.settings.SettingsScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val overlayVm: DebugOverlayViewModel = hiltViewModel()
    val showBugButton by overlayVm.showBugButton.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "monitor") {
            composable("monitor") {
                MonitorScreen(
                    onOpenSettings  = { navController.navigate("settings") },
                    onOpenHistory   = { navController.navigate("history") },
                    onOpenSchedule  = { navController.navigate("schedule") }
                )
            }
            composable("history") {
                HistoryScreen(onBack = { navController.popBackStack() })
            }
            composable("schedule") {
                ScheduleScreen(onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBugReport = { mode -> navController.navigate("bug_report/${mode.name}") }
                )
            }
            composable("bug_report/{mode}") { backStackEntry ->
                val mode = backStackEntry.arguments?.getString("mode")
                    ?.let { runCatching { ReportMode.valueOf(it) }.getOrNull() }
                    ?: ReportMode.BUG_REPORT
                BugReportScreen(mode = mode, onBack = { navController.popBackStack() })
            }
        }
        FloatingBugButton(
            visible = showBugButton,
            onScreenshotCaptured = { bitmap ->
                ScreenshotHolder.store(bitmap)
                navController.navigate("bug_report/BUG_REPORT")
            }
        )
    }
}
