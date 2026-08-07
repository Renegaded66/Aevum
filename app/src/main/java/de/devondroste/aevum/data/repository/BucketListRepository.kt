package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

/**
 * M18.39: Bucket-List-Repository — angepasst an das neue Schema
 * (completed/completedAt statt status/progress_percent).
 */
interface BucketListRepository {
    fun getAll(): Flow<List<BucketListItem>>
    suspend fun getById(id: String): BucketListItem?
    suspend fun insert(item: BucketListItem)
    suspend fun setCompleted(id: String, completed: Boolean, completedAt: Long?, now: Long)
    suspend fun delete(id: String)
}
