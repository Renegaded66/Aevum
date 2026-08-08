package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.BucketListItem
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
    // M18.43: Schwierigkeitsgrad (1-5 Sterne) für die XP-Belohnung.
    suspend fun setDifficulty(id: String, difficulty: Int, now: Long)
    suspend fun delete(id: String)
}
