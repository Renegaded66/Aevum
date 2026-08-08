package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.LifeProfileDao
import com.d_drostes_apps.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.Flow

class LifeProfileRepositoryImpl(
    private val dao: LifeProfileDao
) : LifeProfileRepository {

    override fun getDefault(): Flow<LifeProfile?> = dao.getDefault()

    override suspend fun insert(profile: LifeProfile) = dao.insert(profile)

    override suspend fun update(profile: LifeProfile) = dao.touch(profile.id, System.currentTimeMillis())
}