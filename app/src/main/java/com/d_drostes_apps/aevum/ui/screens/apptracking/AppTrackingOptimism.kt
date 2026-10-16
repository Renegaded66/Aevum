package com.d_drostes_apps.aevum.ui.screens.apptracking

/**
 * M18.147: Reine (JVM-testbare) Regeln für die optimistische App-Aufzeichnung.
 *
 * Der Screen muss sich SOFORT anfühlen: Ein Klick auf eine App verschiebt sie
 * augenblicklich in die andere Spalte, der Datenbank-Schreibvorgang läuft
 * dahinter. Bis er bestätigt ist, gilt die lokale Absicht ([TrackedOverride]);
 * sobald die DB den Stand widerspiegelt (oder der Schreibvorgang scheitert),
 * wird der Override wieder entfernt — die DB bleibt die einzige dauerhafte
 * Wahrheit.
 */
data class TrackedOverride(
    val tracked: Boolean,
    val activityTypeId: String? = null
)

object AppTrackingOptimism {

    /** Effektiv aufgezeichnet? Lokale Absicht schlägt den (noch alten) DB-Stand. */
    fun effectiveTracked(hasDatabaseEntry: Boolean, override: TrackedOverride?): Boolean =
        override?.tracked ?: hasDatabaseEntry

    /**
     * Effektiv zugeordnete Activity. Eine frische Zuordnung ([override] mit
     * ActivityId) gewinnt sofort; sonst zählt der DB-Wert.
     */
    fun effectiveActivityTypeId(databaseTypeId: String?, override: TrackedOverride?): String? {
        if (override != null && override.tracked && override.activityTypeId != null) {
            return override.activityTypeId
        }
        return databaseTypeId
    }

    /**
     * Hat die DB die lokale Absicht übernommen? Dann (und nur dann) darf der
     * Override verschwinden, ohne dass die UI zurückspringt.
     */
    fun settled(
        hasDatabaseEntry: Boolean,
        databaseTypeId: String?,
        override: TrackedOverride
    ): Boolean {
        if (override.tracked != hasDatabaseEntry) return false
        if (override.tracked && override.activityTypeId != null &&
            override.activityTypeId != databaseTypeId
        ) return false
        return true
    }

    /**
     * Nächsten Scan-Chunk an den geladenen Bestand hängen: bereits bekannte
     * Pakete werden ersetzt (frisches Icon/Label), neue angehängt — nie
     * Duplikate, auch wenn ein warmer Cache erneut durch Chunks läuft.
     */
    fun <T> mergeChunk(current: List<T>, chunk: List<T>, key: (T) -> String): List<T> {
        if (chunk.isEmpty()) return current
        val chunkKeys = chunk.mapTo(HashSet()) { key(it) }
        return current.filter { key(it) !in chunkKeys } + chunk
    }
}
