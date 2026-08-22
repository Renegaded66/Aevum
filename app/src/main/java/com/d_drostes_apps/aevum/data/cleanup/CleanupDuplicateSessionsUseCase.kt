package com.d_drostes_apps.aevum.data.cleanup

import android.util.Log
import com.d_drostes_apps.aevum.data.db.ActivitySessionDao
import javax.inject.Inject

/**
 * AEVUM-1: Einmaliger Daten-Aufräumlauf beim App-Start.
 *
 * Lädt alle nicht-gelöschten Sessions, berechnet die Duplikate über
 * [SessionDedupCleaner] (gleiche externalId ODER gleicher Typ + zeitliche
 * Überlappung; die NEUESTE Session jeder Duplikat-Gruppe bleibt) und löscht
 * die älteren Duplikate hart. Verknüpfte Zeilen (activity_session_tag,
 * session_evidence, activity_session_change) räumen sich per ON DELETE
 * CASCADE selbst ab.
 *
 * Bewusst KEIN DB-Migrations-Bump: Es ändert sich kein Schema — die Migration
 * 36→37 gehört AEVUM-3 (manual_quality_override). Der Cleanup läuft idempotent
 * bei jedem App-Start; nach dem ersten Lauf gibt es keine Duplikate mehr und
 * der Lauf ist ein No-Op (ein Lookup + 0 Deletes).
 */
class CleanupDuplicateSessionsUseCase @Inject constructor(
    private val dao: ActivitySessionDao
) {
    suspend operator fun invoke(): Int {
        val sessions = try {
            dao.getAllNonDeletedOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Dedup-Cleanup: Session-Lookup fehlgeschlagen — übersprungen", e)
            return 0
        }
        if (sessions.size < 2) return 0

        val duplicates = SessionDedupCleaner.duplicatesToDelete(sessions)
        if (duplicates.isEmpty()) return 0

        val ids = duplicates.map { it.id }
        // Chunking: Room expandiert IN-Listen; sehr große Bestände (>999
        // Duplikate) in Häppchen löschen, damit kein SQL-Limit greift.
        ids.chunked(500).forEach { chunk ->
            try {
                dao.hardDeleteByIds(chunk)
            } catch (e: Exception) {
                Log.e(TAG, "Dedup-Cleanup: Löschen von ${chunk.size} Sessions fehlgeschlagen", e)
            }
        }
        Log.i(TAG, "Dedup-Cleanup: ${duplicates.size} Duplikate gelöscht " +
            "(z.B. ${duplicates.take(3).joinToString { it.title }})")
        return duplicates.size
    }

    private companion object {
        const val TAG = "SessionDedupCleanup"
    }
}
