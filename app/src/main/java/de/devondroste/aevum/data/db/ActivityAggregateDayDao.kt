package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.ActivityAggregateDay
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityAggregateDayDao {
    @Query("SELECT * FROM activity_aggregate_day WHERE date = :date AND timezone_id = :tz")
    fun getByDate(date: String, tz: String): Flow<ActivityAggregateDay?>

    @Query("SELECT * FROM activity_aggregate_day WHERE date >= :start AND date <= :end AND timezone_id = :tz ORDER BY date")
    fun getByDateRange(start: String, end: String, tz: String): Flow<List<ActivityAggregateDay>>

    @Query("SELECT * FROM activity_aggregate_day WHERE date >= :start AND date <= :end AND timezone_id = :tz AND category_id = :catId ORDER BY date")
    fun getByDateRangeAndCategory(start: String, end: String, tz: String, catId: String): Flow<List<ActivityAggregateDay>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(aggregate: ActivityAggregateDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(aggregates: List<ActivityAggregateDay>)

    @Query("DELETE FROM activity_aggregate_day WHERE date < :cutoff")
    suspend fun deleteOld(cutoff: String)
}