package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.PmNode
import kotlinx.coroutines.flow.Flow

/**
 * M19.1/M19.2: Projektmanagement-Schnittstelle.
 *
 * Absicht: Die Screens kennen nur diese Schnittstelle plus den reinen Baum
 * ([com.d_drostes_apps.aevum.domain.projects.PmTree]) — Baum-Regeln,
 * Massenzuweisung, Reihenfolge und Umzugsprüfung liegen hier, nicht im UI und
 * nicht in jedem ViewModel neu.
 *
 * M19.2-Wortschatz: Es gibt nur noch Projekte (Container) und Aufgaben
 * (abhakbare Blätter). „Todos"/„Kategorien" existieren nicht mehr.
 */
interface PmRepository {

    /** Alle Knoten (eine Quelle der Wahrheit, siehe [com.d_drostes_apps.aevum.data.db.PmNodeDao]). */
    fun observeAll(): Flow<List<PmNode>>

    suspend fun getAllOnce(): List<PmNode>

    suspend fun getById(id: String): PmNode?

    /**
     * Legt einen Knoten an und gibt die neue ID zurück.
     * An der Wurzel entstehen nur Projekte; Aufgaben liegen unter Projekten.
     */
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
     * Legt **mehrere** Aufgaben auf einmal an (eine Zeile = eine Aufgabe).
     * Genutzt von der Inline-Eingabe der To-do-Liste (jede Eingabe einzeln)
     * und vom Import größerer Listen. Leere Zeilen werden verworfen,
     * Reihenfolge bleibt erhalten.
     */
    suspend fun createTasks(
        parentId: String?,
        titles: List<String>,
        scheduledDate: String? = null
    ): List<String>

    suspend fun updateNode(id: String, title: String, note: String, color: String?, dueDate: String?)

    /** Abhaken / Rückgängig. Nur für Aufgaben (Projekte sind nicht abhakbar). */
    suspend fun setDone(id: String, done: Boolean)

    /** Datum setzen oder entfernen (null = Backlog). Nur Aufgaben. */
    suspend fun setScheduled(ids: List<String>, date: String?)

    /**
     * Alle offenen (oder alle) Aufgaben unterhalb von [rootId] auf ein Datum
     * setzen — „alle offenen → heute" mit einem Tipp.
     */
    suspend fun setScheduledForSubtree(rootId: String, date: String?, onlyOpen: Boolean = true): Int

    /**
     * M19.2 (Drag & Drop): setzt die REIHENFOLGE der offenen Aufgaben eines
     * Projekts neu. [orderedOpenIds] muss exakt alle offenen Aufgaben des
     * Projekts enthalten (in neuer Anzeige-Reihenfolge); erledigte Aufgaben
     * werden dahinter einsortiert, damit die manuelle Reihenfolge stabil
     * bleibt und die nächste Anzeige exakt der gezogenen entspricht.
     *
     * @return false, wenn die Liste nicht exakt zum Bestand passt — es wird
     *         dann NICHTS geschrieben (die Anzeige bleibt die Wahrheit).
     */
    suspend fun reorderTasks(parentId: String?, orderedOpenIds: List<String>): Boolean

    /**
     * Verschiebt [id] unter [newParentId] an Position [newIndex].
     * @return false, wenn die Verschiebung unzulässig ist (eigener Teilbaum,
     *         Ziel ist kein Projekt-Container, Aufgabe an die Wurzel).
     *         Es wird dann NICHTS geschrieben.
     */
    suspend fun move(id: String, newParentId: String?, newIndex: Int): Boolean

    /** Löscht einen Knoten samt Teilbaum (Fremdschlüssel CASCADE). */
    suspend fun delete(id: String)

    /** Legt die Vorlage an (idempotent, feste IDs, Titel lokalisiert). */
    suspend fun insertExample()

    /** Löscht den kompletten PM-Baum. */
    suspend fun deleteAll()
}
