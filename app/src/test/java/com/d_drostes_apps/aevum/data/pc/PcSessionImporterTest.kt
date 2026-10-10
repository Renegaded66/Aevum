package com.d_drostes_apps.aevum.data.pc

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Import vom PC-Tracker gegen die ECHTE Room-Datenbank: Idempotenz über
 * external_id, Auto-Mapping über den Activity-Namen, Schutz vor Überschreiben
 * nutzerbearbeiteter Sessions, Kategorie-/Zeitzonen-Übernahme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PcSessionImporterTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: PcSessionImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        importer = PcSessionImporter(db.activitySessionDao(), db.activityTypeDao())
        // In der echten App laufen die Seeds (EnsureDefaultDataUseCase) beim
        // Start; die Fremdschlüssel auf category(id) verlangen sie daher auch
        // hier. „digital" ist die Seed-Kategorie für PC-Aktivitäten.
        runBlocking {
            db.categoryDao().insert(
                Category(id = "digital", name = "Digital", color = "#64748B", icon = "▣", isSystem = true, sortOrder = 90)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pcSession(
        id: String,
        name: String = "Zocken",
        key: String = "gaming",
        category: String = "digital",
        start: Long = 1_000,
        end: Long = 2_000
    ) = PcTrackerClient.PcSession(
        id = id,
        program = "Minecraft",
        activityKey = key,
        activityName = name,
        categoryKey = category,
        icon = "🎮",
        startAt = start,
        endAt = end,
        timezoneId = "Europe/Berlin"
    )

    @Test
    fun `Import legt ActivityType per Namens-Mapping an und importiert die Session`() = runTest {
        val result = importer.import("Devons-PC", listOf(pcSession("s1")))

        assertThat(result.inserted).isEqualTo(1)
        assertThat(result.createdTypes).containsExactly("Zocken")

        val rows = db.activitySessionDao().getByExternalIdsOnce(listOf("pc_s1"))
        assertThat(rows).hasSize(1)
        val row = rows.first()
        assertThat(row.externalId).isEqualTo("pc_s1")
        assertThat(row.sourceType).isEqualTo("PC_TRACKER")
        assertThat(row.title).isEqualTo("Zocken")
        assertThat(row.description).isEqualTo("Minecraft")
        assertThat(row.activityTypeId).isEqualTo("pc_gaming")
        assertThat(row.categoryId).isEqualTo("digital")
        assertThat(row.timezoneId).isEqualTo("Europe/Berlin")

        val type = db.activityTypeDao().getById("pc_gaming").first()
        assertThat(type).isNotNull()
        assertThat(type!!.name).isEqualTo("Zocken")
        assertThat(type.defaultCategoryId).isEqualTo("digital")
        assertThat(type.icon).isEqualTo("🎮")
        assertThat(type.isSystem).isFalse()
    }

    @Test
    fun `erneuter Sync aktualisiert statt zu duplizieren`() = runTest {
        importer.import("Devons-PC", listOf(pcSession("s1", end = 2_000)))
        val second = importer.import("Devons-PC", listOf(pcSession("s1", end = 3_000)))

        assertThat(second.inserted).isEqualTo(0)
        assertThat(second.updated).isEqualTo(1)
        val rows = db.activitySessionDao().getByExternalIdsOnce(listOf("pc_s1"))
        assertThat(rows).hasSize(1)
        assertThat(rows.first().endAt).isEqualTo(3_000)
    }

    @Test
    fun `gleichnamiger ActivityType wird wiederverwendet`() = runTest {
        db.activityTypeDao().insert(
            ActivityType(id = "user_zocken", name = "Zocken", isSystem = false)
        )

        importer.import("Devons-PC", listOf(pcSession("s1")))

        val rows = db.activitySessionDao().getByExternalIdsOnce(listOf("pc_s1"))
        assertThat(rows.first().activityTypeId).isEqualTo("user_zocken")
        assertThat(db.activityTypeDao().getById("pc_gaming").first()).isNull()
    }

    @Test
    fun `nutzerbearbeitete Session wird nicht ueberschrieben`() = runTest {
        importer.import("Devons-PC", listOf(pcSession("s1", end = 2_000)))
        val original = db.activitySessionDao().getByExternalIdsOnce(listOf("pc_s1")).first()
        db.activitySessionDao().update(original.copy(isUserEdited = true, title = "Mein Titel"))

        val second = importer.import("Devons-PC", listOf(pcSession("s1", end = 9_999)))

        assertThat(second.skippedUserEdited).isEqualTo(1)
        assertThat(second.updated).isEqualTo(0)
        val after = db.activitySessionDao().getByExternalIdsOnce(listOf("pc_s1")).first()
        assertThat(after.title).isEqualTo("Mein Titel")
        assertThat(after.endAt).isEqualTo(2_000)
    }

    @Test
    fun `unlogische Zeitraeume werden uebersprungen`() = runTest {
        val result = importer.import("Devons-PC", listOf(pcSession("s1", start = 5_000, end = 5_000)))
        assertThat(result.inserted).isEqualTo(0)
        assertThat(result.updated).isEqualTo(0)
    }

    @Test
    fun `leere Liste ist ein No-Op`() = runTest {
        val result = importer.import("Devons-PC", emptyList())
        assertThat(result.inserted).isEqualTo(0)
        assertThat(result.createdTypes).isEmpty()
    }
}
