package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AppUsageSampleDao
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import kotlinx.coroutines.flow.Flow

class AppUsageSampleRepositoryImpl(
    private val dao: AppUsageSampleDao
) : AppUsageSampleRepository {
    override fun getAll(): Flow<List<AppUsageSample>> = dao.getAll()
    override fun getByDateRange(start: Long, end: Long): Flow<List<AppUsageSample>> = dao.getByDateRange(start, end)
    override fun getByPackage(pkg: String): Flow<List<AppUsageSample>> = dao.getByPackage(pkg)
    override suspend fun insert(sample: AppUsageSample) = dao.insert(sample)
    override suspend fun insertAll(samples: List<AppUsageSample>) = dao.insertAll(samples)
    override suspend fun deleteOld(cutoff: Long) = dao.deleteOld(cutoff)
}
