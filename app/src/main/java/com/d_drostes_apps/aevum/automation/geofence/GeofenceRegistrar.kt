package com.d_drostes_apps.aevum.automation.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val settingsRepository: com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository,
    private val debugLogger: GeofenceDebugLogger
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    data class PermissionStatus(
        val foregroundGranted: Boolean,
        val backgroundApplicable: Boolean,
        val backgroundGranted: Boolean
    )

    fun getPermissionStatus(): PermissionStatus {
        val foreground = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bgApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val bgGranted = if (!bgApplicable) true else has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return PermissionStatus(foreground, bgApplicable, bgGranted)
    }

    suspend fun refreshRegisteredGeofences(): GeofenceRegistrationResult {
        // M18.44: ECHTES Gate statt Kosmetik — wenn Geofencing generell
        // deaktiviert ist, werden alle Geofences deregistriert (kein
        // Broadcast, keine Trigger, kein Akku-Verbrauch).
        val settings = settingsRepository.get().first()
        if (settings?.geofencingEnabled == false) {
            debugLogger.log("REGISTRAR", "geofencingEnabled=false → Geofences deregistriert")
            try {
                client.removeGeofences(pendingIntent()).await()
            } catch (_: Exception) { /* bereits deregistriert */ }
            return GeofenceRegistrationResult.Registered(0)
        }
        val perms = getPermissionStatus()
        if (!perms.foregroundGranted) {
            debugLogger.log("REGISTRAR", "Kein Foreground-Standort")
            return GeofenceRegistrationResult.MissingForegroundLocation
        }
        if (!perms.backgroundGranted && perms.backgroundApplicable) {
            debugLogger.log("REGISTRAR", "Background nicht explizit erteilt — Android 14+ kann trotzdem funktionieren")
        }

        val geofences = geofenceRepository.getAllEnabled().first()
            .filter { it.deletedAt == null }
            .take(MAX_ANDROID_GEOFENCES)

        debugLogger.log("REGISTRAR", "Registriere ${geofences.size} Geofences")

        return try {
            GeofenceForegroundService.start(context)
            client.removeGeofences(pendingIntent()).await()
            if (geofences.isNotEmpty()) {
                val request = GeofencingRequest.Builder()
                    // M18.64-FIX (False-Start beim App-Öffnen): KEIN
                    // INITIAL_TRIGGER_ENTER mehr. Vorher lieferte Google
                    // bei jeder Neuregistrierung (App-Start, 6h-Refresh,
                    // Boot) sofort einen ENTER, wenn der User im Geofence
                    // war — mit dem M18.64-Dedup-Fix (ENTER-nach-ENTER
                    // startet jetzt wieder) hätte JEDES App-Öffnen eine
                    // Session gestartet, die DWELL nach 45s bestätigt
                    // hätte (endlose False-Session "Zuhause").
                    // Echte Übergänge (Betreten/Verlassen) liefert GMS
                    // auch ohne Initial-Trigger zuverlässig — auch bei
                    // geschlossener App und nach Neustart.
                    // ENTER + EXIT + DWELL: Google kann nach Reboot/Refresh
                    // nur DWELL liefern. Der Processor dedupliziert DWELL,
                    // nutzt ihn aber als bestätigten Aufenthalt.
                    .addGeofences(geofences.map { place ->
                        Geofence.Builder()
                            .setRequestId(place.id)
                            .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.coerceAtLeast(MIN_RADIUS_METERS))
                            .setTransitionTypes(
                                Geofence.GEOFENCE_TRANSITION_ENTER or
                                Geofence.GEOFENCE_TRANSITION_EXIT or
                                Geofence.GEOFENCE_TRANSITION_DWELL
                            )
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            // M17: NotificationResponsiveness 20s statt 60s.
                            // Schneller für direktes Auto-Tracking. Die 8s-
                            // Stabilisierung im Debouncer fängt Echos ab.
                            .setNotificationResponsiveness(RESPONSIVENESS_MS)
                            // M18.63-CRITICAL (Root Cause "Geofence startet
                            // keine Aufzeichnung"): Ohne LoiteringDelay feuert
                            // Google NIE ein DWELL-Event — DWELL ist nur als
                            // Transition registriert, aber ohne die Loiter-
                            // Schwelle wird es nicht ausgelöst. Der Auto-
                            // Discard-Refresh (M17: Session nach 60s
                            // verwerfen, wenn kein zweiter Enter-Trigger
                            // kommt) hing genau an DWELL/ENTER-Echos, die
                            // der Debouncer als Echo unterdrückt. Ergebnis:
                            // JEDE Auto-Session wurde nach 60s verworfen —
                            // der User sah nie eine Aufzeichnung.
                            // LoiteringDelay 60s: DWELL feuert nach 60s
                            // Aufenthalt und bestätigt die Session.
                            .setLoiteringDelay(LOITERING_DELAY_MS)
                            .build()
                    })
                    .build()
                client.addGeofences(request, pendingIntent()).await()
                geofences.forEach { place ->
                    debugLogger.log("REGISTRAR", "  ${place.name}: r=${place.radiusMeters}m")
                }
                debugLogger.log("REGISTRAR", "${geofences.size} Geofences registriert")
            }
            GeofenceRegistrationResult.Registered(geofences.size)
        } catch (security: SecurityException) {
            debugLogger.log("REGISTRAR", "SecurityException: ${security.message}")
            GeofenceRegistrationResult.SecurityDenied
        } catch (error: Exception) {
            debugLogger.log("REGISTRAR", "Fehler: ${error.message}")
            GeofenceRegistrationResult.Failed(error.message ?: "Geofence Registrierung fehlgeschlagen")
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE_EVENT
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(context, 42, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    fun openBackgroundLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_GEOFENCE_EVENT = "com.d_drostes_apps.aevum.ACTION_GEOFENCE_EVENT"
        private const val MAX_ANDROID_GEOFENCES = 100
        // M17: Mindest-Radius 80m — etwas kleiner als vorher (100m), weil
        // die Stabilisierung jetzt nur 8s ist statt 2 min. Kleinere Radien
        // sind OK, weil der Debouncer den Drift abfängt.
        private const val MIN_RADIUS_METERS = 80f
        // M17: Notification-Responsiveness 20s — schnelle Zustellung an
        // den Receiver für direktes Auto-Tracking. Stabilisierung im
        // Debouncer fängt GPS-Sprünge ab.
        private const val RESPONSIVENESS_MS = 20 * 1000
        // M18.63-CRITICAL: LoiteringDelay für DWELL-Transitions. Ohne
        // diesen Wert liefert Google Play Services NIE ein DWELL-Event
        // (DWELL ist ohne Loiter-Schwelle funktionslos).
        // TIMING (bewusst): 45s Loitering + 8s Stabilisierung = DWELL-
        // Verarbeitung bei ~53s — VOR dem 60s-Auto-Discard. Ein DWELL
        // bei 60s+ würde erst bei ~68s verarbeitet, NACH dem Discard
        // (Session wäre schon verworfen). 45s stellt sicher, dass der
        // Discard-Timer den DWELL-Beweis noch sieht.
        private const val LOITERING_DELAY_MS = 45_000
    }
}

sealed class GeofenceRegistrationResult {
    data class Registered(val count: Int) : GeofenceRegistrationResult()
    data object MissingForegroundLocation : GeofenceRegistrationResult()
    data object MissingBackgroundLocation : GeofenceRegistrationResult()
    data object SecurityDenied : GeofenceRegistrationResult()
    data class Failed(val message: String) : GeofenceRegistrationResult()
}
