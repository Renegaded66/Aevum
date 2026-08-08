package com.d_drostes_apps.aevum.automation.model

object AutomationConstants {
    const val DATA_SOURCE_GEOFENCING = "phone_geofencing"

    const val DETECTION_GEOFENCE_ENTER = "GEOFENCE_ENTER"
    const val DETECTION_GEOFENCE_EXIT = "GEOFENCE_EXIT"

    // M12.2: Activity Recognition — Fahrten-Erkennung.
    // IN_VEHICLE ist die einzige Detection, die wir aus Activity Recognition
    // verarbeiten. Andere Typen (STILL, ON_FOOT, …) sind zu unzuverlässig
    // und würden die User-Experience mit False-Positives belasten.
    const val DETECTION_ACTIVITY_RECOGNITION_IN_VEHICLE = "ACTIVITY_RECOGNITION_IN_VEHICLE"

    const val TRIGGER_GEOFENCE_ENTER = "GEOFENCE_ENTER"
    const val TRIGGER_GEOFENCE_EXIT = "GEOFENCE_EXIT"
    const val TRIGGER_HOME_ARRIVED = "HOME_ARRIVED"
    const val TRIGGER_HOME_LEFT = "HOME_LEFT"
    const val TRIGGER_WORK_ENTERED = "WORK_ENTERED"
    const val TRIGGER_WORK_LEFT = "WORK_LEFT"
    const val TRIGGER_CUSTOM_PLACE_ENTERED = "CUSTOM_PLACE_ENTERED"
    const val TRIGGER_CUSTOM_PLACE_LEFT = "CUSTOM_PLACE_LEFT"

    const val CANDIDATE_STATUS_PENDING = "PENDING"
    const val CANDIDATE_STATUS_ACCEPTED = "ACCEPTED"
    const val CANDIDATE_STATUS_EDITED = "EDITED"
    const val CANDIDATE_STATUS_DISMISSED = "DISMISSED"

    const val CREATED_BY_GEOFENCE_PIPELINE = "GEOFENCE_PIPELINE_V1"
    const val CREATED_BY_TRIGGER_PAIR_RULES = "TRIGGER_PAIR_RULES_V1"
}
