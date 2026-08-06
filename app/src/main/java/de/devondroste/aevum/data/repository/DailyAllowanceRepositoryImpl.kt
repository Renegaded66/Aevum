package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.DailyAllowanceDao
import de.devondroste.aevum.data.model.AllowanceAccumulationDay
import de.devondroste.aevum.data.model.DailyAllowance
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

    override suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay> =
        dao.getAccumulationForDate(date)
    override suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay> =
        dao.getAccumulationInRange(startDate, endDate)
    override suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay) =
        dao.insertAccumulation(accumulation)
}
