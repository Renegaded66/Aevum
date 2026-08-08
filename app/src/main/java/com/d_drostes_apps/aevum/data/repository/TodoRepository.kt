package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.TodoDao
import com.d_drostes_apps.aevum.data.model.Todo
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepository @Inject constructor(
    private val dao: TodoDao
) {
    fun getActive(): Flow<List<Todo>> = dao.getActive()
    fun getAll(): Flow<List<Todo>> = dao.getAll()
    suspend fun getById(id: String): Todo? = dao.getById(id)
    suspend fun insert(todo: Todo) = dao.insert(todo)
    suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active, System.currentTimeMillis())
    suspend fun delete(id: String) = dao.delete(id)

    // --- Completions ---
    fun getCompletions(todoId: String): Flow<List<TodoCompletion>> = dao.getCompletions(todoId)
    fun getByDate(date: String): Flow<List<TodoCompletion>> = dao.getByDate(date)

    // M18.43: Alle Completions (Dashboard filtert selbst auf das aktuelle Datum).
    fun getAllCompletions(): Flow<List<TodoCompletion>> = dao.getAllCompletions()
    suspend fun getCompletion(todoId: String, date: String): TodoCompletion? = dao.getCompletion(todoId, date)
    suspend fun insertCompletion(completion: TodoCompletion) = dao.insertCompletion(completion)
    suspend fun deleteCompletion(todoId: String, date: String) = dao.deleteCompletion(todoId, date)
}
