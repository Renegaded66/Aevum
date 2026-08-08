package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.db.PlaceGeofenceDao
import com.d_drostes_apps.aevum.data.db.TriggerEventDao
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import kotlinx.coroutines.flow.Flow

class PlaceGeofenceRepositoryImpl(
    private val dao: PlaceGeofenceDao
) : PlaceGeofenceRepository {

    override fun getAllEnabled(): Flow<List<PlaceGeofence>> = dao.getEnabled()
    override fun getAll(): Flow<List<PlaceGeofence>> = dao.getAll()
    override fun getDeleted(): Flow<List<PlaceGeofence>> = dao.getDeleted()
    override fun getById(id: String): Flow<PlaceGeofence?> = dao.getById(id)
    override fun getTagIdsForGeofence(geofenceId: String): Flow<List<String>> = dao.getTagIdsForGeofence(geofenceId)
    override suspend fun insert(geofence: PlaceGeofence) = dao.insert(geofence)
    override suspend fun insertAll(geofences: List<PlaceGeofence>) = dao.insertAll(geofences)
    override suspend fun insertWithTags(geofence: PlaceGeofence, tagIds: List<String>) = dao.insertWithTags(geofence, tagIds)
    override suspend fun update(geofence: PlaceGeofence) = dao.update(geofence)
    override suspend fun softDelete(id: String, now: Long) = dao.softDelete(id, now)
    override suspend fun delete(id: String) = dao.delete(id)
}

class TriggerEventRepositoryImpl(
    private val dao: TriggerEventDao
) : TriggerEventRepository {
    override fun getAll(): Flow<List<TriggerEvent>> = dao.getAll()
    override fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>> = dao.getByDateRange(start, end)
    override fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>> = dao.getByGeofenceId(geofenceId)
    override fun getById(id: String): Flow<TriggerEvent?> = dao.getById(id)
    override suspend fun insert(event: TriggerEvent) = dao.insert(event)
    override suspend fun insertAll(events: List<TriggerEvent>) = dao.insertAll(events)
    override suspend fun delete(id: String) = dao.delete(id)
}

class AutomationSettingsRepositoryImpl(
    private val dao: AutomationSettingsDao
) : AutomationSettingsRepository {
    override fun get(): Flow<AutomationSettings?> = dao.get()
    override suspend fun upsert(settings: AutomationSettings) = dao.upsert(settings)
}
