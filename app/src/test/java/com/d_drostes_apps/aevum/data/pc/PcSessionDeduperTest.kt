package com.d_drostes_apps.aevum.data.pc

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M36: Duplikat-Erkennung der PC-Aufzeichnungen.
 *
 * Der gemeldete Fall: dieselbe Aufzeichnung wurde einmal mit Ende 21:00 und
 * einmal mit Ende 22:00 importiert (zwei parallele Sync-Läufe). Beide Zeilen
 * überlappen; die kürzere ist der Duplikat-Rest.
 */
class PcSessionDeduperTest {

    private fun session(
        id: String,
        externalId: String?,
        start: Long,
        end: Long,
        type: String? = "pc_gaming",
        createdAt: Long = start,
        userEdited: Boolean = false
    ) = ActivitySession(
        id = id,
        title = "Zocken",
        activityTypeId = type,
        startAt = start,
        endAt = end,
        sourceType = PcSessionImporter.SOURCE_TYPE,
        createdBy = PcSessionImporter.CREATED_BY,
        externalId = externalId,
        isUserEdited = userEdited,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun `gleiche externalId - nur die neueste Zeile bleibt`() {
        val old = session("a", "pc_1", 1_000, 2_000, createdAt = 10)
        val new = session("b", "pc_1", 1_000, 3_000, createdAt = 20)
        val duplicates = PcSessionDeduper.duplicatesToDelete(listOf(old, new))
        assertThat(duplicates.map { it.id }).containsExactly("a")
    }

    @Test
    fun `eingebettete Aufzeichnung mit anderer ID wird geloescht`() {
        val short = session("short", "pc_1", 20 * 3_600_000L, 21 * 3_600_000L)
        val long = session("long", "pc_2", 20 * 3_600_000L, 22 * 3_600_000L)
        val duplicates = PcSessionDeduper.duplicatesToDelete(listOf(short, long))
        assertThat(duplicates.map { it.id }).containsExactly("short")
    }

    @Test
    fun `aufeinanderfolgende Sessions bleiben erhalten`() {
        val first = session("a", "pc_1", 20 * 3_600_000L, 21 * 3_600_000L)
        val second = session("b", "pc_2", 21 * 3_600_000L, 22 * 3_600_000L)
        assertThat(PcSessionDeduper.duplicatesToDelete(listOf(first, second))).isEmpty()
    }

    @Test
    fun `gleicher Start aber andere Aktivitaet gilt als Duplikat`() {
        val gaming = session("a", "pc_1", 1_000, 2_000, type = "pc_gaming")
        val video = session("b", "pc_2", 1_000, 3_000, type = "pc_video")
        val duplicates = PcSessionDeduper.duplicatesToDelete(listOf(gaming, video))
        assertThat(duplicates.map { it.id }).containsExactly("a")
    }

    @Test
    fun `nutzerbearbeitete Sessions werden nie geloescht`() {
        val edited = session("a", "pc_1", 1_000, 2_000, userEdited = true)
        val long = session("b", "pc_2", 1_000, 3_000)
        assertThat(PcSessionDeduper.duplicatesToDelete(listOf(edited, long))).isEmpty()
    }

    @Test
    fun `offene Sessions und andere Quellen bleiben unberuehrt`() {
        val open = session("open", "pc_open", 1_000, 1_000).copy(endAt = null)
        val manual = session("manual", null, 1_000, 9_000).copy(sourceType = "MANUAL")
        val long = session("long", "pc_2", 1_000, 3_000)
        val duplicates = PcSessionDeduper.duplicatesToDelete(listOf(open, manual, long))
        assertThat(duplicates).isEmpty()
    }
}
