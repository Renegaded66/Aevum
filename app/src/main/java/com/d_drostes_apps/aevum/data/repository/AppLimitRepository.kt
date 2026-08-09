package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.AppLimit
import kotlinx.coroutines.flow.Flow

interface AppLimitRepository {
    fun getAll(): Flow<List<AppLimit>>
    fun getByPackage(packageName: String): Flow<AppLimit?>
    suspend fun getByPackageOnce(packageName: String): AppLimit?
    suspend fun upsert(limit: AppLimit)
    suspend fun delete(packageName: String)
}
