package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.BucketListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface BucketListItemDao {
    @Query("SELECT * FROM bucket_list_item ORDER BY CASE status WHEN 'IN_PROGRESS' THEN 0 WHEN 'PLANNED' THEN 1 WHEN 'IDEA' THEN 2 ELSE 3 END, target_date")
    fun getAll(): Flow<List<BucketListItem>>

    @Query("SELECT * FROM bucket_list_item WHERE status = :status ORDER BY target_date")
    fun getByStatus(status: String): Flow<List<BucketListItem>>

    @Query("SELECT * FROM bucket_list_item WHERE id = :id")
    fun getById(id: String): Flow<BucketListItem?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BucketListItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BucketListItem>)

    @Query("UPDATE bucket_list_item SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM bucket_list_item WHERE id = :id")
    suspend fun delete(id: String)
}