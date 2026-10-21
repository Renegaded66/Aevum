package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AddictionSessionDao
import com.d_drostes_apps.aevum.data.model.AddictionSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** M27: Persistierte Vordergrund-Sitzungen des Sucht-Detektors. */
@Singleton
class AddictionSessionRepository @Inject constructor(
    private val dao: AddictionSessionDao
) {
    fun getSince(startAt: Long): Flow<List<AddictionSession>> = dao.getSince(startAt)

    suspend fun getSinceOnce(startAt: Long): List<AddictionSession> = dao.getSinceOnce(startAt)

    suspend fun upsertAll(sessions: List<AddictionSession>) = dao.upsertAll(sessions)

    suspend fun deleteOlderThan(cutoff: Long) = dao.deleteOlderThan(cutoff)

    suspend fun deleteForPackage(packageName: String) = dao.deleteForPackage(packageName)
}
