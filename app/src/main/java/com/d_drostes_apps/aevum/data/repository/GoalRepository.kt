package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.Goal
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