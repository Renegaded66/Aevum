package de.devondroste.aevum.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.devondroste.aevum.ui.screens.allowance.DailyAllowancesScreen
import de.devondroste.aevum.ui.screens.dashboard.DashboardScreen
import de.devondroste.aevum.ui.screens.unknownplace.UnknownPlacesScreen
import de.devondroste.aevum.ui.screens.growth.GrowthScreen
import de.devondroste.aevum.ui.screens.insights.InsightsScreen
import de.devondroste.aevum.ui.screens.onboarding.OnboardingScreen
import de.devondroste.aevum.ui.screens.settings.SettingsScreen
import de.devondroste.aevum.ui.screens.automation.AutomationSettingsScreen
import de.devondroste.aevum.ui.screens.automation.AutomationStatusScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceDebugScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceEditorScreen
import de.devondroste.aevum.ui.screens.automation.GeofenceListScreen
import de.devondroste.aevum.ui.screens.automation.TriggerEventsScreen
import de.devondroste.aevum.ui.screens.timeline.ActivityDetailScreen
import de.devondroste.aevum.ui.screens.timeline.ActivityEditorScreen
import de.devondroste.aevum.ui.screens.timeline.TimelineScreen
import de.devondroste.aevum.ui.screens.review.ReviewInboxScreen
import de.devondroste.aevum.ui.screens.weekly.WeeklyReviewScreen
import de.devondroste.aevum.ui.screens.goals.GoalsScreen
import de.devondroste.aevum.ui.screens.goals.GoalEditorScreen
import de.devondroste.aevum.ui.screens.habits.HabitsScreen
import de.devondroste.aevum.ui.screens.habits.HabitEditorScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppDestination.Dashboard.route,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(AppDestination.Dashboard.route) {
            DashboardScreen(
                onOpenTimeline = { navController.navigate(AppDestination.Timeline.route) },
                onOpenReview = { navController.navigate(AppDestination.ReviewInbox.route) },
                onOpenGoals = { navController.navigate(AppDestination.Goals.route) },
                onOpenUsageSettings = {
                    // M12.1: Signal setzen, damit die Automation-Screen
                    // zum UsageStats-Block scrollt, sobald sie erscheint.
                    de.devondroste.aevum.ui.screens.automation.AutomationScrollSignal.requestScrollToUsage()
                    navController.navigate(AppDestination.AutomationSettings.route)
                }
            )
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
            route = AppDestination.TimelineDay.route,
            arguments = listOf(navArgument("date") { type = NavType.LongType })
        ) {
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
        composable(AppDestination.Insights.route) {
            // M17.4: InsightsScreen hat jetzt nur noch onBack —
            // die Verlinkungen zu Goals/Habits/WeeklyReview sind
            // (noch) nicht Teil des Redesigns, folgen separat.
            InsightsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppDestination.WeeklyReview.route) {
            WeeklyReviewScreen(
                onBackToInsights = { navController.popBackStack() },
                onOpenTimelineDay = { dayStart -> navController.navigate("timeline/$dayStart") },
                onOpenTimeline = { navController.navigate(AppDestination.Timeline.route) },
                onOpenReviewInbox = { navController.navigate(AppDestination.ReviewInbox.route) }
            )
        }
        composable(AppDestination.Growth.route) { GrowthScreen() }
        composable(AppDestination.Settings.route) {
            SettingsScreen(
                onOpenAutomation = { navController.navigate(AppDestination.AutomationSettings.route) },
                onOpenGeofences = { navController.navigate(AppDestination.GeofenceList.route) },
                onOpenTriggers = { navController.navigate(AppDestination.TriggerEvents.route) },
                onOpenGoals = { navController.navigate(AppDestination.Goals.route) },
                onOpenHabits = { navController.navigate(AppDestination.Habits.route) },
                // M18.2: Positivitäts-Scores pro Aktivität
                onOpenActivityTypes = { navController.navigate(AppDestination.ActivityTypes.route) },
                // M12.2: Home/Work öffnen den existierenden Geofence-Editor
                // oder legen den Geofence direkt mit dem passenden QuickSetup an.
                onOpenHomeGeofence = { id -> navController.navigate("geofence/edit/$id") },
                onOpenWorkGeofence = { id -> navController.navigate("geofence/edit/$id") },
                onCreateHomeGeofence = { navController.navigate(AppDestination.GeofenceCreateHome.route) },
                onCreateWorkGeofence = { navController.navigate(AppDestination.GeofenceCreateWork.route) }
            )
        }
        composable(AppDestination.AutomationSettings.route) {
            AutomationSettingsScreen(
                onOpenGeofences = { navController.navigate(AppDestination.GeofenceList.route) },
                onOpenTriggers = { navController.navigate(AppDestination.TriggerEvents.route) },
                onOpenStatus = { navController.navigate(AppDestination.AutomationStatus.route) }
            )
        }
        composable(AppDestination.AutomationStatus.route) {
            AutomationStatusScreen(onBack = { navController.popBackStack() })
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
        // M8.1: Quick-setup for Home and Work
        composable(AppDestination.GeofenceCreateHome.route) {
            GeofenceEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.GeofenceCreateWork.route) {
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
        composable(AppDestination.ReviewInbox.route) {
            ReviewInboxScreen(
                onBack = { navController.popBackStack() },
                onOpenEditor = { candidate -> navController.navigate("activity/candidate/${candidate.id}") },
                onOpenSession = { sessionId ->
                    navController.navigate("activity/$sessionId") {
                        popUpTo(AppDestination.ReviewInbox.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppDestination.Onboarding.route) { OnboardingScreen() }
        composable(AppDestination.LifeProfileSetup.route) { OnboardingScreen() }
        composable(AppDestination.PermissionEducation.route) { OnboardingScreen() }
        composable(AppDestination.PlacesSetup.route) { OnboardingScreen() }
        composable(AppDestination.DashboardIntro.route) { OnboardingScreen() }

        // Goals & Habits
        composable(AppDestination.Goals.route) {
            GoalsScreen(
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(AppDestination.GoalCreate.route) },
                onEdit = { id -> navController.navigate("goal/edit/$id") }
            )
        }
        composable(AppDestination.GoalCreate.route) {
            GoalEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = AppDestination.GoalEdit.route,
            arguments = listOf(navArgument("goalId") { type = NavType.StringType })
        ) { backStackEntry ->
            val goalId = backStackEntry.arguments?.getString("goalId")
            GoalEditorScreen(
                onBack = { navController.popBackStack() },
                goalId = goalId
            )
        }
        composable(AppDestination.Habits.route) {
            HabitsScreen(
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(AppDestination.HabitCreate.route) },
                onEdit = { id -> navController.navigate("habit/edit/$id") }
            )
        }
        composable(AppDestination.HabitCreate.route) {
            HabitEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = AppDestination.HabitEdit.route,
            arguments = listOf(navArgument("habitId") { type = NavType.StringType })
        ) {
            HabitEditorScreen(onBack = { navController.popBackStack() })
        }

        // M17.2 + M17.3
        composable(AppDestination.UnknownPlaces.route) {
            UnknownPlacesScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.DailyAllowances.route) {
            DailyAllowancesScreen(onBack = { navController.popBackStack() })
        }
        // M18.2: Positivitäts-Scores pro Aktivität
        composable(AppDestination.ActivityTypes.route) {
            de.devondroste.aevum.ui.screens.activitytypes.ActivityTypesScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // M18.28: Kalender — Heatmap der Zeitqualität + Tages-Detail
        composable(AppDestination.Calendar.route) {
            de.devondroste.aevum.ui.screens.calendar.CalendarScreen(
                onOpenActivity = { sessionId -> navController.navigate("activity/$sessionId") }
            )
        }
    }
}