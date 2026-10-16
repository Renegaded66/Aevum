package com.d_drostes_apps.aevum.automation.pctracker

/**
 * M18.147: Drossel für den stillen PC-Sync (pure Logik, JVM-testbar).
 *
 * Der stille Sync darf nie zum Stromfresser werden: Er versucht es höchstens
 * einmal pro [MIN_GAP_MS] — egal wie oft die App geöffnet oder im Hintergrund
 * geweckt wird — und gar nicht, wenn gerade frisch synchronisiert wurde,
 * keine Kopplung besteht oder der Schalter aus ist.
 */
object PcSyncGate {

    /** Mindestabstand zwischen zwei stillen Sync-Versuchen (Erfolg wie Fehlschlag). */
    const val MIN_GAP_MS = 10 * 60 * 1000L

    /**
     * @param nowMs            jetzt
     * @param lastAttemptMs    letzter stiller Sync-Versuch (0 = nie)
     * @param lastSyncMs       letzter ERFOLGREICHER Sync (0 = nie)
     * @param coupled          eine PC-Kopplung ist gespeichert
     * @param autoSyncEnabled  der Hintergrund-Sync-Schalter ist an
     */
    fun shouldAttemptNow(
        nowMs: Long,
        lastAttemptMs: Long,
        lastSyncMs: Long,
        coupled: Boolean,
        autoSyncEnabled: Boolean
    ): Boolean {
        if (!coupled || !autoSyncEnabled) return false
        if (nowMs - lastAttemptMs < MIN_GAP_MS) return false
        if (nowMs - lastSyncMs < MIN_GAP_MS) return false
        return true
    }
}
