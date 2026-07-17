package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.PlaceGeofence
import kotlinx.coroutines.flow.Flow

interface PlaceGeofenceRepository {
    fun getAllEnabled(): Flow<List<PlaceGeofence>>
    fun getAll(): Flow<List<PlaceGeofence>>
    fun getById(id: String): Flow<PlaceGeofence?>
    suspend fun insert(geofence: PlaceGeofence)
    suspend fun insertAll(geofences: List<PlaceGeofence>)
    suspend fun update(geofence: PlaceGeofence)
    suspend fun delete(id: String)
}