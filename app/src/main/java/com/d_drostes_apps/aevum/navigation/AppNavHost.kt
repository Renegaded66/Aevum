package com.d_drostes_apps.aevum.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.d_drostes_apps.aevum.ui.screens.allowance.DailyAllowancesScreen
import com.d_drostes_apps.aevum.ui.screens.dashboard.DashboardScreen
import com.d_drostes_apps.aevum.ui.screens.unknownplace.UnknownPlacesScreen
import com.d_drostes_apps.aevum.ui.screens.growth.GrowthScreen
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsScreen
import com.d_drostes_apps.aevum.ui.screens.onboarding.OnboardingScreen
import com.d_drostes_apps.aevum.ui.screens.settings.SettingsScreen
import com.d_drostes_apps.aevum.ui.screens.settings.TriggerSettingsScreen
import com.d_drostes_apps.aevum.ui.screens.settings.PrivacyScreen
import com.d_drostes_apps.aevum.ui.screens.settings.FitnessTrackersScreen
import com.d_drostes_apps.aevum.ui.screens.settings.ExportScreen
import com.d_drostes_apps.aevum.ui.screens.settings.BackupScreen
import com.d_drostes_apps.aevum.ui.screens.automation.AutomationStatusScreen
import com.d_drostes_apps.aevum.ui.screens.automation.GeofenceDebugScreen
import com.d_drostes_apps.aevum.ui.screens.automation.GeofenceEditorScreen
import com.d_drostes_apps.aevum.ui.screens.automation.GeofenceListScreen
import com.d_drostes_apps.aevum.ui.screens.automation.TriggerEventsScreen
import com.d_drostes_apps.aevum.ui.screens.timeline.ActivityDetailScreen
import com.d_drostes_apps.aevum.ui.screens.timeline.ActivityEditorScreen
import com.d_drostes_apps.aevum.ui.screens.timeline.TimelineScreen
import com.d_drostes_apps.aevum.ui.screens.review.ReviewInboxScreen
import com.d_drostes_apps.aevum.ui.screens.weekly.WeeklyReviewScreen
import com.d_drostes_apps.aevum.ui.screens.habits.HabitsScreen
import com.d_drostes_apps.aevum.ui.screens.habits.HabitEditorScreen

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
                onOpenUsageSettings = {
                    // M12.1: Signal setzen, damit die Trigger-&-Erkennung-Seite
                    // zum Digital-Balance-Block scrollt, sobald sie erscheint.
                    com.d_drostes_apps.aevum.ui.screens.automation.AutomationScrollSignal.requestScrollToUsage()
                    navController.navigate(AppDestination.TriggerSettings.route)
                },
                // M18.37: Todos-Karte auf dem Dashboard
                onOpenTodos = { navController.navigate(AppDestination.Todos.route) }
            )
        }
        composable(AppDestination.Timeline.route) {
            TimelineScreen(
                onCreateActivity = { date -> navController.navigate("activity/new/$date") },
                onEditActivity = { id -> navController.navigate("activity/edit/$id") },
                onEditCandidate = { id -> navController.navigate("activity/candidate/$id") },
                onOpenActivity = { id -> navController.navigate("activity/$id") },
                // M18.61: Kalender-Icon in der Timeline → Kalenderansicht
                onOpenCalendar = { navController.navigate(AppDestination.Calendar.route) }
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
            // M18.35: + onOpenLifeView zur Lebenszeit-Ansicht.
            InsightsScreen(
                onBack = { navController.popBackStack() },
                onOpenLifeView = { navController.navigate(AppDestination.LifeView.route) }
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
                // M18.44: Trigger & Erkennung ist die PRIMÄRE Automatisierungs-
                // Seite — alle Quellen einzeln schaltbar. M18.57: Die Seite
                // "Berechtigungen" wurde hier hinein fusioniert (nur noch
                // diese eine Seite existiert).
                onOpenTriggerSettings = { navController.navigate(AppDestination.TriggerSettings.route) },
                onOpenGeofences = { navController.navigate(AppDestination.GeofenceList.route) },
                onOpenTriggers = { navController.navigate(AppDestination.TriggerEvents.route) },
                onOpenHabits = { navController.navigate(AppDestination.Habits.route) },
                // M18.30: Todos + Tagespauschalen
                onOpenTodos = { navController.navigate(AppDestination.Todos.route) },
                onOpenDailyAllowances = { navController.navigate(AppDestination.DailyAllowances.route) },
                // M18.39: Bucket List
                onOpenBucketList = { navController.navigate(AppDestination.BucketList.route) },
                // M18.2: Positivitäts-Scores pro Aktivität
                onOpenActivityTypes = { navController.navigate(AppDestination.ActivityTypes.route) },
                onOpenCategories = { navController.navigate(AppDestination.Categories.route) },
                // M12.2: Home/Work öffnen den existierenden Geofence-Editor
                // oder legen den Geofence direkt mit dem passenden QuickSetup an.
                onOpenHomeGeofence = { id -> navController.navigate("geofence/edit/$id") },
                onOpenWorkGeofence = { id -> navController.navigate("geofence/edit/$id") },
                onCreateHomeGeofence = { navController.navigate(AppDestination.GeofenceCreateHome.route) },
                onCreateWorkGeofence = { navController.navigate(AppDestination.GeofenceCreateWork.route) },
                // M18.55: Datenschutz, Export, Backup
                onOpenPrivacy = { navController.navigate(AppDestination.Privacy.route) },
                onOpenExport = { navController.navigate(AppDestination.Export.route) },
                onOpenBackup = { navController.navigate(AppDestination.Backup.route) },
                // M18.59: Fitness-Tracker (Garmin Connect Login + Sync)
                onOpenFitnessTrackers = { navController.navigate(AppDestination.FitnessTrackers.route) }
            )
        }
        // M18.59: Fitness-Tracker — eigene Seite (Garmin Login + Sync)
        composable(AppDestination.FitnessTrackers.route) {
            FitnessTrackersScreen(onBack = { navController.popBackStack() })
        }
        // M18.55: Datenschutz, Export, Backup — eigene Seiten
        composable(AppDestination.Privacy.route) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.Export.route) {
            ExportScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.Backup.route) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        // M18.44: Trigger & Erkennung — eigene Seite, alle Quellen einzeln schaltbar.
        // M18.57: Fusioniert mit der alten "Berechtigungen"-Seite (AutomationSettings).
        composable(AppDestination.TriggerSettings.route) {
            TriggerSettingsScreen(
                onBack = { navController.popBackStack() },
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

        // Habits
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
            com.d_drostes_apps.aevum.ui.screens.activitytypes.ActivityTypesScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // M18.59: Kategorien verwalten
        composable(AppDestination.Categories.route) {
            com.d_drostes_apps.aevum.ui.screens.categories.CategoriesScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // M18.28: Kalender — Heatmap der Zeitqualität + Tages-Detail
        composable(AppDestination.Calendar.route) {
            com.d_drostes_apps.aevum.ui.screens.calendar.CalendarScreen(
                onOpenActivity = { sessionId -> navController.navigate("activity/$sessionId") }
            )
        }
        // M18.61: Digital Balance — ersetzt den Kalender-Tab
        composable(AppDestination.DigitalBalance.route) {
            com.d_drostes_apps.aevum.ui.screens.digitalbalance.DigitalBalanceScreen(
                viewModel = hiltViewModel()
            )
        }
        // M18.30: Todos
        composable(AppDestination.Todos.route) {
            com.d_drostes_apps.aevum.ui.screens.todos.TodosScreen(
                onCreate = { navController.navigate(AppDestination.TodoCreate.route) },
                // M18.38: Todo bearbeiten — Editor mit geladenem Todo
                onEdit = { todoId ->
                    navController.navigate("todo/edit/$todoId")
                }
            )
        }
        composable(AppDestination.TodoCreate.route) {
            com.d_drostes_apps.aevum.ui.screens.todos.TodoEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        // M18.38: Todo bearbeiten — gleicher Editor, aber mit geladenem Todo
        composable(
            route = AppDestination.TodoEdit.route,
            arguments = listOf(navArgument("todoId") { type = NavType.StringType })
        ) { backStackEntry ->
            val todoId = backStackEntry.arguments?.getString("todoId") ?: ""
            com.d_drostes_apps.aevum.ui.screens.todos.TodoEditorScreen(
                todoId = todoId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        // M18.39: Bucket List — eigene Seite, von Settings aus erreichbar
        composable(AppDestination.BucketList.route) {
            com.d_drostes_apps.aevum.ui.screens.bucketlist.BucketListScreen(
                onCreate = { navController.navigate(AppDestination.BucketListCreate.route) },
                onEdit = { itemId -> navController.navigate("bucketlist/edit/$itemId") }
            )
        }
        composable(AppDestination.BucketListCreate.route) {
            com.d_drostes_apps.aevum.ui.screens.bucketlist.BucketListEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = AppDestination.BucketListEdit.route,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId") ?: ""
            com.d_drostes_apps.aevum.ui.screens.bucketlist.BucketListEditorScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        // M18.35: Lebenszeit-Ansicht — von Insights aus erreichbar
        composable(AppDestination.LifeView.route) {
            com.d_drostes_apps.aevum.ui.screens.lifeview.LifeViewScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}