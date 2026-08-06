package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.UnknownPlaceSession
import kotlinx.coroutines.flow.Flow

interface UnknownPlaceSessionRepository {
    fun getOpen(): Flow<List<UnknownPlaceSession>>
    fun getAll(): Flow<List<UnknownPlaceSession>>
    suspend fun getById(id: String): UnknownPlaceSession?
    suspend fun countOpen(): Int
    suspend fun insert(session: UnknownPlaceSession)
    suspend fun markNamed(id: String, name: String)
    suspend fun markConverted(id: String, geofenceId: String)
    suspend fun markDismissed(id: String)
    suspend fun delete(id: String)
}
