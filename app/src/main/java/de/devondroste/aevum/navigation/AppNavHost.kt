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
        composable(AppDestination.Settings.route) { SettingsScreen() }
        composable(AppDestination.Onboarding.route) { OnboardingScreen() }
        composable(AppDestination.LifeProfileSetup.route) { OnboardingScreen() }
        composable(AppDestination.PermissionEducation.route) { OnboardingScreen() }
        composable(AppDestination.PlacesSetup.route) { OnboardingScreen() }
        composable(AppDestination.DashboardIntro.route) { OnboardingScreen() }
    }
}
