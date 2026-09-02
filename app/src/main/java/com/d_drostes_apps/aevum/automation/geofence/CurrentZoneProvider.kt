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

        // ═════════════════════════════════════════════════════════════════
        // M18.87: PRESENCE-SAMPLER — die GPS-Wahrheit als Trigger-Evidenz.
        //
        // Problem: Die Orts-Timeline leitet Visits NUR aus ENTER/EXIT-Events
        // ab. Die drei Fälle "ich bin dauerhaft Zuhause" erzeugen KEINE
        // Evidenz:
        //   1. Zuhause-ENTER gestern, heute kein (GMS-)Event — der
        //      M18.48-Dedup unterdrückt ENTER-nach-ENTER bewusst, und ein
        //      DWELL-Echo kommt nur bei Neuregistrierung.
        //   2. Home hat kein autoStartActivityTypeId → der direkte Pfad
        //      schreibt ausschließlich bei Zonenwechsel Trigger.
        //   3. Prozesses-Tod verliert den Zustand komplett.
        // Resultat: "Heute nirgendwo", obwohl GPS längst weiß "in Zuhause".
        //
        // Lösung: Bei JEDEM checkNow() (ProactiveGeofenceCheckWorker ~2 min
        // + Dashboard-Aufrufe) den Zonen-Zustand als PRESENCE_TRIGGER
        // persistieren — INSERT beim Betreten, CLOSE beim Verlassen (nach
        // 2-Check-Bestätigung gegen Rand-Flackern). Die Engine leitet
        // daraus "Zuhause seit X, läuft gerade" ab, auch tageübergreifend.
        // Quelle "presence_sampler" + Typen PRESENCE_* →
        //   • TriggerPairCandidateRuleEngine ignoriert sie (keine
        //     Travel-Doppel-Candidates),
        //   • GeofenceTransitionProcessor wird defensiv nie für sie
        //     aufgerufen (Skip-Guard),
        //   • PlaceTimelineEngine sieht contains("ENTER"/"EXIT").
        // ═════════════════════════════════════════════════════════════════
        try {
            recordPresenceEvidence(newZoneId, result, System.currentTimeMillis())
        } catch (e: Exception) {
            // Presence ist Zusatz-Evidenz — ein Fehler darf den
            // Auto-Start/Stop-Pfad niemals stören.
            Log.w(TAG, "M18.87 Presence-Sampling fehlgeschlagen: ${e.message}")
        }

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
                            // M18.93v10-FIX: forceFinishForAuto() vor
                            // start() entfernt — start() löst die alte
                            // Session selbst auf (Kürzen bei Overlap,
                            // Beenden bei keiner Überlappung).
                            // M18.66-FIX9: Titel = ActivityType-Name, NICHT
                            // Geofence-Name. Der User hat in der Geofence-
                            // Automatisierung "Fitness" als Activity ausgewählt
                            // → die gestartete Activity soll "Fitness" heißen
                            // und die Kategorie des ActivityTypes haben.
                            // Vorher: title = gf.name ("Gym") → Activity hieß
                            // "Gym" ohne Kategorie. Jetzt: title = "Fitness"
                            // mit der Kategorie, die dem ActivityType
                            // zugewiesen ist.
                            val session = liveActivityManager.start(
                                activityTypeId = autoType,
                                title = null,
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

    // ═════════════════════════════════════════════════════════════════════
    // M18.87: PRESENCE-EVIDENZ
    // ═════════════════════════════════════════════════════════════════════

    companion object {
        /** Source-Marker der Presence-Trigger (Engine/Processor-Filter). */
        const val TRIGGER_SOURCE = "presence_sampler"
        const val TYPE_ENTER = "PRESENCE_ENTER"
        const val TYPE_EXIT = "PRESENCE_EXIT"

        /** Checks außerhalb der Zone, bevor die Presence geschlossen wird
         *  (Rand-Flackern: ein GPS-Drift-Fix knapp außerhalb darf eine
         *  tagelange Anwesenheit nicht beenden). */
        const val PRESENCE_CONFIRM_MISSES = 2

        private const val KEY_PRESENCE_GEOFENCE = "presence_geofence_id"
        private const val KEY_PRESENCE_START = "presence_started_at"
        private const val KEY_PRESENCE_MISSES = "presence_misses"
    }

    /**
     * Persistiert den GPS-Zonen-Zustand als Trigger-Evidenz (M18.87).
     *
     * Lifecycle (State in SharedPreferences, überlebt Prozess-Tod):
     *  - Kein offener Zustand + Zone betreten → PRESENCE_ENTER + Öffnen.
     *  - Gleiche Zone → kein Write (Stille ist der Normalfall).
     *  - Andere Zone → PRESENCE_EXIT (alt) + PRESENCE_ENTER (neu), sofort
     *    (der Check-Zyklen-Abstand IST schon das Flacker-Filter).
     *  - null (außerhalb aller Zonen) → erst nach [PRESENCE_CONFIRM_MISSES]
     *    Bestätigungen PRESENCE_EXIT schreiben.
     *
     * Die Trigger sind insert-only-Evidenz (keine UPDATEs — Trigger sind
     * unveränderliche Fakten). Die Engine kombiniert ENTER+EXIT zum
     * Intervall, identisch zum GEOFENCE_ENTER/EXIT-Muster.
     */
    private suspend fun recordPresenceEvidence(
        newZoneId: String?,
        result: ZoneInfo?,
        t: Long
    ) {
        val openGeofenceId = prefs.getString(KEY_PRESENCE_GEOFENCE, null)

        if (openGeofenceId == null) {
            // Kein offener Presence-Zustand.
            if (newZoneId != null && result != null) {
                triggerRepository.insert(presenceTrigger(TYPE_ENTER, result.geofence, t))
                savePresenceOpen(newZoneId, t)
                Log.d(TAG, "M18.87: PRESENCE_ENTER ${result.geofence.name} @ $t")
            }
            return
        }

        if (newZoneId == openGeofenceId) {
            // Gleiche Zone, dauerhaft da → KEIN Write. Es gibt keinen
            // Zeitstempel-Beweis "immer noch da" — die Engine leitet die
            // Anwesenheit bis zum nächsten Event bzw. now ab. (Ein Trigger
            // pro Check würde die Evidenz fluten und enterAt dauernd
            // verschieben.)
            return
        }

        if (newZoneId != null) {
            // Zonenwechsel: alte Presence schließen, neue sofort öffnen.
            val prevGeofence = geofenceRepository.getById(openGeofenceId).first()
            if (prevGeofence != null) {
                triggerRepository.insert(presenceTrigger(TYPE_EXIT, prevGeofence, t))
            }
            triggerRepository.insert(presenceTrigger(TYPE_ENTER, result!!.geofence, t))
            savePresenceOpen(newZoneId, t)
            Log.d(TAG, "M18.87: Zonenwechsel → PRESENCE_EXIT/ENTER @ $t")
            return
        }

        // newZoneId == null bei offenem Zustand → außerhalb aller Zonen.
        // EIN Check ist Rand-Flackern; der zweite bestätigt.
        val misses = prefs.getInt(KEY_PRESENCE_MISSES, 0) + 1
        if (misses < PRESENCE_CONFIRM_MISSES) {
            prefs.edit().putInt(KEY_PRESENCE_MISSES, misses).apply()
            return
        }
        // Bestätigt außerhalb → Presence schließen.
        Log.d(TAG, "M18.87: außerhalb bestätigt ($misses Checks) → PRESENCE_EXIT @ $t")
        clearPresenceOpen()
        val prevGeofence = geofenceRepository.getById(openGeofenceId).first()
        if (prevGeofence != null) {
            triggerRepository.insert(presenceTrigger(TYPE_EXIT, prevGeofence, t))
        }
    }

    private fun presenceTrigger(
        type: String,
        geofence: PlaceGeofence,
        at: Long
    ) = TriggerEvent(
        id = UUID.randomUUID().toString(),
        occurredAt = at,
        type = type,
        source = TRIGGER_SOURCE,
        confidence = 0.55f,
        geofenceId = geofence.id,
        detectionEventId = null,
        metadataJson = """{"geofenceName":"${geofence.name}","reason":"presence_sampler"}""",
        anchorQuality = "HIGH"
    )

    private fun savePresenceOpen(geofenceId: String, startedAt: Long) {
        prefs.edit().apply {
            putString(KEY_PRESENCE_GEOFENCE, geofenceId)
            putLong(KEY_PRESENCE_START, startedAt)
            putInt(KEY_PRESENCE_MISSES, 0)
            apply()
        }
    }

    private fun clearPresenceOpen() {
        prefs.edit().apply {
            remove(KEY_PRESENCE_GEOFENCE)
            remove(KEY_PRESENCE_START)
            putInt(KEY_PRESENCE_MISSES, 0)
            apply()
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