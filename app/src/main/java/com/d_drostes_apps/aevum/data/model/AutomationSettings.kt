package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "automation_settings")
data class AutomationSettings(
    @PrimaryKey val id: String = "default",
    @ColumnInfo(name = "geofencing_enabled") val geofencingEnabled: Boolean = false,
    @ColumnInfo(name = "background_capture_enabled") val backgroundCaptureEnabled: Boolean = false,
    @ColumnInfo(name = "review_notifications_enabled") val reviewNotificationsEnabled: Boolean = false,
    @ColumnInfo(name = "battery_saver_mode") val batterySaverMode: Boolean = true,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    // M8: Per-source toggles
    @ColumnInfo(name = "health_sleep_enabled", defaultValue = "0") val healthSleepEnabled: Boolean = false,
    @ColumnInfo(name = "digital_balance_enabled", defaultValue = "0") val digitalBalanceEnabled: Boolean = false,
    // M14: Schlaf-Fusion (Screen + Activity Recognition + Digital Balance).
    // Default aus — der User entscheidet bewusst, ob die 3-Signal-Fusion läuft.
    @ColumnInfo(name = "sleep_fusion_enabled", defaultValue = "0") val sleepFusionEnabled: Boolean = false,
    // M18.44: Einzelne Trigger-Quellen schaltbar (Trigger-Settings-Seite).
    // Default AN — die automatische Erkennung ist das Kern-Feature.
    @ColumnInfo(name = "driving_detection_enabled", defaultValue = "1") val drivingDetectionEnabled: Boolean = true,
    @ColumnInfo(name = "walking_detection_enabled", defaultValue = "1") val walkingDetectionEnabled: Boolean = true,
    @ColumnInfo(name = "bicycle_detection_enabled", defaultValue = "1") val bicycleDetectionEnabled: Boolean = true,
    // M18.58: EINE Schlaf-Quelle statt vieler Toggles.
    // Werte: "screen" (Bildschirmzeit-Heuristik, Default), "health_connect",
    // "garmin", "none" (keine Aufzeichnung). Der User wählt GENAU EINE
    // Quelle — die alten Einzel-Toggles (healthSleepEnabled,
    // sleepFusionEnabled) sind damit obsolet, bleiben aber für
    // Bestands-Daten in der DB.
    @ColumnInfo(name = "sleep_source", defaultValue = "screen") val sleepSource: String = "screen",
    // M18.70: Bildschirm-Aufzeichnung — Vorlauf in Minuten.
    // 0 = sofort bei Screen-ON, 1..10 = Vorlauf, -1 = deaktiviert (Slider rechts).
    @ColumnInfo(name = "screen_recording_minutes", defaultValue = "5") val screenRecordingMinutes: Int = 5
) : Serializable
