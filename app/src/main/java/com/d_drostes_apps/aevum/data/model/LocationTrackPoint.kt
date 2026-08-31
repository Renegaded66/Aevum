package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M18.86: Ein GPS-Punkt auf der Strecke einer Auto-Fahrt oder Wanderung.
 *
 * ZWECK (User-Wunsch: "Dass man zumindest halbwegs die Fahrtstrecke sieht.
 * Nicht jede Kurve aber alle paar Minuten mal"): Die Orts-Timeline-Karte
 * zeichnet bisher Luftlinien zwischen den Orten — für die Google-Maps-
 * Zeitachsen-Metapher fehlt die echte Streckengeometrie. Diese Tabelle
 * speichert verdichtete Track-Punkte (Bewegungs-Trigger ~25 s / ≥ 30 m +
 * Heartbeat ~5 Min), NICHT den rohen 5-Sekunden-GPS-Stream (Datenschutz:
 * Strecke ja, Bewegungsprofil nein — 1 Punkt je ~25 s ergibt < 300 Punkte
 * für eine 2-Stunden-Fahrt).
 *
 * ARCHITEKTUR (ADR-0030, bewusst so):
 *  - FK auf activity_session(id) ON DELETE CASCADE: Punkte gehören zur
 *    Aufzeichnung. softDelete setzt nur deleted_at (Zeile bleibt) → Track
 *    bleibt für wiederhergestellte Sessions erhalten; nur HARD-Deletes
 *    (Session-Editor) kaskadieren die Punkte mit.
 *  - Keine separate Trigger-Verknüpfung: Der Track ist EVIDENZ, kein
 *    Trigger (nie Grund für Auto-Starts — das M18.84-Gate-Set bleibt
 *    unberührt).
 *  - Retention 90 Tage über GeofenceEventLog-Muster (deleteOlderThan,
 *    aufrufend im DailyMaintenanceWorker-Rhythmus der App).
 */
@Entity(
    tableName = "location_track_point",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["recorded_at"])
    ]
)
data class LocationTrackPoint(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    /** GPS-Genauigkeit in Metern (null wenn unbekannt) — für Filter beim
     *  Zeichnen (Auspäser > 200 m verwerfen, wie DriveDetectionEngine). */
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float? = null,
    /** Geschwindigkeit beim Fix in m/s (null wenn unbekannt) — für künftige
     *  Farbverläufe nach Tempo. */
    @ColumnInfo(name = "speed_mps") val speedMps: Float? = null
)