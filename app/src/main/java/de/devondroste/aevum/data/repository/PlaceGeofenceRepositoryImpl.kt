package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.PlaceGeofenceDao
import de.devondroste.aevum.data.model.PlaceGeofence
import kotlinx.coroutines.flow.Flow

class PlaceGeofenceRepositoryImpl(
    private val dao: PlaceGeofenceDao
) : PlaceGeofenceRepository {

    override fun getAllEnabled(): Flow<List<PlaceGeofence>> = dao.getEnabled()

    override fun getAll(): Flow<List<PlaceGeofence>> = dao.getAll()

    override fun getById(id: String): Flow<PlaceGeofence?> = dao.getById(id)

    override suspend fun insert(geofence: PlaceGeofence) = dao.insert(geofence)

    override suspend fun insertAll(geofences: List<PlaceGeofence>) = dao.insertAll(geofences)

    override suspend fun update(geofence: PlaceGeofence) = dao.update(geofence)

    override suspend fun delete(id: String) = dao.delete(id)
}