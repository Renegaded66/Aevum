package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.PmNodeDao
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.domain.projects.PmSeed
import com.d_drostes_apps.aevum.domain.projects.PmTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M19.1: Umsetzung des Projektmanagement-Repositories.
 *
 * WARUM HIER EIN MUTEX STEHT: Alle Baum-Operationen sind „lesen → rechnen →
 * schreiben" (Umzug ordnet Geschwister neu, Sammel-Datum sammelt erst die
 * IDs ein). Zwei gleichzeitige Vorgänge (z. B. zwei schnelle Tipps auf
 * „alle offenen → heute") könnten sonst auf demselben Stand rechnen und sich
 * gegenseitig überschreiben — verlorene Aktualisierung, doppelte
 * Sortiernummern. Der Mutex macht jeden Vorgang atomar.
 *
 * WARUM DIE REGELN HIER UND NICHT IM UI STEHEN: Damit jede Oberfläche, die
 * später dazukommt (Widget, Automation, Import), automatisch dieselben Regeln
 * bekommt: keine Todos unter Todos, keine Umzüge in den eigenen Teilbaum,
 * kein Datum an Containern.
 */
@Singleton
class PmRepositoryImpl @Inject constructor(
    private val dao: PmNodeDao
) : PmRepository {

    private val writeLock = Mutex()

    override fun observeAll(): Flow<List<PmNode>> = dao.observeAll()

    override suspend fun getAllOnce(): List<PmNode> = dao.getAllOnce()

    override suspend fun getById(id: String): PmNode? = dao.getById(id)

    override suspend fun createNode(
        parentId: String?,
        type: String,
        title: String,
        note: String,
        color: String?,
        dueDate: String?,
        scheduledDate: String?
    ): String = writeLock.withLock {
        val now = System.currentTimeMillis()
        val all = dao.getAllOnce()
        val id = UUID.randomUUID().toString()
        val safeType = if (PmType.isValid(type)) type else PmType.TASK
        val safeParent = normalizeParent(parentId, all)
        dao.upsert(
            PmNode(
                id = id,
                parentId = safeParent,
                type = safeType,
                title = title.trim(),
                note = note,
                color = color,
                sortOrder = nextOrder(all, safeParent),
                // Nur Todos tragen ein Datum (Regel 1 des Schemas).
                scheduledDate = if (PmType.isTodo(safeType)) scheduledDate else null,
                dueDate = dueDate,
                doneAt = null,
                createdAt = now,
                updatedAt = now
            )
        )
        id
    }

    override suspend fun createTodos(
        parentId: String?,
        titles: List<String>,
        scheduledDate: String?
    ): List<String> = writeLock.withLock {
        val cleaned = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return@withLock emptyList()
        val now = System.currentTimeMillis()
        val all = dao.getAllOnce()
        val safeParent = normalizeParent(parentId, all)
        var order = nextOrder(all, safeParent)
        val ids = mutableListOf<String>()
        val nodes = cleaned.map { title ->
            val id = UUID.randomUUID().toString()
            ids.add(id)
            PmNode(
                id = id,
                parentId = safeParent,
                type = PmType.TODO,
                title = title,
                sortOrder = order++,
                scheduledDate = scheduledDate,
                doneAt = null,
                createdAt = now,
                updatedAt = now
            )
        }
        dao.upsertAll(nodes)
        ids
    }

    override suspend fun updateNode(id: String, title: String, note: String, color: String?, dueDate: String?) {
        val existing = dao.getById(id) ?: return
        dao.updateFields(
            id = id,
            title = title.trim().ifEmpty { existing.title },
            note = note,
            color = color,
            dueDate = dueDate,
            now = System.currentTimeMillis()
        )
    }

    override suspend fun setDone(id: String, done: Boolean) {
        val now = System.currentTimeMillis()
        dao.setDone(id = id, doneAt = if (done) now else null, now = now)
    }

    override suspend fun setScheduled(ids: List<String>, date: String?) = writeLock.withLock {
        if (ids.isEmpty()) return@withLock
        val targetIds = dao.getAllOnce()
            .filter { it.id in ids && it.isTodo }
            .map { it.id }
        if (targetIds.isEmpty()) return@withLock
        dao.setScheduledDate(targetIds, date, System.currentTimeMillis())
    }

    override suspend fun setScheduledForSubtree(rootId: String, date: String?, onlyOpen: Boolean): Int =
        writeLock.withLock {
            val all = dao.getAllOnce()
            val tree = PmTree(all)
            if (tree.byId(rootId) == null) return@withLock 0
            val ids = tree.subtreeTodos(rootId)
                .filter { !onlyOpen || it.doneAt == null }
                .map { it.id }
            if (ids.isEmpty()) return@withLock 0
            dao.setScheduledDate(ids, date, System.currentTimeMillis())
            ids.size
        }

    override suspend fun move(id: String, newParentId: String?, newIndex: Int): Boolean = writeLock.withLock {
        val now = System.currentTimeMillis()
        val all = dao.getAllOnce()
        val tree = PmTree(all)
        val node = tree.byId(id) ?: return@withLock false
        // Erst die Regel (auch: Ziel muss es geben), dann erst normalisieren —
        // sonst würde ein ungültiges Ziel stillschweigend zur Wurzel werden.
        if (!tree.moveAllowed(id, newParentId)) return@withLock false
        val target = normalizeParent(newParentId, all)
        val siblings = tree.children(target).filter { it.id != id }.toMutableList()
        val index = newIndex.coerceIn(0, siblings.size)
        siblings.add(index, node)
        for ((position, sibling) in siblings.withIndex()) {
            when {
                sibling.id == id -> dao.move(id, target, position, now)
                sibling.sortOrder != position -> dao.setSortOrder(sibling.id, position, now)
            }
        }
        true
    }

    override suspend fun delete(id: String) {
        // Fremdschlüssel ON DELETE CASCADE räumt den Teilbaum mit ab.
        dao.delete(id)
    }

    override suspend fun insertExample() = writeLock.withLock {
        val existing = dao.getAllOnce().map { it.id }.toHashSet()
        // Nur fehlende Knoten anlegen: ein zweiter Tipp darf weder Dubletten
        // erzeugen noch den bereits erreichten Fortschritt der Beispieldaten
        // überschreiben (REPLACE würde done_at zurücksetzen).
        val missing = PmSeed.example().filter { it.id !in existing }
        if (missing.isNotEmpty()) dao.upsertAll(missing)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    // ── innere Helfer ──────────────────────────────────────────────────────

    /** Eltern prüfen: fehlender oder unzulässiger Elter (Todo) → Wurzel. */
    private fun normalizeParent(parentId: String?, all: List<PmNode>): String? {
        if (parentId == null) return null
        val parent = all.firstOrNull { it.id == parentId } ?: return null
        return if (parent.isTodo) null else parentId
    }

    private fun nextOrder(all: List<PmNode>, parentId: String?): Int =
        (all.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
}
