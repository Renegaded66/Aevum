package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

interface BucketListRepository {
    fun getActive(): Flow<List<BucketListItem>>
    fun getAll(): Flow<List<BucketListItem>>
    fun getById(id: String): Flow<BucketListItem?>
    suspend fun insert(item: BucketListItem)
    suspend fun insertAll(items: List<BucketListItem>)
    suspend fun update(item: BucketListItem)
    suspend fun delete(id: String)
}