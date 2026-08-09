package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.AllowanceDayOverride
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import kotlinx.coroutines.flow.Flow

interface DailyAllowanceRepository {
    fun getAll(): Flow<List<DailyAllowance>>
    suspend fun getEnabled(): List<DailyAllowance>
    suspend fun getById(id: String): DailyAllowance?
    suspend fun insert(allowance: DailyAllowance)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)

    // M18.37: Accumulations einer Allowance loeschen (beim Delete mit).
    suspend fun deleteAccumulationsForAllowance(allowanceId: String)

    suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay>
    suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay>
    suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay)

    // M18.60: Pro-Tag-Overrides — Tageswert einer Pauschale einmalig anpassen.
    suspend fun getOverridesForDate(date: String): List<AllowanceDayOverride>
    suspend fun getOverride(date: String, allowanceId: String): AllowanceDayOverride?
    suspend fun insertOverride(override: AllowanceDayOverride)
    suspend fun deleteOverride(date: String, allowanceId: String)
}
