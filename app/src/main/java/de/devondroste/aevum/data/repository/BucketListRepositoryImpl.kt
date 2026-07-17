package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.BucketListItemDao
import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

class BucketListRepositoryImpl(
    private val dao: BucketListItemDao
) : BucketListRepository {

    override fun getActive(): Flow<List<BucketListItem>> = dao.getByStatus("IN_PROGRESS")

    override fun getAll(): Flow<List<BucketListItem>> = dao.getAll()

    override fun getById(id: String): Flow<BucketListItem?> = dao.getById(id)

    override suspend fun insert(item: BucketListItem) = dao.insert(item)

    override suspend fun insertAll(items: List<BucketListItem>) = dao.insertAll(items)

    override suspend fun update(item: BucketListItem) = dao.touch(item.id, System.currentTimeMillis())

    override suspend fun delete(id: String) = dao.delete(id)
}