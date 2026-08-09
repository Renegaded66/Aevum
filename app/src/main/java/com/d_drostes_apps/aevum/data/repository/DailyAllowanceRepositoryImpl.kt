package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.DailyAllowanceDao
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.AllowanceDayOverride
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyAllowanceRepositoryImpl @Inject constructor(
    private val dao: DailyAllowanceDao
) : DailyAllowanceRepository {
    override fun getAll(): Flow<List<DailyAllowance>> = dao.getAll()
    override suspend fun getEnabled(): List<DailyAllowance> = dao.getEnabled()
    override suspend fun getById(id: String): DailyAllowance? = dao.getById(id)
    override suspend fun insert(allowance: DailyAllowance) = dao.insert(allowance)
    override suspend fun setEnabled(id: String, enabled: Boolean) =
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun deleteAccumulationsForAllowance(allowanceId: String) =
        dao.deleteAccumulationsForAllowance(allowanceId)

    override suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay> =
        dao.getAccumulationForDate(date)
    override suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay> =
        dao.getAccumulationInRange(startDate, endDate)
    override suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay) =
        dao.insertAccumulation(accumulation)

    // M18.60: Pro-Tag-Overrides
    override suspend fun getOverridesForDate(date: String): List<AllowanceDayOverride> =
        dao.getOverridesForDate(date)
    override fun getOverridesForDateFlow(date: String): Flow<List<AllowanceDayOverride>> =
        dao.getOverridesForDateFlow(date)
    override suspend fun getOverride(date: String, allowanceId: String): AllowanceDayOverride? =
        dao.getOverride(date, allowanceId)
    override suspend fun insertOverride(override: AllowanceDayOverride) =
        dao.insertOverride(override)
    override suspend fun deleteOverride(date: String, allowanceId: String) =
        dao.deleteOverride(date, allowanceId)
}
