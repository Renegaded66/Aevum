package com.d_drostes_apps.aevum.automation.unknownplace

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.UnknownPlaceSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * M17.2: Unknown Place Detector.
 *
 * Periodischer Worker (alle 5 min via WorkManager). Algorithmus:
 *
 *  1) Letzten bekannten Standort holen (FusedLocationProvider).
 *  2) In SharedPreferences (`unknown_place_state`) merken wir uns die
 *     letzte Position + den Zeitpunkt, an dem der User dort sesshaft
 *     wurde. Beim ersten Snapshot: `firstObservedAt = now`, `lastLatLng`
 *     = aktuelle Position.
 *  3) Beim zweiten Snapshot:
 *     a) Wenn neue Position innerhalb POSITION_TROLLEYANCE_M (50m) der
 *        letzten: "dort geblieben" → `firstObservedAt` bleibt.
 *     b) Sonst: Position hat sich signifikant geändert → letzten
 *        sesshaften Zeitraum "abschließen" (`absLastObservedAt =
 *        now`), `firstObservedAt` neu setzen.
 *  4) Wenn `now - firstObservedAt > MIN_DURATION_MS` (15 min) UND
 *     aktueller Standort außerhalb aller Geofences:
 *     → UnknownPlaceSession erzeugen, falls noch keine offene für diese
 *       Position existiert.
 *  5) Wenn User in einen Geofence eintritt: alle offenen Unknown-Place-
 *     Einträge in der Nähe (≤ 200m) schließen (Dismissed, kein
 *     Geofence-Convert — das wäre eine Annahme über die Intention).
 *
 * SharedPreferences ist hier OK, weil die Information ephemeral ist
 * (nur für den laufenden Detection-Process relevant) und nicht zu
 * den Nutzer-Daten gehört.
 */
class UnknownPlaceDetectorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun unknownPlaceRepository(): UnknownPlaceSessionRepository
        fun geofenceRepository(): PlaceGeofenceRepository
    }

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // M17.2: Permission ist Voraussetzung. Wenn der User sie entzogen
        // hat, einfach skip — kein Crash.
        if (!hasFineLocation(ctx) && !hasCoarseLocation(ctx)) {
            Log.d(TAG, "Kein Location-Permission → skip")
            return Result.success()
        }

        val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
        val unknownRepo = deps.unknownPlaceRepository()
        val geofenceRepo = deps.geofenceRepository()

        // 1) Aktueller Standort
        val current = fetchLastLocation(ctx) ?: run {
            Log.d(TAG, "Kein Standort verfügbar → skip")
            return Result.success()
        }

        // 2) Geofences
        val geofences = geofenceRepo.getAllEnabled().first()
            .filter { it.deletedAt == null }

        // 3) In Geofence? → nahegelegene offene Unknown-Place-Einträge schließen
        val insideGeofence = geofences.any {
            distanceMeters(current, it.latitude, it.longitude) <= it.radiusMeters
        }
        if (insideGeofence) {
            closeNearbyOpenEntries(unknownRepo, current)
            // Reset Sesshaft-Tracking — wir sind jetzt "zu Hause" / in Geofence
            clearState(ctx)
            return Result.success()
        }

        // 4) Sesshaft-Tracking in SharedPreferences
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val state = readState(prefs)

        val newFirstObservedAt: Long = if (state.latitude == null || state.longitude == null) {
            // Erster Snapshot überhaupt
            now
        } else {
            val dist = distanceMeters(current, state.latitude, state.longitude)
            if (dist <= POSITION_TROLLEYANCE_M) {
                // Geblieben — firstObservedAt bleibt
                state.firstObservedAt
            } else {
                // Bewegt — neuen Zeitraum starten
                now
            }
        }

        writeState(prefs, current.latitude, current.longitude, newFirstObservedAt)

        // 5) Langlebig genug an einem Ort?
        val stableDurationMs = now - newFirstObservedAt
        if (stableDurationMs < MIN_DURATION_MS) {
            Log.d(TAG, "An Ort seit ${stableDurationMs / 1000}s (noch < ${MIN_DURATION_MS / 1000}s) → skip")
            return Result.success()
        }

        // 6) Existiert schon ein offener Eintrag in der Nähe? Wenn ja, nichts machen.
        val open = unknownRepo.getAll().first().filter { !it.resolved }
        val alreadyKnown = open.any {
            distanceMeters(current, it.latitude, it.longitude) < POSITION_TROLLEYANCE_M
        }
        if (alreadyKnown) {
            Log.d(TAG, "Bereits offener Unknown-Place-Eintrag in der Nähe → skip")
            return Result.success()
        }

        // 7) Neuen Eintrag erzeugen
        val session = UnknownPlaceSession(
            id = UUID.randomUUID().toString(),
            startAt = newFirstObservedAt,
            endAt = now,
            latitude = current.latitude,
            longitude = current.longitude,
            accuracyMeters = current.accuracy,
            resolved = false,
            createdAt = now
        )
        unknownRepo.insert(session)
        Log.d(TAG, "Neuer unbekannter Ort: ${session.id} @ (${current.latitude}, ${current.longitude}) seit ${stableDurationMs / 60_000}min")
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLastLocation(ctx: Context): Location? {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        return try {
            client.lastLocation.await() ?: client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, null
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "Location-Fetch fehlgeschlagen: ${e.message}")
            null
        }
    }

    private suspend fun closeNearbyOpenEntries(
        repo: UnknownPlaceSessionRepository,
        current: Location
    ) {
        val open = repo.getAll().first().filter { !it.resolved }
        open.filter { distanceMeters(current, it.latitude, it.longitude) < CLOSE_RADIUS_M }
            .forEach { repo.markDismissed(it.id) }
        Log.d(TAG, "Schließe ${open.size} nahegelegene Unknown-Place-Einträge (in Geofence)")
    }

    // ============================================================
    // SharedPreferences helpers
    // ============================================================

    private data class SesshaftState(
        val latitude: Double?,
        val longitude: Double?,
        val firstObservedAt: Long
    )

    private fun readState(prefs: SharedPreferences): SesshaftState {
        val lat = if (prefs.contains(KEY_LAT)) java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_LAT, 0L)
        ) else null
        val lng = if (prefs.contains(KEY_LNG)) java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_LNG, 0L)
        ) else null
        val firstAt = prefs.getLong(KEY_FIRST_OBSERVED, 0L)
        return SesshaftState(lat, lng, firstAt)
    }

    private fun writeState(prefs: SharedPreferences, lat: Double, lng: Double, firstAt: Long) {
        prefs.edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .putLong(KEY_FIRST_OBSERVED, firstAt)
            .apply()
    }

    private fun clearState(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    companion object {
        const val TAG = "UnknownPlaceDetector"
        const val WORK_NAME = "aevum.unknown_place.detect"
        // M17.2: 5 min — Minimum-Intervall das WorkManager erlaubt.
        const val WORK_INTERVAL_MINUTES = 5L

        // M17.2: 15 min. Kurz genug für echte Aufenthalte (Restaurant,
        // Arzttermin), lang genug um GPS-Drift am Geofence-Rand zu ignorieren.
        const val MIN_DURATION_MS = 15 * 60 * 1000L
        // 50m = Standard-Phone-GPS-Genauigkeit.
        const val POSITION_TROLLEYANCE_M = 50f
        // 200m beim Schließen in Geofence — großzügig, damit auch
        // "fast am Geofence"-Einträge sauber beendet werden.
        const val CLOSE_RADIUS_M = 200f

        private const val PREFS_NAME = "aevum_unknown_place_state"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LNG = "last_lng"
        private const val KEY_FIRST_OBSERVED = "first_observed_at"

        private fun hasFineLocation(ctx: Context): Boolean =
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                )

        private fun hasCoarseLocation(ctx: Context): Boolean =
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
                )

        /**
         * Haversine-Distanz in Metern via [Location.distanceBetween].
         */
        fun distanceMeters(loc: Location, lat: Double, lon: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
            return results[0]
        }
    }
}

