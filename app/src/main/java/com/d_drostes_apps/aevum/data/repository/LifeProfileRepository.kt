package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.Flow

interface LifeProfileRepository {
    fun getDefault(): Flow<LifeProfile?>
    suspend fun insert(profile: LifeProfile)
    suspend fun update(profile: LifeProfile)
}