package com.d_drostes_apps.aevum

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.AllowanceDayOverride
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.data.model.ActivitySessionTag
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.137 (Kanban t_386782be): Integration von Auf-/Zuklappen und
 * Datenbeschaffung — gegen das ECHTE [InsightsViewModel].
 *
 * WARUM ES DIESE SUITE GIBT
 *
 * Die Vorgaenger-Karten sicherten das Aufklappen auf zwei Ebenen einzeln ab
 * (Datenschicht: Liste vollstaendig und sortiert; UI: Karte zeigt 5 bzw. alle
 * Zeilen). Nicht geprueft war die Naht dazwischen: dass der UiState beim
 * Umschalten tatsaechlich die vollstaendige Liste traegt, dass zugeklappte
 * Sicht und vollstaendige Liste deckungsgleich sind (keine Luecke, keine
 * Dublette) und dass ein Retry aus dem Fehlerzustand wieder echte Daten
 * liefert. Genau das ist der Auftrag dieser Karte.
 *
 * Gefahren wird das echte ViewModel mit In-Memory-Fakes — kein Mock, der die
 * zu pruefende Verdrahtung ersetzen wuerde, und kein zweiter Fetch-Pfad, den
 * es in der App nicht gibt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsightsWiringIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------
    // Testdaten: 7 Aktivitaeten — mehr als die 5er-Grenze, klar
    // unterschiedlich lang, damit Reihenfolge und Vollstaendigkeit
    // eindeutig pruefbar sind.
    // ---------------------------------------------------------------

    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("sport", "Sport", "#22C55E", "▲")
    )
    private val types = listOf(
        ActivityType("deep_work", "Deep Work", "work"),
        ActivityType("gym", "Fitnessstudio", "sport"),
        ActivityType("phone", "Smartphone", "work"),
        ActivityType("friends", "Freunde", "sport"),
        ActivityType("reading", "Lesen", "work"),
        ActivityType("cooking", "Kochen", "work"),
        ActivityType("walking", "Spazieren", "sport")
    )

    private val today: LocalDate = LocalDate.now()

    /**
     * Sieben Sessions heute mit klar unterschiedlicher Dauer — absteigend
     * 210, 180, 150, 120, 90, 60, 30 Minuten.
     *
     * WARUM MINUTEN STATT STUNDEN: die erste Fassung nutzte 7h..1h und
     * summierte sich damit auf 28h. Vier Aktivitaeten fielen dadurch hinter
     * Mitternacht und aus dem "Heute"-Fenster — der Test war rot, weil die
     * Testdaten falsch waren, nicht der Code. 14h am Stueck ab 8 Uhr passen
     * vollstaendig in den Tag und lassen die Sortierung eindeutig.
     */
    private fun sessions(): List<ActivitySession> {
        val plan = listOf(
            "deep_work" to 210, "gym" to 180, "phone" to 150, "friends" to 120,
            "reading" to 90, "cooking" to 60, "walking" to 30
        )
        val sportTypes = setOf("gym", "friends", "walking")
        // Lueckenlos von 8 Uhr an aufeinanderlegen; die Dauer ergibt die Sortierung.
        var cursor = today.atTime(8, 0).atZone(zone)
        return plan.mapIndexed { index, (typeId, durationMinutes) ->
            val start = cursor
            val end = start.plusMinutes(durationMinutes.toLong())
            cursor = end
            ActivitySession(
                id = "s$index",
                title = typeId,
                activityTypeId = typeId,
                categoryId = if (typeId in sportTypes) "sport" else "work",
                startAt = start.toInstant().toEpochMilli(),
                endAt = end.toInstant().toEpochMilli()
            )
        }
    }

    // ---------------------------------------------------------------
    // Fakes — echte Interfaces, In-Memory-Daten.
    // ---------------------------------------------------------------

    /**
     * Activity-Repository als In-Memory-Quelle.
     *
     * WICHTIG: `failReads` wird beim JEWEILIGEN Sammeln geprueft (im
     * flow-Builder), nicht bei der Konstruktion des ViewModels. Nur so kann
     * der Retry-Test die Ursache beheben und beweisen, dass `retry()` den
     * Lesevorgang wirklich erneut ausfuehrt.
     */
    private class FakeActivityRepository : ActivityRepository {
        private val sessions = MutableSharedFlow<List<ActivitySession>>(replay = 1)
        var failReads: Boolean = false

        fun setSessions(list: List<ActivitySession>) {
            sessions.tryEmit(list)
        }

        override fun getAll(): Flow<List<ActivitySession>> = flow {
            if (failReads) throw IllegalStateException("DB-Lesefehler (Test)")
            emitAll(sessions)
        }

        override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = getAll()
        override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = getAll()
        override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long) = getAll()
        override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long) = getAll()
        override fun getBySourceType(sourceType: String) = getAll()
        override suspend fun getLastFinishedBySourceType(sourceType: String): ActivitySession? = null
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
        override suspend fun insert(session: ActivitySession) {}
        override suspend fun insertWithTags(session: ActivitySession, tags: List<Tag>) {}
        override suspend fun update(session: ActivitySession) {}
        override suspend fun softDelete(id: String, now: Long) {}
        override suspend fun delete(id: String) {}
        override suspend fun insertTagMapping(mapping: ActivitySessionTag) {}
        override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteTagMappings(sessionId: String) {}
        override suspend fun countSessionsByType(typeId: String): Int = 0
        override suspend fun countLiveSessionsByType(typeId: String): Int = 0
        override suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long) {}
        override suspend fun hardDeleteSessionsByType(typeId: String) {}
        override suspend fun setManualQualityOverride(sessionId: String, score: Int?) {}
        override suspend fun setManualQualityOverrideForRange(start: Long, end: Long, score: Int?) {}
    }

    private class FakeCategoryRepository(private val data: List<Category>) : CategoryRepository {
        override fun getAll(): Flow<List<Category>> = flowOf(data)
        override fun getById(id: String): Flow<Category?> = flowOf(data.firstOrNull { it.id == id })
        override suspend fun insert(category: Category) {}
        override suspend fun insertAll(categories: List<Category>) {}
        override suspend fun update(category: Category) {}
        override suspend fun delete(id: String) {}
    }

    private class FakeActivityTypeRepository(private val data: List<ActivityType>) : ActivityTypeRepository {
        override fun getById(id: String): Flow<ActivityType?> = flowOf(data.firstOrNull { it.id == id })
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(data)
        override fun getAll(): Flow<List<ActivityType>> = flowOf(data)
        override fun getFavorites(): Flow<List<ActivityType>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun setPositivityScore(id: String, score: Int) {}
        override suspend fun setIcon(id: String, icon: String) {}
        override suspend fun setColor(id: String, color: Long) {}
        override suspend fun setCategory(id: String, categoryId: String?) {}
        override suspend fun insert(type: ActivityType) {}
        override suspend fun insertAll(types: List<ActivityType>) {}
        override suspend fun update(type: ActivityType) {}
        override suspend fun delete(typeId: String) {}
    }

    private class FakeDailyAllowanceRepository : DailyAllowanceRepository {
        override fun getAll(): Flow<List<DailyAllowance>> = flowOf(emptyList())
        override suspend fun getEnabled(): List<DailyAllowance> = emptyList()
        override suspend fun getById(id: String): DailyAllowance? = null
        override suspend fun insert(allowance: DailyAllowance) {}
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun delete(id: String) {}
        override suspend fun deleteAccumulationsForAllowance(allowanceId: String) {}
        override suspend fun getAccumulationForDate(date: String): List<AllowanceAccumulationDay> = emptyList()
        override suspend fun getAccumulationInRange(startDate: String, endDate: String): List<AllowanceAccumulationDay> = emptyList()
        override suspend fun insertAccumulation(accumulation: AllowanceAccumulationDay) {}
        override suspend fun getOverridesForDate(date: String): List<AllowanceDayOverride> = emptyList()
        override fun getOverridesForDateFlow(date: String): Flow<List<AllowanceDayOverride>> = flowOf(emptyList())
        override suspend fun getOverride(date: String, allowanceId: String): AllowanceDayOverride? = null
        override suspend fun insertOverride(override: AllowanceDayOverride) {}
        override suspend fun deleteOverride(date: String, allowanceId: String) {}
    }

    /** DataStore-Stand mit festem Sprachwert — kein echter DataStore im Test. */
    private class FakePreferencesDataStore(language: String = "de") : DataStore<Preferences> {
        private var prefs: Preferences =
            mutablePreferencesOf(stringPreferencesKey("app_language") to language)
        override val data: Flow<Preferences> = flowOf(prefs)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            prefs = transform(prefs)
            return prefs
        }
    }

    private fun viewModel(activityRepository: FakeActivityRepository): InsightsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return InsightsViewModel(
            application = app,
            activityRepository = activityRepository,
            categoryRepository = FakeCategoryRepository(categories),
            activityTypeRepository = FakeActivityTypeRepository(types),
            dailyAllowanceRepository = FakeDailyAllowanceRepository(),
            languageRepository = LanguageRepository(context = app, dataStore = FakePreferencesDataStore())
        )
    }

    /**
     * Haelt den `stateIn(WhileSubscribed)`-Flow aktiv.
     *
     * Ohne einen Collector bleibt `uiState` beim Startwert stehen und das
     * ViewModel baut NICHTS auf — der Test wuerde dann gruen, ohne je Daten
     * gesehen zu haben. `backgroundScope` wird am Ende von runTest
     * automatisch abgebrochen, deshalb kein Hang.
     */
    private fun TestScope.collectUiState(vm: InsightsViewModel) {
        backgroundScope.launch {
            vm.uiState.collect { /* bewusst kein Verbrauch: nur aktiv halten */ }
        }
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    /**
     * KERN-ZUSICHERUNG dieser Karte: der UiState traegt nach dem Aufbau ALLE
     * sieben Aktivitaeten — nicht nur die Top 5. Genau diese Liste sieht die
     * aufgeklappte UI.
     */
    @Test
    fun uiStateCarriesAllSevenActivitiesForTheExpandedView() = runTest(dispatcher) {
        val repo = FakeActivityRepository()
        repo.setSessions(sessions())
        val vm = viewModel(repo)
        collectUiState(vm)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.errorMessage).isNull()
        assertThat(state.allBreakdown).hasSize(7)
        assertThat(state.allBreakdown.map { it.label })
            .containsExactly(
                "Deep Work", "Fitnessstudio", "Smartphone",
                "Freunde", "Lesen", "Kochen", "Spazieren"
            )
            .inOrder()
    }

    /**
     * Keine Dublette, keine Luecke beim Umschalten: die zugeklappt sichtbaren
     * fuenf Zeilen sind exakt die ersten fuenf der vollstaendigen Liste. Die
     * UI schneidet selbst zu, die Quelle ist dieselbe — es kann also weder
     * eine Zeile doppelt erscheinen noch beim Aufklappen eine fehlen.
     */
    @Test
    fun collapsedRowsAreExactlyTheFirstFiveOfTheFullList() = runTest(dispatcher) {
        val repo = FakeActivityRepository()
        repo.setSessions(sessions())
        val vm = viewModel(repo)
        collectUiState(vm)

        advanceUntilIdle()

        val all = vm.uiState.value.allBreakdown
        val collapsed = all.take(5)
        val tail = all.drop(5)

        assertThat(collapsed).hasSize(5)
        assertThat(tail).hasSize(2)
        assertThat(collapsed.map { it.id }.toSet().intersect(tail.map { it.id }.toSet())).isEmpty()
        assertThat(collapsed + tail).isEqualTo(all)
    }

    /**
     * Das Umschalten ist ein reiner UI-Zustand: die Datenliste bleibt
     * identisch, nichts wird nachgeladen oder verworfen. Damit ist die
     * Anforderung "no duplicate or stale data" strukturell garantiert.
     */
    @Test
    fun togglingExpandedDoesNotChangeTheData() = runTest(dispatcher) {
        val repo = FakeActivityRepository()
        repo.setSessions(sessions())
        val vm = viewModel(repo)
        collectUiState(vm)

        advanceUntilIdle()
        val before = vm.uiState.value.allBreakdown
        assertThat(vm.uiState.value.topActivitiesExpanded).isFalse()

        vm.toggleTopActivitiesExpanded()
        advanceUntilIdle()
        assertThat(vm.uiState.value.topActivitiesExpanded).isTrue()
        assertThat(vm.uiState.value.allBreakdown).isEqualTo(before)

        vm.toggleTopActivitiesExpanded()
        advanceUntilIdle()
        assertThat(vm.uiState.value.topActivitiesExpanded).isFalse()
        assertThat(vm.uiState.value.allBreakdown).isEqualTo(before)
    }

    /**
     * Fehlerpfad end-to-end: ein kaputter Lesevorgang erzeugt den
     * Fehlerzustand mit Text (nicht "keine Daten"), und `retry()` holt
     * anschliessend echte Daten — der Nutzer ist nicht in der App gefangen.
     */
    @Test
    fun failedBuildEmitsErrorMessageAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeActivityRepository()
        repo.failReads = true
        val vm = viewModel(repo)
        collectUiState(vm)

        advanceUntilIdle()

        val errored = vm.uiState.value
        assertThat(errored.errorMessage).isNotNull()
        assertThat(errored.isLoading).isFalse()
        assertThat(errored.allBreakdown).isEmpty()

        // Ursache beheben und erneut versuchen: retry() baut den Flow-Baum neu.
        repo.failReads = false
        repo.setSessions(sessions())
        vm.retry()
        advanceUntilIdle()

        val recovered = vm.uiState.value
        assertThat(recovered.errorMessage).isNull()
        assertThat(recovered.allBreakdown).hasSize(7)
    }

    /**
     * Kaltstart: bevor der erste echte Wert da ist, steht `isLoading` — die
     * UI zeigt in dieser Phase den Platzhalter statt faelschlich "keine Daten".
     */
    @Test
    fun coldStartStartsWithLoadingTrue() = runTest(dispatcher) {
        val repo = FakeActivityRepository()
        repo.setSessions(sessions())
        val vm = viewModel(repo)

        // Ohne advanceUntilIdle: der stateIn-Startwert steht noch.
        assertThat(vm.uiState.value.isLoading).isTrue()
        assertThat(vm.uiState.value.errorMessage).isNull()
    }
}
