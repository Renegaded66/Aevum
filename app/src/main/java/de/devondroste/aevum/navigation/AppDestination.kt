package de.devondroste.aevum.navigation

enum class AppDestination(val route: String, val title: String) {
    Dashboard("dashboard", "Heute"),
    Timeline("timeline", "Timeline"),
    TimelineDay("timeline/{date}", "Timeline"),
    ActivityCreate("activity/new/{date}", "Neue Aktivität"),
    ActivityEdit("activity/edit/{sessionId}", "Aktivität bearbeiten"),
    ActivityFromCandidate("activity/candidate/{candidateId}", "Candidate bearbeiten"),
    ActivityDetail("activity/{sessionId}", "Aktivität"),
    Insights("insights", "Insights"),
    WeeklyReview("weekly_review", "Weekly Review"),
    Growth("growth", "Wachstum"),
    Settings("settings", "Einstellungen"),
    AutomationSettings("automation", "Automatisierung"),
    // M18.44: Eigene Seite für alle Trigger-Quellen (Geofence/Auto/Walking/Rad/Schlaf)
    TriggerSettings("trigger_settings", "Trigger & Erkennung"),
    GeofenceList("geofences", "Geofences"),
    GeofenceCreate("geofence/new", "Geofence anlegen"),
    GeofenceCreateHome("geofence/new/home", "Zuhause anlegen"),
    GeofenceCreateWork("geofence/new/work", "Arbeit anlegen"),
    GeofenceEdit("geofence/edit/{geofenceId}", "Geofence bearbeiten"),
    TriggerEvents("trigger_events", "Trigger Events"),
    GeofenceDebug("geofence_debug", "Geofence Diagnose"),
    AutomationStatus("automation/status", "Automatisierung Status"),
    ReviewInbox("review_inbox", "Review Inbox"),
    Onboarding("onboarding", "Onboarding"),
    LifeProfileSetup("life_profile_setup", "Lebensprofil"),
    Goals("goals", "Ziele"),
    GoalCreate("goal/new", "Ziel anlegen"),
    GoalEdit("goal/edit/{goalId}", "Ziel bearbeiten"),
    Habits("habits", "Gewohnheiten"),
    HabitCreate("habit/new", "Gewohnheit anlegen"),
    HabitEdit("habit/edit/{habitId}", "Gewohnheit bearbeiten"),
    PermissionEducation("permission_education", "Berechtigungen"),
    PlacesSetup("places_setup", "Orte"),
    DashboardIntro("dashboard_intro", "Dashboard"),
    // M17.2 + M17.3
    UnknownPlaces("unknown_places", "Unbekannte Orte"),
    DailyAllowances("daily_allowances", "Tagespauschalen"),
    // M18.2: Positivitäts-Scores pro Aktivität
    ActivityTypes("activity_types", "Aktivitäten & Positivität"),
    // M18.28: Kalender-Tab (Heatmap der Zeitqualität)
    Calendar("calendar", "Kalender"),
    // M18.30: Todos
    Todos("todos", "Todos"),
    TodoCreate("todo/new", "Neues Todo"),
    // M18.38: Todo bearbeiten
    TodoEdit("todo/edit/{todoId}", "Todo bearbeiten"),
    // M18.39: Bucket List
    BucketList("bucketlist", "Bucket List"),
    BucketListCreate("bucketlist/new", "Neuer Eintrag"),
    BucketListEdit("bucketlist/edit/{itemId}", "Eintrag bearbeiten"),
    // M18.35: Lebenszeit-Ansicht
    LifeView("lifeview", "Lebenszeit")
}