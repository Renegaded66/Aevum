package com.d_drostes_apps.aevum.domain.projects

/**
 * M19.1: Feier-Gate — verhindert das klassische Animations-Fiasko.
 *
 * DAS PROBLEM: Ein Feuerwerk, das an „Fortschritt == 100 %" hängt, feuert bei
 * JEDER Neukomposition erneut — und Compose komponiert oft neu (Scrollen,
 * Rotation, Rückkehr auf die Seite, Datenbank-Update). Der Nutzer erlebt dann
 * ein Dauerfeuer und die Belohnung ist kaputt.
 *
 * DIE REGEL: gefeiert wird nur beim ÜBERGANG. Persistiert wird pro Tag, wie
 * viele Todos schon gefeiert wurden:
 *  - neuer Tag                      → feiern (sobald voll)
 *  - voll, aber noch nie gefeiert   → feiern
 *  - voll, aber weniger/gleich wie  → NICHT feiern (Rekomposition, Hin- und
 *    beim bisherigen Feierstand         Herwechseln, Abhaken→Rückgängig→Abhaken)
 *  - voll und MEHR als bisher       → feiern (verdientes zweites Mal: der
 *    gefeierte Stand                     Nutzer hat nachgelegt)
 *
 * REIN und damit ohne Gerät prüfbar.
 */
object PmCelebrationGate {

    /** Feierstand eines Tages. */
    data class State(val date: String? = null, val celebratedDone: Int = 0)

    fun shouldCelebrate(state: State, todayIso: String, total: Int, done: Int): Boolean {
        if (total <= 0) return false
        if (done < total) return false
        if (state.date != todayIso) return true
        return done > state.celebratedDone
    }

    fun afterCelebration(state: State, todayIso: String, done: Int): State =
        State(date = todayIso, celebratedDone = done)
}
