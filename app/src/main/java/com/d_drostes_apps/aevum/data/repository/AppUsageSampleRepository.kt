package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.AppUsageSample
import kotlinx.coroutines.flow.Flow

interface AppUsageSampleRepository {
    fun getAll(): Flow<List<AppUsageSample>>
    fun getByDateRange(start: Long, end: Long): Flow<List<AppUsageSample>>
    fun getByPackage(pkg: String): Flow<List<AppUsageSample>>
    suspend fun insert(sample: AppUsageSample)
    suspend fun insertAll(samples: List<AppUsageSample>)
    suspend fun deleteOld(cutoff: Long)
}
