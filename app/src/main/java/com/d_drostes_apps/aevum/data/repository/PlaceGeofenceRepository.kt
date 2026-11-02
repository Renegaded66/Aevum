package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface PlaceGeofenceRepository {
    fun getAllEnabled(): Flow<List<PlaceGeofence>>
    fun getAll(): Flow<List<PlaceGeofence>>
    /** M18.114: Alle nicht-gelöschten Geofences (aktiv ODER deaktiviert).
     *  Basis für den DriveEnd-Re-Enter-Check. */
    fun getActiveOrDisabled(): Flow<List<PlaceGeofence>>
    fun getDeleted(): Flow<List<PlaceGeofence>>
    fun getById(id: String): Flow<PlaceGeofence?>
    fun getTagIdsForGeofence(geofenceId: String): Flow<List<String>>
    suspend fun insert(geofence: PlaceGeofence)
    suspend fun insertAll(geofences: List<PlaceGeofence>)
    suspend fun insertWithTags(geofence: PlaceGeofence, tagIds: List<String>)
    suspend fun update(geofence: PlaceGeofence)
    suspend fun softDelete(id: String, now: Long)
    suspend fun delete(id: String)
}

interface TriggerEventRepository {
    fun getAll(): Flow<List<TriggerEvent>>
    fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>>
    fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>>
    fun getById(id: String): Flow<TriggerEvent?>
    suspend fun insert(event: TriggerEvent)
    suspend fun insertAll(events: List<TriggerEvent>)
    suspend fun delete(id: String)
}

interface AutomationSettingsRepository {
    fun get(): Flow<AutomationSettings?>
    /**
     * M35: einmaliger Lesezugriff für Hintergrund-Worker (ohne Flow-
     * Sammlung). Default leitet auf [get] um, damit Test-Fakes unverändert
     * bleiben; die Produktions-Implementierung nutzt den synchronen DAO-Weg.
     */
    suspend fun getSync(): AutomationSettings? = get().first()
    suspend fun upsert(settings: AutomationSettings)
}
