package com.d_drostes_apps.aevum.domain.analytics

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * M22: Takt für Auswertungen, die LIVE mitlaufen müssen, ohne dass sich
 * Daten ändern — konkret die Ziel-Fortschritte während einer laufenden
 * Aufzeichnung.
 *
 * Nutzer-Report: „Ich habe Studium seit 25 Minuten am Aufzeichnen, aber das
 * Ziel wurde nicht aktualisiert, erst als ich pausiert habe." Ursache: Die
 * ViewModels kombinieren nur Datenbank-Flows; eine laufende Session bleibt
 * aber EINE Zeile (endAt = null), die sich nicht ändert — also kam kein
 * neues Emission. Der Ticker stößt die Auswertung im Takt neu an; die
 * Berechnung selbst rechnet die offene Session bis „jetzt"
 * ([GoalProgressAnalytics]).
 *
 * Nur ein einfaches Signal (Unit) — die Zeit holt sich die Auswertung selbst.
 */
object LiveRefreshTicker {

    /** 10 s: sichtbar „live", aber weit weg von Rechen- oder Akku-Last. */
    const val DEFAULT_INTERVAL_MS = 10_000L

    fun ticker(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(intervalMs)
        }
    }
}
