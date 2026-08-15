package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AppTrackingEntryDao
import com.d_drostes_apps.aevum.data.model.AppTrackingEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTrackingEntryRepository @Inject constructor(
    private val dao: AppTrackingEntryDao
) {
    fun getAll(): Flow<List<AppTrackingEntry>> = dao.getAll()

    suspend fun getByPackageOnce(packageName: String): AppTrackingEntry? =
        dao.getByPackageOnce(packageName)

    suspend fun getEnabledOnce(): List<AppTrackingEntry> = dao.getEnabledOnce()

    suspend fun upsert(entry: AppTrackingEntry) = dao.upsert(entry)

    suspend fun delete(packageName: String) = dao.delete(packageName)
}
