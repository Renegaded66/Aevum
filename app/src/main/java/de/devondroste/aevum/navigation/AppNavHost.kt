package de.devondroste.aevum.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.devondroste.aevum.ui.screens.dashboard.DashboardScreen
import de.devondroste.aevum.ui.screens.growth.GrowthScreen
import de.devondroste.aevum.ui.screens.insights.InsightsScreen
import de.devondroste.aevum.ui.screens.onboarding.OnboardingScreen
import de.devondroste.aevum.ui.screens.settings.SettingsScreen
import de.devondroste.aevum.ui.screens.timeline.TimelineScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppDestination.Dashboard.route
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(AppDestination.Dashboard.route) { DashboardScreen() }
        composable(AppDestination.Timeline.route) { TimelineScreen() }
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
