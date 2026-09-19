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
    @ColumnInfo(name = "screen_recording_minutes", defaultValue = "5") val screenRecordingMinutes: Int = 5,
    // M18.129: Kalender-Integration.
    // Master-Schalter: Kalender überhaupt lesen (Timeline-Vorschau der
    // geplanten Blöcke). Default AUS — READ_CALENDAR ist eine
    // dangerous-Permission, die der Nutzer bewusst erteilen muss.
    @ColumnInfo(name = "calendar_sync_enabled", defaultValue = "0") val calendarSyncEnabled: Boolean = false,
    // Separat schaltbar: automatisch aufzeichnen (Start/Stop an
    // Termingrenzen). Der Nutzer kann den Kalender bewusst nur LESEN
    // (Vorschau) und trotzdem nicht automatisch aufzeichnen lassen —
    // das ist eine eigenständige Entscheidung.
    @ColumnInfo(name = "calendar_auto_tracking_enabled", defaultValue = "0") val calendarAutoTrackingEnabled: Boolean = false,
    // Zeitstempel des letzten erfolgreichen Syncs (0 = nie).
    @ColumnInfo(name = "calendar_last_sync_at", defaultValue = "0") val calendarLastSyncAt: Long = 0L,
    // Sync-Takt in Stunden. Default 6 — Kalender ändern sich selten,
    // 4 Syncs/Tag sind reichlich und akku-schonend (M18.104-Muster:
    // teure Operationen selten, nicht im Dauertakt).
    @ColumnInfo(name = "calendar_sync_interval_hours", defaultValue = "6") val calendarSyncIntervalHours: Int = 6,
    // M18.133: Fahrt-Stopp beim Gehen (Hardware-Schritte).
    // User-Spezifikation: "Sobald ich aus dem Auto aussteige und gehe, bin
    // ich offensichtlich nicht mehr am Autofahren — die Aufzeichnung kann
    // gestoppt werden. Falls die Berechtigung erteilt ist, soll die
    // Aufzeichnung automatisch stoppen, sobald Schritte bzw. Gehen
    // erkannt wird."
    // Default AN: Es ist die vom User gewünschte Sofort-Reaktion; die
    // Wirksamkeit hängt ohnehin an der ACTIVITY_RECOGNITION-Berechtigung
    // (Step-Detector, API 29+), die die UI als Gate anzeigt.
    @ColumnInfo(name = "walk_stop_on_steps_enabled", defaultValue = "1") val walkStopOnStepsEnabled: Boolean = true
) : Serializable
