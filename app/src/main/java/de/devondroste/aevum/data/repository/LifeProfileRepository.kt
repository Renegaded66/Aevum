package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.Flow

interface LifeProfileRepository {
    fun getDefault(): Flow<LifeProfile?>
    suspend fun insert(profile: LifeProfile)
    suspend fun update(profile: LifeProfile)
}