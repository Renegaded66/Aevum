package de.devondroste.aevum.navigation

enum class AppDestination(val route: String, val title: String) {
    Dashboard("dashboard", "Heute"),
    Timeline("timeline", "Timeline"),
    Insights("insights", "Insights"),
    Growth("growth", "Wachstum"),
    Settings("settings", "Einstellungen"),
    Onboarding("onboarding", "Onboarding"),
    LifeProfileSetup("life_profile_setup", "Lebensprofil"),
    PermissionEducation("permission_education", "Berechtigungen"),
    PlacesSetup("places_setup", "Orte"),
    DashboardIntro("dashboard_intro", "Dashboard")
}
