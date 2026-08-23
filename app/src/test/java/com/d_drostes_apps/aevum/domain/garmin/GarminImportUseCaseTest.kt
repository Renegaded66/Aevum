package com.d_drostes_apps.aevum.domain.garmin

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.GarminActivity
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.GarminRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * M18.67: Garmin-Aktivitäts-Import — Name-basiertes Type-Matching.
 *
 * User-Wunsch: "Wenn man bspw noch keine Activity Yoga in Aevum hat, und
 * dann auf Garmin eine Aktivität Yoga aufnimmt, und dann die Aktivität
 * 'Dortmund Yoga' da ist, dass in Aevum nur 'Yoga' berücksichtigt wird,
 * ohne Ortsname, und es wird geschaut, ob es bereits in Aevum einen
 * Activity Type 'Yoga' gibt, falls ja dann füge in die Timeline zu
 * passenden Zeiten diese Activity von dem Typ ein, sonst erstelle erst
 * einen Activity Type Yoga mit Güte 50 und füge anschließend eine
 * Activity von diesem Typ in die Timeline ein. Falls vorher schon der
 * Activity Type vorhanden war, soll genau dieser eingefügt werden, weil
 * die Güte vom Nutzer vielleicht schon angepasst wurde und natürlich
 * bestehen bleiben soll."
 */
class GarminImportUseCaseTest {

    private fun garminActivity(
        externalId: String,
        title: String,
        type: String,
        startAt: Long = 1_000_000L,
        endAt: Long = 1_800_000L
    ) = GarminActivity(
        id = externalId,
        externalId = externalId,
        activityType = type,
        title = title,
        startAt = startAt,
        endAt = endAt,
        distanceMeters = 0.0,
        calories = 0,
        importedAt = 0L,
        sessionId = null
    )

    private fun type(id: String, name: String, score: Int = 50) = ActivityType(
        id = id,
        name = name,
        defaultCategoryId = null,
        isSystem = false,
        propertiesJson = null,
        positivityScore = score,
        icon = "•",
        color = 0L
    )

