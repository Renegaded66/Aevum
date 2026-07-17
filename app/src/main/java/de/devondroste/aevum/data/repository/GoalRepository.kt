package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.Goal
import kotlinx.coroutines.flow.Flow

interface GoalRepository {
    fun getAll(): Flow<List<Goal>>
    fun getByStatus(status: String): Flow<List<Goal>>
    fun getById(id: String): Flow<Goal?>
    suspend fun insert(goal: Goal)
    suspend fun insertAll(goals: List<Goal>)
    suspend fun update(goal: Goal)
    suspend fun delete(id: String)
}