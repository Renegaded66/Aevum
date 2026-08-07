package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.BucketListItemDao
import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

class BucketListRepositoryImpl(
    private val dao: BucketListItemDao
) : BucketListRepository {

    override fun getAll(): Flow<List<BucketListItem>> = dao.getAll()

    override suspend fun getById(id: String): BucketListItem? = dao.getById(id)

    override suspend fun insert(item: BucketListItem) = dao.insert(item)

    override suspend fun setCompleted(id: String, completed: Boolean, completedAt: Long?, now: Long) =
        dao.setCompleted(id, completed, completedAt, now)

    // M18.43: Schwierigkeitsgrad (1-5 Sterne) für die XP-Belohnung.
    override suspend fun setDifficulty(id: String, difficulty: Int, now: Long) =
        dao.setDifficulty(id, difficulty, now)

    override suspend fun delete(id: String) = dao.delete(id)
}
