package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.Todo
import de.devondroste.aevum.data.model.TodoCompletion
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM todo WHERE active = 1 ORDER BY created_at DESC")
    fun getActive(): Flow<List<Todo>>

    @Query("SELECT * FROM todo ORDER BY created_at DESC")
    fun getAll(): Flow<List<Todo>>

    @Query("SELECT * FROM todo WHERE id = :id")
    suspend fun getById(id: String): Todo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(todo: Todo)

    @Query("UPDATE todo SET active = :active, updated_at = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("DELETE FROM todo WHERE id = :id")
    suspend fun delete(id: String)

    // --- Completions ---
    @Query("SELECT * FROM todo_completion WHERE todo_id = :todoId ORDER BY date DESC")
    fun getCompletions(todoId: String): Flow<List<TodoCompletion>>

    @Query("SELECT * FROM todo_completion WHERE date = :date")
    fun getByDate(date: String): Flow<List<TodoCompletion>>

    @Query("SELECT * FROM todo_completion WHERE todo_id = :todoId AND date = :date")
    suspend fun getCompletion(todoId: String, date: String): TodoCompletion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletion(completion: TodoCompletion)

    @Query("DELETE FROM todo_completion WHERE todo_id = :todoId AND date = :date")
    suspend fun deleteCompletion(todoId: String, date: String)
}
