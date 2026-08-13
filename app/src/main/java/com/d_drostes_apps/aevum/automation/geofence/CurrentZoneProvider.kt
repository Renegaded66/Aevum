package com.d_drostes_apps.aevum.automation.geofence

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════════════════════════════
// M18.66-FIX3: CURRENT-ZONE PROVIDER
//
// Liefert die Geofence-Zone, in der sich der User gerade befindet
// (oder null = "Abwesend"). Wird vom Dashboard-Zone-Banner konsumiert.
//
// Der Provider hält einen StateFlow, den die UI beobachten kann.
// Aktualisierung: on-demand via checkNow() oder periodisch via
// ProactiveGeofenceCheckWorker (der den Status setzt).
// ══════════════════════════════════════════════════════════════════════

private const val TAG = "CurrentZoneProvider"

@Singleton
class CurrentZoneProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    // M18.66-FIX4: Processor wird direkt aufgerufen, wenn die Zone wechselt
    private val processor: GeofenceTransitionProcessor,
    private val debugLogger: GeofenceDebugLogger
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    data class ZoneInfo(
        val geofence: PlaceGeofence,
        val distanceMeters: Double,
        val updatedAt: Long
    )

    private val _currentZone = MutableStateFlow<ZoneInfo?>(null)
    val currentZone: StateFlow<ZoneInfo?> = _currentZone

    /**
     * Prüft sofort den GPS-Standort gegen alle Geofences und
     * aktualisiert den StateFlow. Wird aufgerufen:
     * - Beim App-Start (sofortige Anzeige im Dashboard)
     * - Durch ProactiveGeofenceCheckWorker (alle 2 Min)
     */
    @SuppressLint("MissingPermission")
    suspend fun checkNow(): ZoneInfo? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Keine Standortberechtigung — Zone nicht ermittelbar")
            return null
        }

        val geofences = try {
            geofenceRepository.getAllEnabled().first().filter { it.deletedAt == null }
        } catch (e: Exception) {
            Log.e(TAG, "Geofences laden fehlgeschlagen: ${e.message}")
            return _currentZone.value
        }

        if (geofences.isEmpty()) {
            _currentZone.value = null
            return null
        }

        val location = try {
            client.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        if (location == null) {
            Log.w(TAG, "Kein GPS-Fix — Zone bleibt unverändert")
            return _currentZone.value
        }

        val matched = findNearestGeofence(location, geofences)
        val result = if (matched != null) {
            val distance = haversineDistance(
                location.latitude, location.longitude,
                matched.latitude, matched.longitude
            )
            ZoneInfo(
                geofence = matched,
                distanceMeters = distance,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            null
        }

        // M18.66-FIX4: Zonenwechsel → Pipeline triggern!
        // Vorher: checkNow() aktualisierte nur den Banner, aber startete
        // nie die Activity. Der Banner zeigte "Arbeit", aber die Pipeline
        // wurde nie aufgerufen → kein Auto-Start.
        // Jetzt: Wenn sich die Zone ändert (drinnen→draußen oder
        // draußen→drinnen), rufen wir processTransition direkt auf.
        val previousZoneId = _currentZone.value?.geofence?.id
        val newZoneId = result?.geofence?.id
        val zoneChanged = previousZoneId != newZoneId

        _currentZone.value = result
        Log.d(TAG, "Zone: ${result?.geofence?.name ?: "Abwesend"} (acc=${location.accuracy}m, changed=$zoneChanged)")

        if (zoneChanged) {
            if (newZoneId != null) {
                // ENTER: Betreten einer Zone
                debugLogger.log("ZONE_PROVIDER", "ENTER: ${result?.geofence?.name} (checkNow)")
                try {
                    processor.processTransition(
                        geofenceId = newZoneId,
                        transition = GeofenceTransition.Enter,
                        occurredAt = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "ENTER processTransition failed: ${e.message}", e)
                }
            } else if (previousZoneId != null) {
                // EXIT: Verlassen einer Zone
                debugLogger.log("ZONE_PROVIDER", "EXIT: $previousZoneId (checkNow)")
                try {
                    processor.processTransition(
                        geofenceId = previousZoneId,
                        transition = GeofenceTransition.Exit,
                        occurredAt = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "EXIT processTransition failed: ${e.message}", e)
                }
            }
        }

        return result
    }

    /** Setzt die Zone direkt (z.B. durch ProactiveGeofenceCheckWorker). */
    fun setZone(geofence: PlaceGeofence?, distanceMeters: Double = 0.0) {
        _currentZone.value = geofence?.let {
            ZoneInfo(it, distanceMeters, System.currentTimeMillis())
        }
    }

    private fun findNearestGeofence(
        location: Location,
        geofences: List<PlaceGeofence>
    ): PlaceGeofence? {
        var nearest: PlaceGeofence? = null
        var nearestDist = Double.MAX_VALUE
        for (g in geofences) {
            val d = haversineDistance(location.latitude, location.longitude, g.latitude, g.longitude)
            if (d < nearestDist) {
                nearestDist = d
                nearest = g
            }
        }
        return nearest?.let {
            if (nearestDist <= it.radiusMeters) it else null
        }
    }

    @Suppress("SameParameterValue")
    private fun hasLocationPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Erdradius in Metern
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}