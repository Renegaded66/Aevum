package com.d_drostes_apps.aevum.automation.geofence

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════════════════════════════
// M18.66-FIX7: CURRENT-ZONE PROVIDER — DIREKTER AUTO-START
//
// Vorher (FIX4-6): checkNow() rief processor.processTransition() auf.
// Der Processor hat 7 Schichten (Dedup, SleepShield, Trigger-Erzeugung,
// Auto-Discard, DWELL-Bestätigung...) — jede kann den Start silently
// blockieren. Resultat: Kein Trigger, kein Auto-Start, trotz korrekter
// Zonenerkennung.
//
// Jetzt: checkNow() ruft LiveActivityManager.start() DIREKT auf — kein
// Processor, kein Dedup, kein SleepShield. Wenn der User eine Zone
// betritt und autoStartActivityTypeId gesetzt ist → Activity startet.
// Punkt. Wenn er sie verlässt → Activity stoppt. Punkt.
//
// Der Processor wird weiterhin für GMS-Geofence-Events aufgerufen
// (Receiver-Pipeline), aber checkNow() ist der direkte Pfad.
// ══════════════════════════════════════════════════════════════════════

private const val TAG = "CurrentZoneProvider"

@Singleton
class CurrentZoneProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val triggerRepository: TriggerEventRepository,
    private val liveActivityManager: LiveActivityManager
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    data class ZoneInfo(
        val geofence: PlaceGeofence,
        val distanceMeters: Double,
        val updatedAt: Long
    )

    private val _currentZone = MutableStateFlow<ZoneInfo?>(null)
    val currentZone: StateFlow<ZoneInfo?> = _currentZone

    // M18.66-FIX7: Debug-Info für den Banner — der User kann sehen,
    // was passiert, und mir die Werte geben.
    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo

    private val prefs by lazy {
        context.getSharedPreferences("aevum_zone_state", Context.MODE_PRIVATE)
    }
    private fun loadPreviousZoneId(): String? = prefs.getString("prev_zone_id", null)
    private fun savePreviousZoneId(id: String?) {
        prefs.edit().apply {
            if (id != null) putString("prev_zone_id", id) else remove("prev_zone_id")
        }.apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun checkNow(): ZoneInfo? {
        if (!hasLocationPermission()) {
            _debugInfo.value = "❌ Keine Standort-Berechtigung"
            Log.w(TAG, "Keine Standortberechtigung — Zone nicht ermittelbar")
            return null
        }

        val geofences = try {
            geofenceRepository.getAllEnabled().first().filter { it.deletedAt == null }
        } catch (e: Exception) {
            _debugInfo.value = "❌ Geofences laden fehlgeschlagen: ${e.message}"
            Log.e(TAG, "Geofences laden fehlgeschlagen: ${e.message}")
            return _currentZone.value
        }

        if (geofences.isEmpty()) {
            _debugInfo.value = "❌ Keine Geofences in DB"
            _currentZone.value = null
            return null
        }

        val location = try {
            client.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).await()
        } catch (e: Exception) {
            _debugInfo.value = "❌ GPS-Fix fehlgeschlagen: ${e.message}"
            Log.w(TAG, "GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        if (location == null) {
            _debugInfo.value = "❌ Kein GPS-Fix"
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

        val previousZoneId = loadPreviousZoneId()
        val newZoneId = result?.geofence?.id
        val zoneChanged = previousZoneId != newZoneId

        _currentZone.value = result

        // M18.66-FIX7: DIREKTER AUTO-START — kein Processor, kein Dedup.
        // Wenn der User eine Zone betritt und autoStartActivityTypeId
        // gesetzt ist → Activity starten. Wenn er sie verlässt → stoppen.
        if (zoneChanged) {
            savePreviousZoneId(newZoneId)
            val now = System.currentTimeMillis()

            if (newZoneId != null && result != null) {
                // ═══ ENTER: Zone betreten ═══
                val gf = result.geofence
                val autoType = gf.autoStartActivityTypeId
                _debugInfo.value = "ENTER: ${gf.name} | autoStart=$autoType | prev=$previousZoneId"

                if (autoType != null) {
                    try {
                        val existing = liveActivityManager.liveSession.value
                        val isSameActivity = existing != null &&
                            existing.isLive &&
                            existing.activityTypeId == autoType

                        if (!isSameActivity) {
                            // Andere Session beenden, neue starten.
                            if (existing != null && existing.isLive) {
                                liveActivityManager.forceFinishForAuto()
                            }
                            // M18.66-FIX7: DIREKTER start() — kein Auto-Discard,
                            // kein Processor. Die Session startet und bleibt.
                            val session = liveActivityManager.start(
                                activityTypeId = autoType,
                                title = gf.name,
                                sourceType = "GEOFENCE_AUTO",
                                sourceTriggerId = null
                            )
                            LiveActivityService.start(context)
                            Log.d(TAG, "✅ Auto-Start: ${gf.name} → ${session.id} (type=$autoType)")

                            // Trigger direkt schreiben — kein Processor-Umweg.
                            triggerRepository.insert(
                                TriggerEvent(
                                    id = UUID.randomUUID().toString(),
                                    occurredAt = now,
                                    type = "GEOFENCE_ENTER",
                                    source = "geofence_auto",
                                    confidence = 1.0f,
                                    geofenceId = gf.id,
                                    detectionEventId = null,
                                    metadataJson = """{"geofenceName":"${gf.name}","activityTypeId":"$autoType","reason":"direct_auto_start"}""",
                                    anchorQuality = "HIGH"
                                )
                            )
                            _debugInfo.value = "✅ Auto-Start: ${gf.name} → $autoType (Session ${session.id.take(8)})"
                        } else {
                            Log.d(TAG, "Auto-Start: ${gf.name} läuft bereits ($autoType)")
                            _debugInfo.value = "ℹ️ ${gf.name} läuft bereits ($autoType)"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-Start fehlgeschlagen: ${e.message}", e)
                        _debugInfo.value = "❌ Auto-Start fehlgeschlagen: ${e.message}"
                    }
                } else {
                    Log.d(TAG, "ENTER: ${gf.name} — kein autoStartActivityTypeId")
                    _debugInfo.value = "ENTER: ${gf.name} | autoStart=NULL (kein Auto-Start konfiguriert)"
                }
            } else if (previousZoneId != null) {
                // ═══ EXIT: Zone verlassen ═══
                val prevGf = geofences.find { it.id == previousZoneId }
                val autoType = prevGf?.autoStartActivityTypeId
                _debugInfo.value = "EXIT: ${prevGf?.name ?: previousZoneId} | autoStop=$autoType"

                if (autoType != null) {
                    try {
                        val existing = liveActivityManager.liveSession.value
                        if (existing != null && existing.isLive &&
                            existing.activityTypeId == autoType &&
                            existing.sourceType == "GEOFENCE_AUTO"
                        ) {
                            liveActivityManager.stop()
                            Log.d(TAG, "✅ Auto-Stop: ${prevGf?.name} → Session beendet")

                            triggerRepository.insert(
                                TriggerEvent(
                                    id = UUID.randomUUID().toString(),
                                    occurredAt = now,
                                    type = "GEOFENCE_EXIT",
                                    source = "geofence_auto",
                                    confidence = 1.0f,
                                    geofenceId = previousZoneId,
                                    detectionEventId = null,
                                    metadataJson = """{"geofenceName":"${prevGf?.name}","activityTypeId":"$autoType","reason":"direct_auto_stop"}""",
                                    anchorQuality = "HIGH"
                                )
                            )
                            _debugInfo.value = "✅ Auto-Stop: ${prevGf?.name} → Session beendet"
                        } else {
                            Log.d(TAG, "Auto-Stop: Keine passende Live-Session (existing=$existing)")
                            _debugInfo.value = "EXIT: ${prevGf?.name} | keine passende Session"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-Stop fehlgeschlagen: ${e.message}", e)
                        _debugInfo.value = "❌ Auto-Stop fehlgeschlagen: ${e.message}"
                    }
                }
            }
        } else {
            // Kein Zonenwechsel — Debug-Info aktualisieren.
            val gf = result?.geofence
            _debugInfo.value = if (gf != null) {
                "Zone: ${gf.name} | autoStart=${gf.autoStartActivityTypeId ?: "NULL"} | unchanged"
            } else {
                "Abwesend | unchanged"
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
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}