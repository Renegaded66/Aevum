package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.GoalDao
import de.devondroste.aevum.data.model.Goal
import kotlinx.coroutines.flow.Flow

class GoalRepositoryImpl(
    private val dao: GoalDao
) : GoalRepository {

    override fun getAll(): Flow<List<Goal>> = dao.getAll()

    override fun getByStatus(status: String): Flow<List<Goal>> = dao.getByStatus(status)

    override fun getById(id: String): Flow<Goal?> = dao.getById(id)

    override suspend fun insert(goal: Goal) = dao.insert(goal)

    override suspend fun insertAll(goals: List<Goal>) = dao.insertAll(goals)

    override suspend fun update(goal: Goal) = dao.update(goal)

    override suspend fun delete(id: String) = dao.delete(id)
}