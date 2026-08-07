package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

/**
 * M18.39: Bucket-List-DAO — angepasst an das neue Schema.
 * (Basis: M2-DAO, erweitert um completed/completedAt-Logik.)
 */
@Dao
interface BucketListItemDao {
    @Query("SELECT * FROM bucket_list_item ORDER BY completed ASC, created_at DESC")
    fun getAll(): Flow<List<BucketListItem>>

    @Query("SELECT * FROM bucket_list_item WHERE id = :id")
    suspend fun getById(id: String): BucketListItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BucketListItem)

    @Query("UPDATE bucket_list_item SET completed = :completed, completed_at = :completedAt, updated_at = :now WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean, completedAt: Long?, now: Long)

    @Query("DELETE FROM bucket_list_item WHERE id = :id")
    suspend fun delete(id: String)
}
