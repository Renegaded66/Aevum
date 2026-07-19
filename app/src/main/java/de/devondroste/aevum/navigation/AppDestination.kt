package de.devondroste.aevum.navigation

enum class AppDestination(val route: String, val title: String) {
    Dashboard("dashboard", "Heute"),
    Timeline("timeline", "Timeline"),
    ActivityCreate("activity/new/{date}", "Neue Aktivität"),
    ActivityEdit("activity/edit/{sessionId}", "Aktivität bearbeiten"),
    ActivityFromCandidate("activity/candidate/{candidateId}", "Candidate bearbeiten"),
    ActivityDetail("activity/{sessionId}", "Aktivität"),
    Insights("insights", "Insights"),
    Growth("growth", "Wachstum"),
    Settings("settings", "Einstellungen"),
    AutomationSettings("automation", "Automatisierung"),
    GeofenceList("geofences", "Geofences"),
    GeofenceCreate("geofence/new", "Geofence anlegen"),
    GeofenceEdit("geofence/edit/{geofenceId}", "Geofence bearbeiten"),
    TriggerEvents("trigger_events", "Trigger Events"),
    GeofenceDebug("geofence_debug", "Geofence Diagnose"),
    Onboarding("onboarding", "Onboarding"),
    LifeProfileSetup("life_profile_setup", "Lebensprofil"),
    PermissionEducation("permission_education", "Berechtigungen"),
    PlacesSetup("places_setup", "Orte"),
    DashboardIntro("dashboard_intro", "Dashboard")
}
