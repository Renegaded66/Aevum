package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.GarminDao
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.model.GarminDailySummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.58: Repository für Garmin-Daten (Tageszusammenfassung + Aktivitäten).
 */
interface GarminRepository {
    fun getSummaryByDate(date: String): Flow<GarminDailySummary?>
    fun getSummariesFrom(start: String): Flow<List<GarminDailySummary>>
    fun getSummariesByRange(start: String, end: String): Flow<List<GarminDailySummary>>
    suspend fun upsertSummary(summary: GarminDailySummary)
    suspend fun getActivityByExternalId(externalId: String): GarminActivity?
    fun getActivitiesByRange(start: Long, end: Long): Flow<List<GarminActivity>>
    suspend fun upsertActivity(activity: GarminActivity)
}

@Singleton
class GarminRepositoryImpl @Inject constructor(
    private val dao: GarminDao
) : GarminRepository {
    override fun getSummaryByDate(date: String): Flow<GarminDailySummary?> = dao.getByDate(date)
    override fun getSummariesFrom(start: String): Flow<List<GarminDailySummary>> = dao.getFrom(start)
    override fun getSummariesByRange(start: String, end: String): Flow<List<GarminDailySummary>> = dao.getByDateRange(start, end)
    override suspend fun upsertSummary(summary: GarminDailySummary) = dao.upsert(summary)
    override suspend fun getActivityByExternalId(externalId: String): GarminActivity? = dao.getByExternalId(externalId)
    override fun getActivitiesByRange(start: Long, end: Long): Flow<List<GarminActivity>> = dao.getByRange(start, end)
    override suspend fun upsertActivity(activity: GarminActivity) = dao.upsertActivity(activity)
}
