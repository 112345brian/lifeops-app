package com.lifeops.briefing.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.lifeops.briefing.data.BriefingState

/**
 * The app's new single-Activity entry point, replacing SettingsActivity as
 * the launcher (see AndroidManifest.xml) and PanelActivity's WebView as the
 * primary home surface -- native Compose screens hosted by one Compose
 * Navigation graph, per this task's brief ("a single Activity + Compose
 * Navigation... rather than one Activity per screen"). PanelActivity itself
 * is kept, unmodified, as the secondary "web panel" fallback reachable from
 * [SystemScreen] -- see that file and PanelActivity.kt's kdoc.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeOpsTheme {
                LifeOpsApp()
            }
        }
    }
}

private enum class TopLevelDestination(val route: String, val label: String) {
    TODAY("today", "Today"),
    ATTENTION("attention", "Attention"),
    ROUTINES("routines", "Recurring"),
    HISTORY("history", "History"),
    SYSTEM("system", "System"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LifeOpsApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { LifeOpsBottomBar(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.TODAY.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.TODAY.route) {
                TodayScreen()
            }
            composable(TopLevelDestination.ATTENTION.route) {
                // Reuses the same on-disk BriefingState the Today screen
                // reads (see TodayRepository) -- one load per screen visit
                // is cheap (a single SharedPreferences/DataStore read), so
                // no shared ViewModel/ok to keep these two screens
                // independent rather than wiring cross-screen state.
                var briefing by remember { mutableStateOf(BriefingState.empty()) }
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    briefing = TodayRepository.load(context).briefing
                }
                AttentionScreen(briefing = briefing)
            }
            composable(TopLevelDestination.ROUTINES.route) {
                RoutineListScreen(
                    onOpenRoutine = { id -> navController.navigate("routines/edit/$id") },
                    onNewRoutine = { navController.navigate("routines/edit/new") },
                )
            }
            composable(
                route = "routines/edit/{routineId}",
                arguments = listOf(navArgument("routineId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val arg = backStackEntry.arguments?.getString("routineId")
                RoutineEditScreen(
                    routineId = arg?.takeIf { it != "new" },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(TopLevelDestination.HISTORY.route) {
                HistoryScreen()
            }
            composable(TopLevelDestination.SYSTEM.route) {
                SystemScreen()
            }
        }
    }
}

@Composable
private fun LifeOpsBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Text(text = badgeGlyphFor(destination)) },
                label = { Text(destination.label) },
            )
        }
    }
}

/** Deliberately a plain text glyph per tab, not a Material icon-set
 * dependency this app doesn't otherwise pull in (material3 alone does not
 * transitively bring in `material-icons-core`) -- matches the widget's own
 * deterministic status-glyph convention (docs/lifeops_capability_todo.md:
 * "prefer symbolic/visual state... keep symbols deterministic") rather than
 * adding a whole icon-set artifact for five nav-bar icons. */
private fun badgeGlyphFor(destination: TopLevelDestination): String = when (destination) {
    TopLevelDestination.TODAY -> "●"
    TopLevelDestination.ATTENTION -> "▲"
    TopLevelDestination.ROUTINES -> "↻"
    TopLevelDestination.HISTORY -> "≡"
    TopLevelDestination.SYSTEM -> "⚙"
}
