package com.d_drostes_apps.aevum.ui.screens.insights

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.R
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InsightsViewModel @Inject constructor(
    private val application: Application,
    private val activityRepository: ActivityRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    // M17.4: Tagespauschalen für die Statistik-Aggregation.
    private val dailyAllowanceRepository: DailyAllowanceRepository,
    // L10N-RUNTIME-FIX: Sprach-Flow — bei Sprachwechsel zur Laufzeit wird
    // die komplette Statistik (inkl. application.getString-Texte) neu gebaut.
    languageRepository: com.d_drostes_apps.aevum.data.repository.LanguageRepository,
    // M18.138: Bildschirmzeit-Quelle (Digital Balance) — EINE Wahrheit für
    // alle Statistiken.
    private val balanceSource: com.d_drostes_apps.aevum.domain.digital.DigitalBalanceSource
) : ViewModel() {
    private val zoneId = java.time.ZoneId.systemDefault()

    // M18.137 (Kanban t_13e9f843): Retry-Trigger.
    //
    // Warum auf DIESER Ebene und nicht innerhalb des combine? Ein `.catch`
    // auf dem inneren Flow beendet ihn nach dem Fehler-Emissions-Wert — der
    // innere combine ist dann tot. Ein Trigger, der nur IM combine haengt,
    // koennte ihn nicht wiederbeleben. Steht der Trigger im flatMapLatest,
    // baut ein Bump den kompletten inneren Baum neu auf und ist damit ein
    // echter Retry.
    private val _retryTrigger = MutableStateFlow(0)

    // M18.34: Die letzte Period-Auswahl wird in SharedPreferences
    // persistiert und beim naechsten Oeffnen wiederhergestellt.
    // Default: Today (User-Praeferenz: "ich praefriere die heute ansicht").
    private val prefs = application.getSharedPreferences("aevum_insights", android.content.Context.MODE_PRIVATE)
    private val _selectedPeriod = MutableStateFlow(
        InsightPeriod.fromStorage(prefs.getString(KEY_PERIOD, null))
    )
    private val _selectedHeatmapDate = MutableStateFlow<java.time.LocalDate?>(null)
    // M18.146: Navigierbarer Anker-Tag. Vorher war der Anker FEST der
    // App-Start-Tag — ein Durchskippen durch Tage/Wochen/Monate war deshalb
    // unmöglich, und die Pauschalen rechneten in einer eigenen, rollierenden
    // „letzte 30 Tage"-Logik, während die Sessions kalendarisch zählten
    // (Bug: „Monatsansicht summiert 30 Tage Pauschale statt Kalendermonat").
    // Jetzt: EIN Anker, EINE Perioden-Definition ([InsightsAnalytics.periodDates]),
    // alle Verbraucher leiten daraus ab.
    private val _anchorDate = MutableStateFlow(java.time.LocalDate.now())
    // M17.4: Toggle zwischen Aktivitäts- und Kategorie-Aufschlüsselung.
    private val _breakdownMode = MutableStateFlow(BreakdownMode.Activity)
    // M18.137 (Kanban t_70a06809): Auf-/Zuklappen der Top-Liste.
    // Persistiert wie die Period-Auswahl (M18.34) — die Einstellung ist eine
    // Nutzer-Präferenz und soll einen Screen-Wechsel/Neustart überleben.
    private val _topActivitiesExpanded = MutableStateFlow(
        prefs.getBoolean(KEY_TOP_ACTIVITIES_EXPANDED, false)
    )

    // M18.146: combine-Operator-Limit (max. 5 typisierte Flows) — darum
    // werden Ausklapp-Zustand und Anker-Tag zu EINEM Paar-Flow verschachtelt
    // (Muster aus PlaceTimelineViewModel / TriggerSettingsScreen).
    private val _viewPrefs = combine(
        _topActivitiesExpanded,
        _anchorDate
    ) { expanded, anchor -> expanded to anchor }

    private val dataFlow = combine(
        activityRepository.getAll(),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        // M17.4: Tagespauschalen — wir laden ALLE Accumululations, weil
        // die Period-Filter (Woche/Monat) in InsightsAnalytics.apply()
        // entschieden werden, nicht hier im ViewModel.
        dailyAllowanceRepository.getAll(),
        // M18.138: Bildschirmzeit (Digital Balance) — ersetzt die
        // Aufzeichnungen in allen Kennzahlen. 60s-Takt wie der Balance-Tab.
        //
        // M18.146: Das Fenster folgt jetzt der GEZEIGTEN Periode statt eines
        // festen 62-Tage-Rückblicks. Seit die Statistik durch Tage/Wochen/
        // Monate navigierbar ist, wäre der feste Rückblick falsch: nach dem
        // Skippen in den Vormonat fehlten dort alle Digital-Balance-Werte.
        // Abgedeckt wird: gezeigte Periode + Vorperiode (Vergleichs-Changes)
        // + komplette Kalenderwoche des Ankers (Heatmap zeigt stets diese
        // Woche). flatMapLatest baut das Abonnement bei jedem Skip um.
        combine(_selectedPeriod, _anchorDate) { period, anchor -> period to anchor }
            .flatMapLatest { (period, anchor) ->
                val dates = InsightsAnalytics.periodDates(period, anchor)
                val coverageStart = minOf(dates.previousStart, PeriodLabels.weekStart(anchor))
                balanceSource.dailyTotalsInRange(coverageStart, dates.endExclusive)
            }
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val sessions = values[0] as List<ActivitySession>
        val categories = values[1] as List<Category>
        val types = values[2] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val allowances = values[3] as List<com.d_drostes_apps.aevum.data.model.DailyAllowance>
        @Suppress("UNCHECKED_CAST")
        val balance = values[4] as Map<java.time.LocalDate, Long>
        // M17.4: Hole die Accumulations einmalig (suspend → first())
        // Achtung: getAll() auf Accumulation existiert nicht im
        // Repository, also müssen wir die Accumulation-Reads im
        // Analytics-Build machen. Wir übergeben nur die Allowance-Liste
        // und laden die Accumulations dort on-demand.
        DataLayer(sessions, categories, types, allowances, balance)
    }

    val uiState: StateFlow<InsightsUiState> = combine(
        languageRepository.language,
        _retryTrigger
    ) { _, retry -> retry }
        .flatMapLatest { _ ->
            // L10N-RUNTIME-FIX: Sprachwechsel → kompletter Rebuild.
            // M18.137: Auch ein Retry landet hier — der Bump des Triggers
            // startet den kompletten inneren Baum neu, inklusive der
            // Accumulation-Reads, die im Aufbau stecken.
            combine(
                dataFlow,
                _selectedPeriod,
                _selectedHeatmapDate,
                _breakdownMode,
                // M18.146: 5. Flow — Paar aus Ausklapp-Zustand + Anker-Tag
                // (6 einzelne Flows wären über dem combine-Limit).
                _viewPrefs
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val data = values[0] as DataLayer
                val period = values[1] as InsightPeriod
                val heatmapDate = values[2] as java.time.LocalDate?
                val breakdownMode = values[3] as BreakdownMode
                // M18.137 (Kanban t_70a06809): Ausklapp-Zustand der Top-Liste.
                val topExpanded = (values[4] as Pair<Boolean, java.time.LocalDate>).first
                // M18.146: Anker-Tag der aktuell gezeigten Periode.
                val anchorDate = (values[4] as Pair<Boolean, java.time.LocalDate>).second
        // M18.138: Bildschirmzeit je Tag (Digital Balance) — sie ersetzt
        // die Aufzeichnungen in allen Kennzahlen dieses Screens.
        val balanceMsPerDay = data.balanceMsPerDay
        val typeMap = data.types.associateBy { it.id }
        // M17.4: Tagespauschalen-Accumulations im aktuellen Zeitraum laden
        // und zu den Sessions addieren. Bewusst nur in der Statistik, nicht
        // in der Timeline.
        // M18.33: ZUSAETZLICH enabled Allowances on-the-fly fuer die
        // aktuellen Tage einrechnen — eine heute erstellte Pauschale
        // erscheint SOFORT, nicht erst nach dem Midnight-Worker (00:05).
        // M18.146-FIX (User: „Pauschale zählt in der Monatsansicht 30 Tage
        // statt Kalendermonat"): Die Pauschalen benutzen jetzt DIESELBE
        // Perioden-Definition wie die Sessions — [InsightsAnalytics.periodDates].
        // Vorher hatte das ViewModel eine eigene, rollierende Rechnung
        // (Monat = minusDays(29)), während InsightsAnalytics.build für die
        // Sessions kalendarisch rechnete (Monat ab dem 1.). Zwei Definitionen
        // — zwei Wahrheiten; sichtbar wurde der Widerspruch als „Pauschale
        // summiert die letzten 30 Tage, Sessions nur den Monat".
        val periodDates = InsightsAnalytics.periodDates(period, anchorDate)
        val periodStart = periodDates.start
        val periodEndInclusive = periodDates.endInclusive
        val allowanceAccums = dailyAllowanceRepository.getAccumulationInRange(
            periodStart.toString(), periodEndInclusive.toString()
        )
            // M18.37-FIX (Self-Healing): Verwaiste Accumulations ignorieren —
            // wenn die zugehoerige Allowance geloescht wurde (z.B. Pauschale
            // geloescht + neu erstellt), zaehlt die alte Accumulation sonst
            // doppelt. Nur Accumulations behalten, deren Allowance existiert.
            .filter { acc -> data.allowances.any { it.id == acc.allowanceId } }
            .toMutableList()
        // Heutige Pauschalen (und alle Tage ohne Accumulation) on-the-fly:
        // pro Tag im Zeitraum wird jede enabled Allowance addiert, die noch
        // keinen Accumulation-Eintrag hat (idempotent via PK-Check).
        // M18.146: Nur Tage BIS HEUTE — beim Vorwärts-Skippen in die Zukunft
        // gilt eine Pauschale (noch) nicht.
        val existingKeys = allowanceAccums.map { it.date to it.allowanceId }.toSet()
        val today = java.time.LocalDate.now()
        val enabledAllowances = data.allowances.filter { it.enabled }
        var cursor = periodStart
        while (!cursor.isAfter(periodEndInclusive)) {
            if (cursor <= today) {
                enabledAllowances.forEach { allowance ->
                    val key = cursor.toString() to allowance.id
                    if (key !in existingKeys) {
                        allowanceAccums.add(
                            com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay(
                                date = cursor.toString(),
                                timezoneId = zoneId.id,
                                allowanceId = allowance.id,
                                activityTypeId = allowance.activityTypeId,
                                minutes = allowance.minutesPerDay
                            )
                        )
                    }
                }
            }
            cursor = cursor.plusDays(1)
        }
        InsightsAnalytics.build(
            context = application,
            sessions = data.sessions,
            categories = data.categories,
            activityTypes = data.types,
            selectedPeriod = period,
            anchorDate = anchorDate,
            zoneId = zoneId,
            // M17.4: neue Parameter
            allowanceAccumulations = allowanceAccums,
            breakdownMode = breakdownMode,
            // M18.138: Bildschirmzeit ersetzt die Aufzeichnungen (SCREEN_AUTO)
            // in sämtlichen Kennzahlen dieser Ansicht.
            balanceMsPerDay = balanceMsPerDay
        ).copy(
            selectedHeatmapDate = heatmapDate,
            // M18.146: Anker-Tag für die Navigationszeile (UI-Zustand).
            anchorDate = anchorDate,
            // M18.137 (Kanban t_70a06809): Reiner UI-Zustand — wird bewusst
            // NICHT in InsightsAnalytics.build entschieden, damit die
            // Analytik unabhängig von der Darstellung bleibt.
            topActivitiesExpanded = topExpanded,
            // M18.137: Der Aufbau ist durchgelaufen → kein Ladezustand,
            // kein Fehler mehr. Beide Flags gehoeren in den ERFOLGS-Pfad,
            // damit ein Retry sie zuverlaessig zuruecksetzt.
            isLoading = false,
            errorMessage = null
        )
        }
        // M18.137 (Kanban t_13e9f843): Fehlerzustand. Ein Fehler im Aufbau
        // (z.B. DB-Lesefehler bei den Accumulations) darf den Screen nicht
        // leer lassen und nicht als unbehandelte Exception hochschlagen —
        // stattdessen ein benannter Fehlertext plus Retry-Angebot in der UI.
        //
        // Bewusst KEIN emit eines Default-States ohne Fehlertext: sonst
        // saehe der User "keine Daten" statt "Laden fehlgeschlagen" und
        // haette keinen Weg zurueck ausser die App neu zu starten.
        .catch { e ->
            android.util.Log.e(
                "InsightsViewModel",
                "uiState combine() failed — emitting error state",
                e
            )
            emit(
                InsightsUiState(
                    isLoading = false,
                    errorMessage = application.getString(R.string.insights_load_failed),
                    periodLabel = application.getString(R.string.common_today)
                )
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // M18.137: Kaltstart-Zustand = "laedt". Der erste echte Wert
        // ersetzt ihn; bis dahin zeigt die UI einen Indikator statt
        // faelschlich "keine Daten".
        InsightsUiState(
            periodLabel = application.getString(R.string.common_today),
            summary = application.getString(R.string.insights_summary_empty),
            isLoading = true
        )
    )

    /**
     * M18.137 (Kanban t_13e9f843): Einstiegspunkt fuer "erneut versuchen",
     * wenn der letzte Aufbau fehlgeschlagen ist.
     *
     * Bumpt den Retry-Trigger. Weil der Trigger im flatMapLatest haengt,
     * wird der KOMPLETTE innere Flow-Baum neu aufgebaut — auch die
     * suspend-Reads, die im Aufbau stecken. Ein Retry, der nur den
     * Zustand zuruecksetzt, wuerde am selben Fehler wieder scheitern.
     */
    fun retry() {
        _retryTrigger.value = _retryTrigger.value + 1
    }

    fun selectPeriod(period: InsightPeriod) {
        _selectedPeriod.value = period
        _selectedHeatmapDate.value = null
        // M18.146: Beim Wechsel der Granularität springt der Anker auf die
        // AKTUELLE Periode zurück. Sonst würde z.B. ein beim Skippen in den
        // Mai gesetzter Anker nach dem Wechsel auf „Woche" plötzlich eine
        // Mai-Woche zeigen, obwohl der Nutzer gerade „jetzt" erwartet.
        _anchorDate.value = java.time.LocalDate.now()
        // M18.34: Auswahl persistieren — beim naechsten Oeffnen direkt da.
        prefs.edit().putString(KEY_PERIOD, period.name).apply()
    }

    /**
     * M18.146: Durchskippen um ±1 Periode (Tag/Woche/Monat). Basis der
     * ‹ / ›-Buttons in der Navigationszeile. Die Schrittweite ergibt sich
     * aus der gewählten Granularität ([InsightsAnalytics.shiftAnchor]).
     */
    fun stepAnchor(steps: Long) {
        _anchorDate.value = InsightsAnalytics.shiftAnchor(_selectedPeriod.value, _anchorDate.value, steps)
        _selectedHeatmapDate.value = null
    }

    /**
     * M18.146: Direkte Auswahl einer Periode aus dem Zeitraum-Sheet. Der
     * Anker wird auf den Periodenbeginn normalisiert, damit die Auswahl
     * unabhängig davon ist, welcher Tag im Eintrag repräsentiert wurde.
     */
    fun selectAnchor(anchor: java.time.LocalDate) {
        _anchorDate.value = PeriodLabels.normalize(_selectedPeriod.value, anchor)
        _selectedHeatmapDate.value = null
    }

    /** M18.146: Zurück zur aktuellen Periode („Heute"/„Diese Woche"/„Dieser Monat"). */
    fun resetAnchorToToday() {
        _anchorDate.value = java.time.LocalDate.now()
        _selectedHeatmapDate.value = null
    }

    /**
     * M18.146: Ist die aktuell gezeigte Periode NICHT die, in der „heute"
     * liegt? Steuert den „Zurück zu heute"-Affordance in der UI.
     */
    fun isShowingPastPeriod(): Boolean {
        val today = java.time.LocalDate.now()
        return PeriodLabels.normalize(_selectedPeriod.value, _anchorDate.value) !=
            PeriodLabels.normalize(_selectedPeriod.value, today)
    }

    fun selectHeatmapDay(date: java.time.LocalDate) {
        _selectedHeatmapDate.value = date
    }

    /** M17.4: Toggle zwischen Aktivitäts- und Kategorie-Aufschlüsselung. */
    fun setBreakdownMode(mode: BreakdownMode) {
        _breakdownMode.value = mode
        // M18.137: Die Kategorie-Ansicht kennt kein Auf-/Zuklappen (sie zeigt
        // ohnehin alle Kategorien) — beim Wechsel zurück zur Aktivitäts-
        // Ansicht startet die Liste wieder zugeklappt. Sonst stünde der
        // Nutzer nach dem Umschalten vor einer unerwartet langen Liste.
        _topActivitiesExpanded.value = false
        prefs.edit().putBoolean(KEY_TOP_ACTIVITIES_EXPANDED, false).apply()
    }

    /**
     * M18.137 (Kanban t_70a06809): Klappt die Top-Aktivitäten-Liste auf bzw.
     * wieder zu. Der Zustand wird persistiert, damit er einen Screen-Wechsel
     * überlebt (gleiches Muster wie die Period-Auswahl).
     */
    fun toggleTopActivitiesExpanded() {
        val next = !_topActivitiesExpanded.value
        _topActivitiesExpanded.value = next
        prefs.edit().putBoolean(KEY_TOP_ACTIVITIES_EXPANDED, next).apply()
    }

    /**
     * M18.146: ENTFERNT — die rollierende Perioden-Rechnung („letzte 7/30
     * Tage") war die Ursache des Pauschalen-Bugs. Die Perioden-Grenzen
     * kommen jetzt ausschließlich aus [InsightsAnalytics.periodDates]
     * (kalendarisch, dieselbe Quelle wie die Sessions).
     */

    private data class DataLayer(
        val sessions: List<ActivitySession>,
        val categories: List<Category>,
        val types: List<ActivityType>,
        val allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance>,
        // M18.138: Bildschirmzeit je Tag (Digital Balance).
        val balanceMsPerDay: Map<java.time.LocalDate, Long> = emptyMap()
    )

    companion object {
        /**
         * M18.146: Name der SharedPreferences-Datei der Statistik-Ansichten.
         * Public, weil die Aktivitäts-Statistikseite ihre Granularität in
         * derselben Datei persistiert (gleiche Nutzer-Präferenzen, ein Ort).
         */
        const val PREFS_NAME = "aevum_insights"
        // M18.34: Storage-Key fuer die persistierte Period-Auswahl.
        private const val KEY_PERIOD = "selected_period"
        // M18.137 (Kanban t_70a06809): Ausklapp-Zustand der Top-Liste.
        private const val KEY_TOP_ACTIVITIES_EXPANDED = "top_activities_expanded"
        /**
         * M18.138: Rückblick-Fenster für die Bildschirmzeit. Die Insights
         * zeigen höchstens einen Monat; die Vergleichsperiode (Vormonat)
         * braucht die doppelte Reichweite. 62 Tage decken beides ab.
         */
        private const val LOOKBACK_DAYS = 62
    }
}

/** M17.4: Aufschlüsselungs-Modus für die Top-Liste. */
enum class BreakdownMode { Activity, Category }
