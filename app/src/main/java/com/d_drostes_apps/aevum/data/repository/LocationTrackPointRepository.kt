package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.LocationTrackPoint
import kotlinx.coroutines.flow.Flow

/**
 * M18.86: Repository für verdichtete GPS-Track-Punkte (ADR-0030).
 * Dünner Daten-Zugriff über den DAO — keine Business-Logik (die liegt im
 * TrackRecorder-Service bzw. der Timeline-Engine).
 */
interface LocationTrackPointRepository {
    fun getAll(): Flow<List<LocationTrackPoint>>
    suspend fun getBySession(sessionId: String): List<LocationTrackPoint>
    suspend fun getByTimeRange(fromMs: Long, toMs: Long): List<LocationTrackPoint>
    suspend fun insertAll(points: List<LocationTrackPoint>)
    suspend fun deleteOlderThan(cutoff: Long)
    suspend fun count(): Int
}