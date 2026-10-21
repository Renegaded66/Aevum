package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.AddictionWatchAppDao
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M27: Auswahl der beobachteten Apps. Das Repository kapselt die
 * Sortier-Reihenfolge — neue Apps landen hinten, die Anzeige bleibt stabil.
 */
@Singleton
class AddictionWatchRepository @Inject constructor(
    private val dao: AddictionWatchAppDao
) {
    fun getAll(): Flow<List<AddictionWatchApp>> = dao.getAll()

    suspend fun getAllOnce(): List<AddictionWatchApp> = dao.getAllOnce()

    suspend fun add(packageName: String, appLabel: String) {
        val existing = dao.getAllOnce().firstOrNull { it.packageName == packageName }
        dao.upsert(
            AddictionWatchApp(
                packageName = packageName,
                appLabel = appLabel,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                sortOrder = existing?.sortOrder ?: (dao.maxSortOrder() + 1)
            )
        )
    }

    suspend fun addAll(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) return
        var next = dao.maxSortOrder() + 1
        val existing = dao.getAllOnce().associateBy { it.packageName }
        val rows = apps.distinctBy { it.first }.map { (pkg, label) ->
            val old = existing[pkg]
            AddictionWatchApp(
                packageName = pkg,
                appLabel = label,
                addedAt = old?.addedAt ?: System.currentTimeMillis(),
                sortOrder = old?.sortOrder ?: next++
            )
        }
        dao.upsertAll(rows)
    }

    suspend fun remove(packageName: String) = dao.delete(packageName)
}
