package com.d_drostes_apps.aevum.ui.screens.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.seed.EnsureDefaultDataUseCase
import com.d_drostes_apps.aevum.domain.seed.SeedSuppressionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M34 — Zustand des Willkommens-Dialogs.
 *
 * Aufgaben:
 *  1. Vorschläge laden: Kategorien und Aktivitäten kommen aus der Datenbank
 *     (die Seed-Daten legt [EnsureDefaultDataUseCase] an) — nicht aus einer
 *     zweiten, parallelen Liste in der UI. Was der Nutzer hier sieht, ist
 *     exakt das, was nachher im Dashboard steht.
 *  2. Auswahl halten (entfernen/hinzufügen) und beim Abschluss anwenden.
 *  3. Maskottchen, Sprache und Berechtigungen persistieren.
 *
 * Bewusst KEINE eigene Kopie der Seeds: würde die UI eigene Vorschläge
 * anzeigen und beim Abschluss „die nicht gewählten löschen", müsste sie die
 * Seed-IDs ein zweites Mal kennen — und liefe bei jeder Seed-Änderung aus dem
 * Takt.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageRepository: LanguageRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val mascotRepository: MascotRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase,
    private val seedSuppression: SeedSuppressionStore
) : ViewModel() {

    /** Ein auswählbarer Eintrag — Kategorie oder Aktivität. */
    data class PickItem(
        val id: String,
        val label: String,
        val icon: String,
        val colorArgb: Long,
        val isSeed: Boolean
    )

    data class OnboardingUiState(
        val page: Int = 0,
        val language: String = LanguageRepository.LANGUAGE_DEFAULT,
        val categories: List<PickItem> = emptyList(),
        val selectedCategoryIds: Set<String> = emptySet(),
        val activities: List<PickItem> = emptyList(),
        val selectedActivityIds: Set<String> = emptySet(),
        val mascotEnabled: Boolean = false,
        val mascotColor: String = MascotRepository.COLOR_DEFAULT,
        val mascotAntenna: String = MascotRepository.ANTENNA_DEFAULT,
        val mascotCheeks: Boolean = MascotRepository.CHEEKS_DEFAULT,
        /** IDs der eingeschalteten Berechtigungen (siehe [PermissionOption]). */
        val permissions: Set<String> = DEFAULT_PERMISSIONS,
        /** true, sobald die Vorschläge aus der Datenbank da sind. */
        val ready: Boolean = false
    )

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * Wurde die Auswahl schon einmal aus der Datenbank befüllt? Ohne dieses
     * Flag würde JEDE Flow-Emission die Auswahl des Nutzers zurücksetzen
     * (z. B. nachdem er eine Aktivität hinzugefügt hat).
     */
    private var categoriesInitialized = false
    private var activitiesInitialized = false

    /**
     * IDs, die beim nächsten DB-Ausstoß automatisch ausgewählt werden —
     * nur Einträge, die der Nutzer in DIESEM Wizard gerade angelegt hat.
     * Ein pauschales „alle eigenen Einträge sind ausgewählt" wäre falsch:
     * ein absichtlich abgewählter eigener Eintrag würde bei der nächsten
     * Emission wieder auftauchen.
     */
    private val pendingAutoSelect = mutableSetOf<String>()

    /** Zähler für selbst angelegte Einträge (Sortierung/Farbwahl). */
    private var customCounter = 0

    init {
        viewModelScope.launch {
            // Seeds sicherstellen, BEVOR die Liste gelesen wird. Auf einer
            // frischen Installation läuft der Seed-Lauf zwar schon in
            // AevumApplication.onCreate — der ist asynchron, und der Nutzer
            // kann die zweite Seite schneller erreichen, als er fertig ist.
            runCatching { ensureDefaultData() }
        }
        viewModelScope.launch {
            categoryRepository.getAll().collect { categories ->
                val items = categories
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                    .map {
                        PickItem(
                            id = it.id,
                            label = it.name,
                            icon = it.icon,
                            colorArgb = parseHexColor(it.color),
                            isSeed = it.id in EnsureDefaultDataUseCase.SEED_CATEGORY_IDS
                        )
                    }
                _state.update { current ->
                    val ids = items.map { it.id }.toSet()
                    current.copy(
                        categories = items,
                        selectedCategoryIds = if (categoriesInitialized) {
                            // Auswahl erhalten, nur verschwundene IDs fallen
                            // heraus; frisch angelegte rücken nach.
                            current.selectedCategoryIds.intersect(ids) +
                                pendingAutoSelect.intersect(ids)
                        } else {
                            items.map { it.id }.toSet()
                        },
                        ready = categoriesInitialized || items.isNotEmpty()
                    )
                }
                pendingAutoSelect.removeAll { it !in items.map { item -> item.id } }
                categoriesInitialized = true
            }
        }
        viewModelScope.launch {
            activityTypeRepository.getAll().collect { types ->
                val items = types
                    .sortedWith(compareBy({ it.defaultCategoryId ?: "" }, { it.name }))
                    .map {
                        PickItem(
                            id = it.id,
                            label = it.name,
                            icon = it.icon,
                            colorArgb = it.color,
                            isSeed = it.id in EnsureDefaultDataUseCase.SEED_ACTIVITY_TYPE_IDS
                        )
                    }
                _state.update { current ->
                    val ids = items.map { it.id }.toSet()
                    current.copy(
                        activities = items,
                        selectedActivityIds = if (activitiesInitialized) {
                            current.selectedActivityIds.intersect(ids) +
                                pendingAutoSelect.intersect(ids)
                        } else {
                            items.map { it.id }.toSet()
                        }
                    )
                }
                pendingAutoSelect.removeAll { it !in items.map { item -> item.id } }
                activitiesInitialized = true
            }
        }
        // Sprache: gespeicherter Stand (Default Englisch).
        viewModelScope.launch {
            val stored = languageRepository.language.first()
            _state.update { it.copy(language = stored) }
        }
        // Maskottchen: gespeicherter Stand (Default aus).
        viewModelScope.launch {
            val stored = mascotRepository.config.first()
            _state.update {
                it.copy(
                    mascotEnabled = stored.enabled,
                    mascotColor = stored.colorId,
                    mascotAntenna = stored.antennaStyle,
                    mascotCheeks = stored.cheeks
                )
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────

    fun goTo(page: Int) {
        _state.update { it.copy(page = page.coerceIn(0, PAGE_COUNT - 1)) }
    }

    fun next() = goTo(_state.value.page + 1)

    fun back() = goTo(_state.value.page - 1)

    // ── Seite 1: Sprache ──────────────────────────────────────────────────

    /**
     * Sprache wählen. Wird sofort persistiert, damit ein Neustart mitten im
     * Wizard die Wahl behält; die restlichen Seiten lesen ihre Texte über
     * `LocaleHelper.resourcesFor(...)` und wechseln dadurch ohne Recreate.
     */
    fun selectLanguage(code: String) {
        _state.update { it.copy(language = code) }
        viewModelScope.launch { languageRepository.setLanguage(code) }
    }

    // ── Seite 2: Kategorien + Aktivitäten ─────────────────────────────────

    fun toggleCategory(id: String) {
        _state.update { current ->
            val next = if (id in current.selectedCategoryIds) {
                current.selectedCategoryIds - id
            } else {
                current.selectedCategoryIds + id
            }
            current.copy(selectedCategoryIds = next)
        }
    }

    fun toggleActivity(id: String) {
        _state.update { current ->
            val next = if (id in current.selectedActivityIds) {
                current.selectedActivityIds - id
            } else {
                current.selectedActivityIds + id
            }
            current.copy(selectedActivityIds = next)
        }
    }

    fun addCategory(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val entryId = "custom_" + UUID.randomUUID().toString().take(8)
        viewModelScope.launch {
            pendingAutoSelect += entryId
            runCatching {
                categoryRepository.insert(
                    Category(
                        id = entryId,
                        name = clean,
                        color = CUSTOM_COLORS[customCounter % CUSTOM_COLORS.size],
                        icon = CUSTOM_ICONS[customCounter % CUSTOM_ICONS.size],
                        isSystem = false,
                        sortOrder = 500 + customCounter,
                    )
                )
            }
            customCounter++
        }
    }

    fun addActivity(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val entryId = "custom_" + UUID.randomUUID().toString().take(8)
        viewModelScope.launch {
            pendingAutoSelect += entryId
            runCatching {
                activityTypeRepository.insert(
                    ActivityType(
                        id = entryId,
                        name = clean,
                        defaultCategoryId = null,
                        isSystem = false,
                        propertiesJson = null,
                        isFavorite = false,
                        positivityScore = 50,
                        icon = "✨",
                        color = CUSTOM_ACTIVITY_COLORS[customCounter % CUSTOM_ACTIVITY_COLORS.size]
                    )
                )
            }
            customCounter++
        }
    }

    // ── Seite 3: Maskottchen ──────────────────────────────────────────────

    /** Schaltet das Maskottchen an/aus und persistiert sofort (Vorschau-Live). */
    fun setMascotEnabled(enabled: Boolean) {
        _state.update { it.copy(mascotEnabled = enabled) }
        viewModelScope.launch { mascotRepository.setEnabled(enabled) }
    }

    fun setMascotColor(colorId: String) {
        _state.update { it.copy(mascotColor = colorId) }
        viewModelScope.launch { mascotRepository.setColor(colorId) }
    }

    fun setMascotAntenna(style: String) {
        _state.update { it.copy(mascotAntenna = style) }
        viewModelScope.launch { mascotRepository.setAntennaStyle(style) }
    }

    fun setMascotCheeks(enabled: Boolean) {
        _state.update { it.copy(mascotCheeks = enabled) }
        viewModelScope.launch { mascotRepository.setCheeks(enabled) }
    }

    // ── Seite 4: Berechtigungen ───────────────────────────────────────────

    fun togglePermission(id: String) {
        _state.update { current ->
            val next = if (id in current.permissions) {
                current.permissions - id
            } else {
                current.permissions + id
            }
            current.copy(permissions = next)
        }
    }

    // ── Abschluss ─────────────────────────────────────────────────────────

    /**
     * Wendet die Auswahl an und schließt den Wizard ab.
     *
     * Reihenfolge ist wichtig:
     *  1. Auswahl anwenden (löschen/unterdrücken) — VOR dem Flag, damit ein
     *     Abbruch danach nicht zu einem halb angewandten Zustand führt.
     *  2. Abschluss-Flag setzen (in [OnboardingGate]) — danach startet der
     *     Wizard nie wieder.
     *  3. Erst danach fragt die UI die Berechtigungen ab.
     */
    suspend fun commit() {
        val snapshot = _state.value
        // Maskottchen — alle vier Eigenschaften, unabhängig vom aktuellen Flow.
        runCatching {
            mascotRepository.setEnabled(snapshot.mascotEnabled)
            mascotRepository.setColor(snapshot.mascotColor)
            mascotRepository.setAntennaStyle(snapshot.mascotAntenna)
            mascotRepository.setCheeks(snapshot.mascotCheeks)
        }

        // Nicht gewählte Kategorien entfernen. Seeds werden zusätzlich
        // unterdrückt, sonst legt der nächste App-Start sie wieder an.
        runCatching {
            val toRemove = categoryRepository.getAll().first()
                .filterNot { it.id in snapshot.selectedCategoryIds }
            val seedIds = toRemove.filter { it.id in EnsureDefaultDataUseCase.SEED_CATEGORY_IDS }
                .map { SeedSuppressionStore.categoryKey(it.id) }
            seedSuppression.suppress(seedIds)
            toRemove.forEach { runCatching { categoryRepository.delete(it.id) } }
        }

        // Nicht gewählte Aktivitäten entfernen (gleiches Muster).
        runCatching {
            val toRemove = activityTypeRepository.getAll().first()
                .filterNot { it.id in snapshot.selectedActivityIds }
            val seedIds = toRemove.filter { it.id in EnsureDefaultDataUseCase.SEED_ACTIVITY_TYPE_IDS }
                .map { SeedSuppressionStore.activityKey(it.id) }
            seedSuppression.suppress(seedIds)
            toRemove.forEach { runCatching { activityTypeRepository.delete(it.id) } }
        }

        runCatching { languageRepository.setLanguage(snapshot.language) }
        OnboardingGate.markCompleted(context)
    }

    companion object {
        const val PAGE_COUNT = 5

        /** Reihenfolge = Anzeige-Reihenfolge auf Seite 4. */
        val DEFAULT_PERMISSIONS: Set<String> = setOf("location", "activity", "notifications")

        val CUSTOM_COLORS = listOf(
            "#6366F1", "#0EA5E9", "#22C55E", "#F59E0B", "#EC4899", "#A855F7"
        )
        val CUSTOM_ICONS = listOf("◼", "●", "◆", "◎", "✦", "♡")

        val CUSTOM_ACTIVITY_COLORS = listOf(
            0xFF5C6BC0L, 0xFF26A69A, 0xFFEF5350, 0xFFFFA726, 0xFFAB47BC, 0xFF66BB6A
        )

        /** "#RRGGBB" oder "#AARRGGBB" → ARGB-Long. Unbekannt → neutrales Grau. */
        fun parseHexColor(value: String): Long {
            val hex = value.removePrefix("#")
            return runCatching {
                when (hex.length) {
                    6 -> 0xFF000000L or java.lang.Long.parseLong(hex, 16)
                    8 -> java.lang.Long.parseLong(hex, 16)
                    else -> 0xFF94A3B8L
                }
            }.getOrDefault(0xFF94A3B8L)
        }
    }
}
