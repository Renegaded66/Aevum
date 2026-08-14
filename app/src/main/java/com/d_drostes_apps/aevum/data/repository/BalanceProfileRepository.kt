package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.BalanceProfileDao
import com.d_drostes_apps.aevum.data.model.BalanceProfile
import com.d_drostes_apps.aevum.data.model.BalanceProfileApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface BalanceProfileRepository {
    fun getAll(): Flow<List<BalanceProfile>>
    fun getActive(): Flow<BalanceProfile?>
    suspend fun getActiveOnce(): BalanceProfile?
    suspend fun getById(id: String): BalanceProfile?
    suspend fun create(name: String, icon: String, color: String, packageNames: List<String>): BalanceProfile
    // M18.66-FIX14: Profil mit Zeitplan erstellen/bearbeiten
    suspend fun createWithSchedule(name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int): BalanceProfile
    suspend fun update(profileId: String, name: String, icon: String, color: String, packageNames: List<String>)
    suspend fun updateWithSchedule(profileId: String, name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int)
    suspend fun updateSchedule(profileId: String, enabled: Boolean, days: Int, startMin: Int, endMin: Int)
    suspend fun getScheduledProfiles(): List<BalanceProfile>
    suspend fun updateApps(profileId: String, packageNames: List<String>)
    suspend fun setActive(id: String)
    suspend fun deactivate()
    suspend fun delete(id: String)
    suspend fun getAppPackages(profileId: String): List<String>
    fun getAppsFlow(profileId: String): Flow<List<BalanceProfileApp>>
    suspend fun getAppPackagesOnce(profileId: String): List<String>
}

@Singleton
class BalanceProfileRepositoryImpl @Inject constructor(
    private val dao: BalanceProfileDao
) : BalanceProfileRepository {

    override fun getAll(): Flow<List<BalanceProfile>> = dao.getAll()

    override fun getActive(): Flow<BalanceProfile?> = dao.getActive()

    override suspend fun getActiveOnce(): BalanceProfile? = dao.getActive().first()

    override suspend fun getById(id: String): BalanceProfile? = dao.getById(id)

    override suspend fun create(name: String, icon: String, color: String, packageNames: List<String>): BalanceProfile {
        val profile = BalanceProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            icon = icon,
            color = color,
            isActive = false,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(profile)
        packageNames.forEach { pkg ->
            dao.insertApp(BalanceProfileApp(UUID.randomUUID().toString(), profile.id, pkg))
        }
        return profile
    }

    // M18.66-FIX14: Profil mit Zeitplan erstellen
    override suspend fun createWithSchedule(
        name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int
    ): BalanceProfile {
        val profile = BalanceProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            icon = icon,
            color = color,
            isActive = false,
            createdAt = System.currentTimeMillis(),
            scheduleEnabled = scheduleEnabled,
            scheduleDays = scheduleDays,
            scheduleStartMinute = scheduleStartMin,
            scheduleEndMinute = scheduleEndMin
        )
        dao.upsert(profile)
        packageNames.forEach { pkg ->
            dao.insertApp(BalanceProfileApp(UUID.randomUUID().toString(), profile.id, pkg))
        }
        return profile
    }

    override suspend fun update(profileId: String, name: String, icon: String, color: String, packageNames: List<String>) {
        val existing = dao.getById(profileId) ?: return
        dao.upsert(
            existing.copy(
                name = name,
                icon = icon,
                color = color
            )
        )
        updateApps(profileId, packageNames)
    }

    // M18.66-FIX14: Profil mit Zeitplan bearbeiten
    override suspend fun updateWithSchedule(
        profileId: String, name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int
    ) {
        val existing = dao.getById(profileId) ?: return
        dao.upsert(
            existing.copy(
                name = name,
                icon = icon,
                color = color,
                scheduleEnabled = scheduleEnabled,
                scheduleDays = scheduleDays,
                scheduleStartMinute = scheduleStartMin,
                scheduleEndMinute = scheduleEndMin
            )
        )
        updateApps(profileId, packageNames)
    }

    override suspend fun updateSchedule(profileId: String, enabled: Boolean, days: Int, startMin: Int, endMin: Int) {
        dao.updateSchedule(profileId, enabled, days, startMin, endMin)
    }

    override suspend fun getScheduledProfiles(): List<BalanceProfile> = dao.getScheduledProfiles()

    override suspend fun updateApps(profileId: String, packageNames: List<String>) {
        dao.deleteApps(profileId)
        packageNames.forEach { pkg ->
            dao.insertApp(BalanceProfileApp(UUID.randomUUID().toString(), profileId, pkg))
        }
    }

    override suspend fun setActive(id: String) {
        dao.deactivateAll()
        dao.activate(id)
    }

    override suspend fun deactivate() = dao.deactivateAll()

    override suspend fun delete(id: String) {
        dao.deleteApps(id)
        dao.delete(id)
    }

    override suspend fun getAppPackages(profileId: String): List<String> = dao.getAppPackages(profileId)

    override suspend fun getAppPackagesOnce(profileId: String): List<String> = dao.getAppPackages(profileId)

    override fun getAppsFlow(profileId: String): Flow<List<BalanceProfileApp>> = dao.getAppsFlow(profileId)
}
