package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.data.model.HabitLog
import kotlinx.coroutines.flow.Flow

interface HabitRepository {
    fun getActive(): Flow<List<Habit>>
    fun getAll(): Flow<List<Habit>>
    fun getById(id: String): Flow<Habit?>
    suspend fun insert(habit: Habit)
    suspend fun insertAll(habits: List<Habit>)
    suspend fun update(habit: Habit)
    suspend fun delete(id: String)

    fun getLogsByHabit(habitId: String): Flow<List<HabitLog>>
    fun getLogsByDate(date: String): Flow<List<HabitLog>>
    fun getLogByHabitAndDate(habitId: String, date: String): Flow<HabitLog?>
    suspend fun insertLog(log: HabitLog)
    suspend fun insertLogs(logs: List<HabitLog>)
    suspend fun deleteLog(habitId: String, date: String)
}