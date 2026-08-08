package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.HabitDao
import com.d_drostes_apps.aevum.data.db.HabitLogDao
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.data.model.HabitLog
import kotlinx.coroutines.flow.Flow

class HabitRepositoryImpl(
    private val habitDao: HabitDao,
    private val habitLogDao: HabitLogDao
) : HabitRepository {

    override fun getActive(): Flow<List<Habit>> = habitDao.getActive()

    override fun getAll(): Flow<List<Habit>> = habitDao.getAll()

    override fun getById(id: String): Flow<Habit?> = habitDao.getById(id)

    override suspend fun insert(habit: Habit) = habitDao.insert(habit)

    override suspend fun insertAll(habits: List<Habit>) = habitDao.insertAll(habits)

    override suspend fun update(habit: Habit) = habitDao.update(habit)

    override suspend fun delete(id: String) = habitDao.delete(id)

    override fun getLogsByHabit(habitId: String): Flow<List<HabitLog>> = habitLogDao.getByHabit(habitId)

    override fun getLogsByDate(date: String): Flow<List<HabitLog>> = habitLogDao.getByDate(date)

    override fun getLogByHabitAndDate(habitId: String, date: String): Flow<HabitLog?> = habitLogDao.getByHabitAndDate(habitId, date)

    override suspend fun insertLog(log: HabitLog) = habitLogDao.insert(log)

    override suspend fun insertLogs(logs: List<HabitLog>) = habitLogDao.insertAll(logs)

    override suspend fun deleteLog(habitId: String, date: String) = habitLogDao.deleteByHabitAndDate(habitId, date)
}