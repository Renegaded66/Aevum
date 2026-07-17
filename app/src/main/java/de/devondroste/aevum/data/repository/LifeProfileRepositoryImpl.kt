package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.LifeProfileDao
import de.devondroste.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.Flow

class LifeProfileRepositoryImpl(
    private val dao: LifeProfileDao
) : LifeProfileRepository {

    override fun getDefault(): Flow<LifeProfile?> = dao.getDefault()

    override suspend fun insert(profile: LifeProfile) = dao.insert(profile)

    override suspend fun update(profile: LifeProfile) = dao.touch(profile.id, System.currentTimeMillis())
}