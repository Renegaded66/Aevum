package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.PmNode
import kotlinx.coroutines.flow.Flow

/**
 * M19.1: Zugriff auf den Projektmanagement-Baum.
 *
 * BEWUSST EIN FLOW ÜBER DIE GANZE TABELLE (`observeAll`) statt vieler
 * Teilabfragen: Der Baum wird in Kotlin indexiert (siehe
 * [com.d_drostes_apps.aevum.domain.projects.PmTree]), dadurch stimmen
 * Fortschrittsbalken, Glas und Listen nach JEDER Änderung automatisch
 * überein — eine Quelle der Wahrheit, keine getrennten Abfragen, die
 * auseinanderlaufen können. Bei hunderten bis wenigen tausend Knoten ist das
 * eine Query ohne messbare Last.
 *
 * Schreibvorgänge sind einzeln gehalten, damit das Repository sie zu
 * Baum-Operationen (Umzug, Sammel-Datum) kombinieren kann.
 */
@Dao
interface PmNodeDao {

    @Query("SELECT * FROM pm_node")
    fun observeAll(): Flow<List<PmNode>>

    @Query("SELECT * FROM pm_node")
    suspend fun getAllOnce(): List<PmNode>

    @Query("SELECT * FROM pm_node WHERE id = :id")
    suspend fun getById(id: String): PmNode?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: PmNode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<PmNode>)

    @Query("DELETE FROM pm_node WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pm_node")
    suspend fun deleteAll()

    @Query(
        "UPDATE pm_node SET title = :title, note = :note, color = :color, due_date = :dueDate, " +
            "updated_at = :now WHERE id = :id"
    )
    suspend fun updateFields(id: String, title: String, note: String, color: String?, dueDate: String?, now: Long)

    @Query("UPDATE pm_node SET done_at = :doneAt, updated_at = :now WHERE id = :id")
    suspend fun setDone(id: String, doneAt: Long?, now: Long)

    @Query("UPDATE pm_node SET scheduled_date = :date, updated_at = :now WHERE id IN (:ids)")
    suspend fun setScheduledDate(ids: List<String>, date: String?, now: Long)

    @Query("UPDATE pm_node SET parent_id = :parentId, sort_order = :sortOrder, updated_at = :now WHERE id = :id")
    suspend fun move(id: String, parentId: String?, sortOrder: Int, now: Long)

    @Query("UPDATE pm_node SET sort_order = :sortOrder, updated_at = :now WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int, now: Long)
}
