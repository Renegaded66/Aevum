package com.d_drostes_apps.aevum.navigation

import androidx.annotation.StringRes
import com.d_drostes_apps.aevum.R

enum class AppDestination(val route: String, @StringRes val titleRes: Int) {
    Dashboard("dashboard", R.string.dashboard_title),
    Timeline("timeline", R.string.timeline_title),
    TimelineDay("timeline/{date}", R.string.timeline_title),
    ActivityCreate("activity/new/{date}", R.string.activity_create_title),
    ActivityEdit("activity/edit/{sessionId}", R.string.activity_edit_title),
    ActivityFromCandidate("activity/candidate/{candidateId}", R.string.activity_candidate_title),
    ActivityDetail("activity/{sessionId}", R.string.activity_detail_title),
    Insights("insights", R.string.insights_title),
    WeeklyReview("weekly_review", R.string.weekly_review_title),
    Growth("growth", R.string.growth_title),
    Settings("settings", R.string.settings_title),
    // M18.44: Eigene Seite für alle Trigger-Quellen (Geofence/Auto/Walking/Rad/Schlaf)
    // M18.57: Fusioniert mit der alten "Berechtigungen"-Seite (AutomationSettings entfernt).
    TriggerSettings("trigger_settings", R.string.trigger_settings_title),
    GeofenceList("geofences", R.string.geofence_list_title),
    GeofenceCreate("geofence/new", R.string.geofence_create_title),
    GeofenceCreateHome("geofence/new/home", R.string.geofence_create_home_title),
    GeofenceCreateWork("geofence/new/work", R.string.geofence_create_work_title),
    GeofenceEdit("geofence/edit/{geofenceId}", R.string.geofence_edit_title),
    TriggerEvents("trigger_events", R.string.trigger_events_title),
    GeofenceDebug("geofence_debug", R.string.geofence_debug_title),
    AutomationStatus("automation/status", R.string.automation_status_title),
    ReviewInbox("review_inbox", R.string.review_inbox_title),
    Onboarding("onboarding", R.string.onboarding_title),
    LifeProfileSetup("life_profile_setup", R.string.life_profile_setup_title),
    PermissionEducation("permission_education", R.string.permission_education_title),
    PlacesSetup("places_setup", R.string.places_setup_title),
    DashboardIntro("dashboard_intro", R.string.dashboard_title),
    // M17.2 + M17.3
    UnknownPlaces("unknown_places", R.string.unknown_places_title),
    DailyAllowances("daily_allowances", R.string.daily_allowances_title),
    // M18.2: Positivitäts-Scores pro Aktivität
    ActivityTypes("activity_types", R.string.activity_types_title),
    // M18.59: Kategorien verwalten (erstellen, Aktivitäten zuordnen, Icon+Farbe)
    Categories("categories", R.string.categories_title),
    // M18.28: Kalender-Tab (Heatmap der Zeitqualität)
    Calendar("calendar", R.string.calendar_title),
    // M18.61: Digital Balance — ersetzt den Kalender-Tab
    DigitalBalance("digital_balance", R.string.digital_balance_title),
    // M18.67: App-Aufzeichnung (Apps → Activity automatisch)
    AppTracking("app_tracking", R.string.app_tracking_title),
    // M18.30: Todos
    Todos("todos", R.string.todos_title),
    TodoCreate("todo/new", R.string.todo_create_title),
    // M18.38: Todo bearbeiten
    TodoEdit("todo/edit/{todoId}", R.string.todo_edit_title),
    // M18.35: Lebenszeit-Ansicht
    LifeView("lifeview", R.string.lifeview_title),
    // M18.55: Datenschutz, Export, Backup
    Privacy("privacy", R.string.privacy_title),
    Export("export", R.string.export_title),
    Backup("backup", R.string.backup_title),
    // M18.59: Fitness-Tracker (Garmin Connect Login + Sync)
    FitnessTrackers("fitness_trackers", R.string.fitness_trackers_title)
}
