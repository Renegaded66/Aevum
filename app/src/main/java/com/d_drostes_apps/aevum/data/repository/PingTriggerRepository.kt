package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.PingTriggerDao
import com.d_drostes_apps.aevum.data.model.PingTrigger
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface PingTriggerRepository {
    fun getAll(): Flow<List<PingTrigger>>
    suspend fun getAllEnabled(): List<PingTrigger>
    suspend fun getById(id: String): PingTrigger?
    suspend fun upsert(trigger: PingTrigger)
    suspend fun create(name: String, ipAddress: String, activityTypeId: String): PingTrigger
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
}

@Singleton
class PingTriggerRepositoryImpl @Inject constructor(
    private val dao: PingTriggerDao
) : PingTriggerRepository {

    override fun getAll(): Flow<List<PingTrigger>> = dao.getAll()

    override suspend fun getAllEnabled(): List<PingTrigger> = dao.getAllEnabled()

    override suspend fun getById(id: String): PingTrigger? = dao.getById(id)

    override suspend fun upsert(trigger: PingTrigger) = dao.upsert(trigger)

    override suspend fun create(name: String, ipAddress: String, activityTypeId: String): PingTrigger {
        val trigger = PingTrigger(
            id = UUID.randomUUID().toString(),
            name = name,
            ipAddress = ipAddress,
            activityTypeId = activityTypeId,
            enabled = true
        )
        dao.upsert(trigger)
        return trigger
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun delete(id: String) = dao.delete(id)
}