    private class FakeActivityRepository : ActivityRepository {
        val inserted = mutableListOf<ActivitySession>()
        val updated = mutableListOf<ActivitySession>()
        val deleted = mutableListOf<String>()
        override fun getAll(): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override fun getCurrentActiveSession(): Flow<ActivitySession?> = flowOf(null)
        override fun getLiveSession(): Flow<ActivitySession?> = flowOf(null)
        override suspend fun updateStatus(id: String, status: String) {}
        override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) {}
        override suspend fun pauseSession(id: String, endAt: Long) {}
        override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) {}
        override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = flowOf(null)
        override fun getById(id: String): Flow<ActivitySession?> = flowOf(null)
        override fun getByExternalId(externalId: String): Flow<List<ActivitySession>> = flowOf(emptyList())
        override suspend fun insert(session: ActivitySession) { inserted.add(session) }
        override suspend fun insertWithTags(session: ActivitySession, tags: List<com.d_drostes_apps.aevum.data.model.Tag>) {}
        override suspend fun update(session: ActivitySession) { updated.add(session) }
        override suspend fun softDelete(id: String, now: Long) { deleted.add(id) }
        override suspend fun delete(id: String) {}
        override suspend fun setManualQualityOverride(sessionId: String, score: Int?) {}
        override suspend fun setManualQualityOverrideForRange(start: Long, end: Long, score: Int?) {}
        override suspend fun insertTagMapping(mapping: com.d_drostes_apps.aevum.data.model.ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
    }

    private open class FakeGarminRepository : GarminRepository {
        val upserted = mutableListOf<GarminActivity>()
        override fun getSummaryByDate(date: String): Flow<com.d_drostes_apps.aevum.data.model.GarminDailySummary?> = flowOf(null)
        override fun getSummariesFrom(start: String): Flow<List<com.d_drostes_apps.aevum.data.model.GarminDailySummary>> = flowOf(emptyList())
        override fun getSummariesByRange(start: String, end: String): Flow<List<com.d_drostes_apps.aevum.data.model.GarminDailySummary>> = flowOf(emptyList())
        override suspend fun upsertSummary(summary: com.d_drostes_apps.aevum.data.model.GarminDailySummary) {}
        override suspend fun getActivityByExternalId(externalId: String): GarminActivity? = null
        override fun getActivitiesByRange(start: Long, end: Long): Flow<List<GarminActivity>> = flowOf(emptyList())
        override suspend fun upsertActivity(activity: GarminActivity) { upserted.add(activity) }
    }

    private class FakeTypeRepository(
        initial: List<ActivityType>
    ) : ActivityTypeRepository {
        val types = MutableStateFlow(initial)
        val inserted = mutableListOf<ActivityType>()
        override fun getById(id: String): Flow<ActivityType?> = flowOf(types.value.firstOrNull { it.id == id })
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(types.value.filter { it.isSystem })
        override fun getAll(): Flow<List<ActivityType>> = types
        override fun getFavorites(): Flow<List<ActivityType>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setPositivityScore(id: String, score: Int) {}
        override suspend fun setIcon(id: String, icon: String) {}
        override suspend fun setColor(id: String, color: Long) {}
        override suspend fun setCategory(id: String, categoryId: String?) {}
        override suspend fun insert(type: ActivityType) {
            inserted.add(type)
            types.value = types.value + type
        }
        override suspend fun insertAll(types: List<ActivityType>) { this.types.value = this.types.value + types }
        override suspend fun update(type: ActivityType) {
            types.value = types.value.map { if (it.id == type.id) type else it }
        }
        override suspend fun delete(typeId: String) {
            types.value = types.value.filter { it.id != typeId }
        }
    }

    // ─── Fall 1: "Dortmund Yoga", kein Yoga-Typ existiert → neu mit Güte 50 ───
    @Test
    fun `unbekannter Typ wird mit Guete 50 erstellt und Titel ohne Ortsname`() = runTest {
        val activityRepo = FakeActivityRepository()
        val garminRepo = FakeGarminRepository()
        val typeRepo = FakeTypeRepository(emptyList())

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g1", "Dortmund Yoga", "yoga"))
        )

        assertThat(imported).isEqualTo(1)
        // Neuer Typ "Yoga" mit Güte 50
        assertThat(typeRepo.inserted).hasSize(1)
        assertThat(typeRepo.inserted[0].id).isEqualTo("yoga")
        assertThat(typeRepo.inserted[0].name).isEqualTo("Yoga")
        assertThat(typeRepo.inserted[0].positivityScore).isEqualTo(50)
        // Session: Titel ohne Ortsname, Typ = neuer Yoga-Typ
        assertThat(activityRepo.inserted).hasSize(1)
        assertThat(activityRepo.inserted[0].title).isEqualTo("Yoga")
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("yoga")
    }

    // ─── Fall 2: "Dortmund Yoga", Yoga-Typ existiert mit Güte 80 → bestehender Typ ───
    @Test
    fun `existierender Typ wird wiederverwendet und Guete bleibt erhalten`() = runTest {
        val activityRepo = FakeActivityRepository()
        val garminRepo = FakeGarminRepository()
        val typeRepo = FakeTypeRepository(listOf(type("yoga", "Yoga", score = 80)))

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g1", "Dortmund Yoga", "yoga"))
        )

        assertThat(imported).isEqualTo(1)
        // KEIN neuer Typ erstellt — der bestehende (Güte 80) wird genutzt
        assertThat(typeRepo.inserted).isEmpty()
        assertThat(activityRepo.inserted).hasSize(1)
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("yoga")
        assertThat(activityRepo.inserted[0].title).isEqualTo("Yoga")
        // Güte unangetastet
        assertThat(typeRepo.types.value.first { it.id == "yoga" }.positivityScore).isEqualTo(80)
    }

    // ─── Fall 3: 1-Wort-Name ohne Ortsnamen → typeKey running → Seed "joggen" ───
    @Test
    fun `bekannter typeKey faellt auf Seed-Mapping zurueck`() = runTest {
        val activityRepo = FakeActivityRepository()
        val garminRepo = FakeGarminRepository()
        val typeRepo = FakeTypeRepository(listOf(type("joggen", "Joggen", score = 60)))

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g1", "Abendrunde", "running"))
        )

        assertThat(imported).isEqualTo(1)
        assertThat(typeRepo.inserted).isEmpty()
        assertThat(activityRepo.inserted).hasSize(1)
        // Titel bleibt (kein Ortsname), Typ = bestehender Seed "joggen"
        assertThat(activityRepo.inserted[0].title).isEqualTo("Abendrunde")
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("joggen")
    }

    // ─── Fall 4: Dedup — gleiche externalId wird nicht doppelt importiert ───
    @Test
    fun `gleiche externalId wird nicht doppelt importiert`() = runTest {
        val activityRepo = FakeActivityRepository()
        val garminRepo = object : FakeGarminRepository() {
            override suspend fun getActivityByExternalId(externalId: String): GarminActivity? =
                garminActivity(externalId, "Dortmund Yoga", "yoga")
        }
        val typeRepo = FakeTypeRepository(emptyList())

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g1", "Dortmund Yoga", "yoga"))
        )

        assertThat(imported).isEqualTo(0)
        assertThat(activityRepo.inserted).isEmpty()
        assertThat(typeRepo.inserted).isEmpty()
    }

    // ─── Fall 5: Zero-Width-Space (live verifiziert M18.67) ───
    @Test
    fun `zeroWidthSpace im Garmin-Namen wird entfernt und matcht existierenden Typ`() = runTest {
        // Garmin liefert "Krafttrai\u200bning" (unsichtbares ZWS) — der
        // Cleaner muss es entfernen, sonst schlägt der Name-Match gegen
        // "Krafttraining" fehl und es würde fälschlich ein neuer Typ erstellt.
        val activityRepo = FakeActivityRepository()
        val typeRepo = FakeTypeRepository(listOf(type("kraft", "Krafttraining", score = 80)))
        val garminRepo = FakeGarminRepository()

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g-zws-1", "Krafttrai\u200bning", "strength_training"))
        )

        assertThat(imported).isEqualTo(1)
        assertThat(typeRepo.inserted).isEmpty() // kein neuer Typ
        assertThat(activityRepo.inserted).hasSize(1)
        assertThat(activityRepo.inserted[0].title).isEqualTo("Krafttraining")
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("kraft")
    }

    // ─── Fall 6: Syddjurs Laufen (live verifiziert M18.67) ───
    @Test
    fun `Syddjurs Laufen wird zu Laufen und erstellt neuen Typ mit Guete 50`() = runTest {
        // Garmin liefert "Syddjurs Laufen" (Ortsname + Typ) — nur "Laufen"
        // soll in die Timeline. Da "Laufen" als Typ noch nicht existiert,
        // wird er NEU erstellt (Güte 50) — NICHT auf Seed "joggen" gemappt
        // (User: "falls Laufen noch nicht als Activity Type existiert dann
        // soll es erstellt werden").
        val activityRepo = FakeActivityRepository()
        val typeRepo = FakeTypeRepository(listOf(type("joggen", "Joggen", score = 60)))
        val garminRepo = FakeGarminRepository()

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g-syddjurs-1", "Syddjurs Laufen", "running"))
        )

        assertThat(imported).isEqualTo(1)
        assertThat(activityRepo.inserted).hasSize(1)
        assertThat(activityRepo.inserted[0].title).isEqualTo("Laufen")
        // Neuer Typ "Laufen" wurde erstellt (Güte 50), nicht Seed "joggen"
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("running")
        assertThat(typeRepo.inserted).hasSize(1)
        assertThat(typeRepo.inserted[0].name).isEqualTo("Laufen")
        assertThat(typeRepo.inserted[0].positivityScore).isEqualTo(50)
    }

    // ─── Fall 7: 1-Wort-Name ohne Ortsnamen → Seed-Fallback ───
    @Test
    fun `einwortiger Name ohne Ortsnamen faellt auf Seed-Mapping zurueck`() = runTest {
        // "Abendrunde" hat keinen Ortsnamen-Präfix → typeKey-Mapping auf
        // Seed "joggen" (bestehendes Verhalten bleibt erhalten).
        val activityRepo = FakeActivityRepository()
        val typeRepo = FakeTypeRepository(listOf(type("joggen", "Joggen", score = 60)))
        val garminRepo = FakeGarminRepository()

        val useCase = GarminImportUseCase(activityRepo, garminRepo, typeRepo)
        val imported = useCase.importActivities(
            listOf(garminActivity("g-abend-1", "Abendrunde", "running"))
        )

        assertThat(imported).isEqualTo(1)
        assertThat(typeRepo.inserted).isEmpty() // kein neuer Typ
        assertThat(activityRepo.inserted).hasSize(1)
        assertThat(activityRepo.inserted[0].title).isEqualTo("Abendrunde")
        assertThat(activityRepo.inserted[0].activityTypeId).isEqualTo("joggen")
    }
}
