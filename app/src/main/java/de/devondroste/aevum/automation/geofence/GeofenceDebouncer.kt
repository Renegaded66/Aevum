package de.devondroste.aevum.automation.geofence

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M11.2: Zentrale Stabilisierungs-Entprellung für Geofence-Trigger.
 *
 * Jeder Übergang (ENTER/EXIT) wird erst bestätigt, wenn der Zustand
 * für STABILIZATION_MS (2 Minuten) konstant bleibt.
 *
 * Ablauf:
 *   1. ENTER erkannt → wird als "pending" gespeichert
 *   2. Wenn innerhalb der Stabilisierungszeit ein EXIT kommt → ENTER verworfen
 *   3. Nach Ablauf der Stabilisierungszeit → GeofenceStabilizationWorker
 *      ruft confirmPending() auf → ENTER wird emitted
 *
 * Thread-safe via ConcurrentHashMap.
 */
@Singleton
class GeofenceDebouncer @Inject constructor() {

    private val pendingByGeofence = ConcurrentHashMap<String, PendingTransition>()
    private val confirmedStateByGeofence = ConcurrentHashMap<String, GeofenceTransition>()

    /**
     * Wird vom BroadcastReceiver bei jedem Geofence-Event aufgerufen.
     *
     * - Wenn kein pendenter Übergang existiert → neuen pendenten starten,
     *   StabilizationWorker schedulen, false zurückgeben (nicht sofort emit).
     * - Wenn ein pendenter Übergang existiert und der neue übereinstimmt →
     *   false (warten auf StabilizationWorker).
     * - Wenn ein pendenter Übergang existiert und der neue abweicht →
     *   pendenten verwerfen, neuen starten, false zurückgeben.
     */
    fun shouldEmit(geofenceId: String, transition: GeofenceTransition, now: Long): Boolean {
        val pending = pendingByGeofence[geofenceId]

        // GPS-Flattern: pendent widerspricht neuem → verwerfen
        if (pending != null && pending.transition != transition) {
            pendingByGeofence.remove(geofenceId)
        }

        val current = pendingByGeofence[geofenceId]
        if (current == null) {
            // Echo-Schutz: gleicher Übergang wie bereits bestätigt → suppress
            val confirmed = confirmedStateByGeofence[geofenceId]
            if (confirmed == transition) {
                return false
            }
            pendingByGeofence[geofenceId] = PendingTransition(transition, now)
            return false // Worker übernimmt die Bestätigung
        }

        // Pendenter Übergang stimmt mit neuem überein → weiter warten
        return false
    }

    /**
     * Wird vom GeofenceStabilizationWorker nach Ablauf der
     * Stabilisierungszeit aufgerufen.
     */
    fun confirmPending(
        geofenceId: String,
        expectedTransition: GeofenceTransition,
        now: Long
    ): ConfirmationResult {
        val pending = pendingByGeofence[geofenceId]
        if (pending == null) {
            return ConfirmationResult.AlreadyEmitted
        }
        if (pending.transition != expectedTransition) {
            pendingByGeofence.remove(geofenceId)
            return ConfirmationResult.Cancelled
        }

        pendingByGeofence.remove(geofenceId)
        confirmedStateByGeofence[geofenceId] = pending.transition
        return ConfirmationResult.Confirmed
    }

    fun markEmitted(geofenceId: String, now: Long) {
        // Kompatibilität — Bestätigung erfolgt in confirmPending
    }

    fun reset() {
        pendingByGeofence.clear()
        confirmedStateByGeofence.clear()
    }

    private data class PendingTransition(
        val transition: GeofenceTransition,
        val startedAt: Long
    )

    enum class ConfirmationResult {
        Confirmed,
        Cancelled,
        AlreadyEmitted
    }

    companion object {
        val STABILIZATION_MS = 120_000L // 2 Minuten

        fun workName(geofenceId: String, transition: GeofenceTransition): String =
            "${GeofenceStabilizationWorker.WORK_PREFIX}${geofenceId}_${transition.name}"
    }
}