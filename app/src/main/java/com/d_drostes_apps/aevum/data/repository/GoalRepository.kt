package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import kotlinx.coroutines.flow.Flow

interface GoalRepository {
    fun getAll(): Flow<List<Goal>>
    fun getByStatus(status: String): Flow<List<Goal>>
    fun getById(id: String): Flow<Goal?>
    suspend fun insert(goal: Goal)
    suspend fun insertAll(goals: List<Goal>)
    suspend fun update(goal: Goal)
    suspend fun delete(id: String)

    // ── M20: Ziel-Architektur — Check-ins manueller Ziele ────────────────
    /** Alle Check-ins ab [sinceMs] — Grundlage der Fortschritts-Auswertung. */
    fun getCheckinsSince(sinceMs: Long): Flow<List<GoalCheckin>>

    /** Check-ins eines Ziels (neueste zuerst) — Detail-Ansicht. */
    fun getCheckinsForGoal(goalId: String): Flow<List<GoalCheckin>>

    suspend fun addCheckin(checkin: GoalCheckin)

    suspend fun removeCheckin(checkinId: String)

    suspend fun lastCheckin(goalId: String): GoalCheckin?
}