package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.GoalCheckinDao
import com.d_drostes_apps.aevum.data.db.GoalDao
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import kotlinx.coroutines.flow.Flow

class GoalRepositoryImpl(
    private val dao: GoalDao,
    private val checkinDao: GoalCheckinDao
) : GoalRepository {

    override fun getAll(): Flow<List<Goal>> = dao.getAll()

    override fun getByStatus(status: String): Flow<List<Goal>> = dao.getByStatus(status)

    override fun getById(id: String): Flow<Goal?> = dao.getById(id)

    override suspend fun insert(goal: Goal) = dao.insert(goal)

    override suspend fun insertAll(goals: List<Goal>) = dao.insertAll(goals)

    override suspend fun update(goal: Goal) = dao.update(goal)

    override suspend fun delete(id: String) = dao.delete(id)

    // ── M20: Check-ins manueller Ziele ───────────────────────────────────

    override fun getCheckinsSince(sinceMs: Long): Flow<List<GoalCheckin>> =
        checkinDao.getSince(sinceMs)

    override fun getCheckinsForGoal(goalId: String): Flow<List<GoalCheckin>> =
        checkinDao.getForGoal(goalId)

    override suspend fun addCheckin(checkin: GoalCheckin) = checkinDao.insert(checkin)

    override suspend fun removeCheckin(checkinId: String) = checkinDao.deleteById(checkinId)

    override suspend fun lastCheckin(goalId: String): GoalCheckin? = checkinDao.lastForGoal(goalId)
}