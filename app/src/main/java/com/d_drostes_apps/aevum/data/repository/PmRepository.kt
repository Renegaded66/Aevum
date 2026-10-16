package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.PmNode
import kotlinx.coroutines.flow.Flow

/**
 * M19.1: Projektmanagement-Schnittstelle.
 *
 * Absicht: Die Screens kennen nur diese Schnittstelle plus den reinen Baum
 * ([com.d_drostes_apps.aevum.domain.projects.PmTree]) — Baum-Regeln,
 * Massenzuweisung und Umzugsprüfung liegen hier, nicht im UI und nicht in
 * jedem ViewModel neu.
 */
interface PmRepository {

    /** Alle Knoten (eine Quelle der Wahrheit, siehe [com.d_drostes_apps.aevum.data.db.PmNodeDao]). */
    fun observeAll(): Flow<List<PmNode>>

    suspend fun getAllOnce(): List<PmNode>

    suspend fun getById(id: String): PmNode?

    /** Legt einen Knoten an und gibt die neue ID zurück. */
    suspend fun createNode(
        parentId: String?,
        type: String,
        title: String,
        note: String = "",
        color: String? = null,
        dueDate: String? = null,
        scheduledDate: String? = null
    ): String

    /**
     * M19.1-KERNKOMFORT: legt **mehrere** Todos auf einmal an — eine Zeile
     * = ein Todo. Damit ist eine eingefügte Schrittliste („erste Hälfte
     * lesen", „Entwurf aufschreiben", …) ein einziger Vorgang.
     * Leere Zeilen werden verworfen, Reihenfolge bleibt erhalten.
     */
    suspend fun createTodos(
        parentId: String?,
        titles: List<String>,
        scheduledDate: String? = null
    ): List<String>

    suspend fun updateNode(id: String, title: String, note: String, color: String?, dueDate: String?)

    /** Abhaken / Rückgängig. Nur für Todos sinnvoll (Container sind nicht abhakbar). */
    suspend fun setDone(id: String, done: Boolean)

    /** Datum setzen oder entfernen (null = Backlog). Nur Todos. */
    suspend fun setScheduled(ids: List<String>, date: String?)

    /**
     * Alle offenen (oder alle) Todos unterhalb von [rootId] auf ein Datum
     * setzen — „alle offenen Schritte dieser Aufgabe heute" mit einem Tipp.
     */
    suspend fun setScheduledForSubtree(rootId: String, date: String?, onlyOpen: Boolean = true): Int

    /**
     * Verschiebt [id] unter [newParentId] an Position [newIndex].
     * @return false, wenn die Verschiebung unzulässig ist (eigener Teilbaum,
     *         Ziel ist ein Todo). Es wird dann NICHTS geschrieben.
     */
    suspend fun move(id: String, newParentId: String?, newIndex: Int): Boolean

    /** Löscht einen Knoten samt Teilbaum (Fremdschlüssel CASCADE). */
    suspend fun delete(id: String)

    /** Legt die Beispielstruktur an (idempotent, feste IDs). */
    suspend fun insertExample()

    /** Löscht den kompletten PM-Baum. */
    suspend fun deleteAll()
}
