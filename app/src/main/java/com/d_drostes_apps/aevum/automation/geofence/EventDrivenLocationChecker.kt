package com.d_drostes_apps.aevum.automation.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M18.23: Event-driven Location Check.
 *
 * Statt 24/7 Geofences bei Google Play Services zu registrieren (was
 * wiederholte False-Trigger bei Neuregistrierung, GPS-Drift und App-Updates
 * erzeugt), wird der Standort NUR geprueft, wenn die Activity Recognition
 * ein relevantes Event meldet (IN_VEHICLE start/stop, ON_FOOT, etc.).
 *
 * Algorithmus:
 *  1. Activity Recognition meldet Event
 *  2. Einmaliger GPS-Fix (FusedLocationProvider, PRIORITY_BALANCED_POWER_ACCURACY)
 *  3. Pruefe ob der Standort in einer gespeicherten Geofence liegt
 *  4. Wenn ja: Triggere ENTER/EXIT wie ein normaler Geofence
 *  5. Wenn nein: UnknownPlaceDetector kann den Standort als "neuer Ort" erfassen
 *
 * Vorteile:
 *  - Keine wiederholten False-Trigger durch Geofence-Neuregistrierung
 *  - GPS nur bei echten Bewegungs-Events, nicht 24/7
 *  - Akkusparender als always-on Geofencing
 *
 * Nachteile (bewusst in Kauf genommen):
 *  - ENTER-Trigger haben eine Verzoegerung von ~10-15s (GPS-Fix-Zeit)
 *  - Wenn Activity Recognition kein Event liefert, wird kein Geofence-Wechsel erkannt
 *    (akzeptabel: der User ist dann wahrscheinlich zu Hause / an einem bekannten Ort)
 */
class EventDrivenLocationChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val debugLogger: GeofenceDebugLogger
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    data class LocationCheckResult(
        val matchedGeofence: PlaceGeofence?,
        val location: Location?,
        val allGeofences: List<PlaceGeofence>
    )

    /**
     * Holt einen einmaligen GPS-Fix und prueft, ob der Standort in einer
     * gespeicherten Geofence liegt. Blockiert bis der Fix da ist (mit Timeout
     * durch die await()-Aufrufe).
     *
     * @return LocationCheckResult mit der gematchten Geofence (oder null)
     */
    @SuppressLint("MissingPermission")
    suspend fun checkCurrentLocationAgainstGeofences(): LocationCheckResult {
        if (!hasLocationPermission()) {
            debugLogger.log("LOC_CHECK", "Keine Standortberechtigung")
            return LocationCheckResult(null, null, emptyList())
        }

        val geofences = geofenceRepository.getAllEnabled().first()
            .filter { it.deletedAt == null }

        if (geofences.isEmpty()) {
            debugLogger.log("LOC_CHECK", "Keine Geofences gespeichert")
            return LocationCheckResult(null, null, emptyList())
        }

        // Einmaliger GPS-Fix mit mittlerer Genauigkeit (akkusparend)
        val location = try {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
            ).await()
        } catch (e: Exception) {
            debugLogger.log("LOC_CHECK", "GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        if (location == null) {
            debugLogger.log("LOC_CHECK", "Kein GPS-Fix verfuegbar")
            return LocationCheckResult(null, null, geofences)
        }

        debugLogger.log("LOC_CHECK", "GPS-Fix: ${location.latitude}, ${location.longitude} (acc=${location.accuracy}m)")

        // Pruefe gegen alle Geofences
        val matched = geofences.minByOrNull { geofence ->
            haversineDistance(
                location.latitude, location.longitude,
                geofence.latitude, geofence.longitude
            )
        }?.let { nearest ->
            val distance = haversineDistance(
                location.latitude, location.longitude,
                nearest.latitude, nearest.longitude
            )
            debugLogger.log(
                "LOC_CHECK",
                "Naechste Geofence: ${nearest.name} (${distance.toInt()}m, radius=${nearest.radiusMeters}m)"
            )
            if (distance <= nearest.radiusMeters) nearest else null
        }

        return LocationCheckResult(matched, location, geofences)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Haversine-Formel fuer die Entfernung zwischen zwei GPS-Koordinaten.
         * Rueckgabe in Metern.
         */
        fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Erdradius in Metern
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}