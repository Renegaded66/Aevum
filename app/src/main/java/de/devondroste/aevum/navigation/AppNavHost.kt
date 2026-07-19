package de.devondroste.aevum.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.devondroste.aevum.ui.screens.dashboard.DashboardScreen
import de.devondroste.aevum.ui.screens.growth.GrowthScreen
import de.devondroste.aevum.ui.screens.insights.InsightsScreen
import de.devondroste.aevum.ui.screens.onboarding.OnboardingScreen
import de.devondroste.aevum.ui.screens.settings.SettingsScreen
import de.devondroste.aevum.ui.screens.automation.AutomationSettingsScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceDebugScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceEditorScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceListScreen
import de.devondroste.aevum.ui.screens.automation.TriggerEventsScreen
import de.devondroste.aevum.ui.screens.timeline.ActivityDetailScreen
import de.devondroste.aevum.ui.screens.timeline.ActivityEditorScreen
import de.devondroste.aevum.ui.screens.timeline.TimelineScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppDestination.Dashboard.route
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(AppDestination.Dashboard.route) {
            DashboardScreen(onOpenTimeline = { navController.navigate(AppDestination.Timeline.route) })
        }
        composable(AppDestination.Timeline.route) {
            TimelineScreen(
                onCreateActivity = { date -> navController.navigate("activity/new/$date") },
                onEditActivity = { id -> navController.navigate("activity/edit/$id") },
                onEditCandidate = { id -> navController.navigate("activity/candidate/$id") },
                onOpenActivity = { id -> navController.navigate("activity/$id") }
            )
        }
        composable(
            route = AppDestination.ActivityCreate.route,
            arguments = listOf(navArgument("date") { type = NavType.LongType })
        ) {
            ActivityEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("activity/$id") {
                        popUpTo(AppDestination.Timeline.route)
                    }
                }
            )
        }
        composable(
            route = AppDestination.ActivityEdit.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) {
            ActivityEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("activity/$id") {
                        popUpTo(AppDestination.Timeline.route)
                    }
                }
            )
        }
        composable(
            route = AppDestination.ActivityFromCandidate.route,
            arguments = listOf(navArgument("candidateId") { type = NavType.StringType })
        ) {
            ActivityEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("activity/$id") {
                        popUpTo(AppDestination.Timeline.route)
                    }
                }
            )
        }
        composable(
            route = AppDestination.ActivityDetail.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) {
            ActivityDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate("activity/edit/$id") }
            )
        }
        composable(AppDestination.Insights.route) { InsightsScreen() }
        composable(AppDestination.Growth.route) { GrowthScreen() }
        composable(AppDestination.Settings.route) {
            SettingsScreen(
                onOpenAutomation = { navController.navigate(AppDestination.AutomationSettings.route) },
                onOpenGeofences = { navController.navigate(AppDestination.GeofenceList.route) },
                onOpenTriggers = { navController.navigate(AppDestination.TriggerEvents.route) }
            )
        }
        composable(AppDestination.AutomationSettings.route) {
            AutomationSettingsScreen(
                onOpenGeofences = { navController.navigate(AppDestination.GeofenceList.route) },
                onOpenTriggers = { navController.navigate(AppDestination.TriggerEvents.route) },
                onOpenDebug = { navController.navigate(AppDestination.GeofenceDebug.route) }
            )
        }
        composable(AppDestination.GeofenceList.route) {
            GeofenceListScreen(
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(AppDestination.GeofenceCreate.route) },
                onEdit = { id -> navController.navigate("geofence/edit/$id") }
            )
        }
        composable(AppDestination.GeofenceCreate.route) {
            GeofenceEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = AppDestination.GeofenceEdit.route,
            arguments = listOf(navArgument("geofenceId") { type = NavType.StringType })
        ) {
            GeofenceEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.TriggerEvents.route) {
            TriggerEventsScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.GeofenceDebug.route) {
            GeofenceDebugScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.Onboarding.route) { OnboardingScreen() }
        composable(AppDestination.LifeProfileSetup.route) { OnboardingScreen() }
        composable(AppDestination.PermissionEducation.route) { OnboardingScreen() }
        composable(AppDestination.PlacesSetup.route) { OnboardingScreen() }
        composable(AppDestination.DashboardIntro.route) { OnboardingScreen() }
    }
}
