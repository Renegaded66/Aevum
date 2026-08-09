package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AppLimitDao
import com.d_drostes_apps.aevum.data.model.AppLimit
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class AppLimitRepositoryImpl @Inject constructor(
    private val dao: AppLimitDao
) : AppLimitRepository {
    override fun getAll(): Flow<List<AppLimit>> = dao.getAll()
    override fun getByPackage(packageName: String): Flow<AppLimit?> = dao.getByPackage(packageName)
    override suspend fun getByPackageOnce(packageName: String): AppLimit? = dao.getByPackageOnce(packageName)
    override suspend fun upsert(limit: AppLimit) = dao.upsert(limit)
    override suspend fun delete(packageName: String) = dao.delete(packageName)
}
