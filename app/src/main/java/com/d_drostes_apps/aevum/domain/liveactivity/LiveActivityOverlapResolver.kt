package com.d_drostes_apps.aevum.domain.liveactivity

import com.d_drostes_apps.aevum.data.model.ActivitySession
import java.util.UUID

/**
 * M18.71: Überlappende Aktivitäten — nur die Überlappungszeit überschreiben.
 *
 * Beim Start einer neuen (Live-)Aktivität, die sich mit bestehenden Sessions
 * zeitlich überlappt, wird die BESTEHENDE Session nur im überlappenden
 * Zeitraum beschnitten — nie gelöscht. Regeln (User-Spec):
 *
 *  (a) Neue startet mitten in bestehender → bestehende endet exakt am Start
 *      der neuen (end = neuer start).
 *  (b) Neue liegt komplett in bestehender → bestehende wird in zwei Teile
 *      gesplittet (vor + nach der neuen).
 *  (c) Kein Löschen ganzer Sessions.
 *
 * Sonderfall Live-Pfad: Die bestehende Session läuft gerade (endAt == null).
 * Ihr Ende ist [now] (der Zeitpunkt des Starts der neuen Aufzeichnung) —
 * die Anpassung wirkt also nur auf den BEREITS VERGANGENEN Teil
 * [existing.startAt, now]. Die Zukunft der alten Session gehört der neuen.
 *
 * Reine Logik ohne Android-Abhängigkeiten → JVM-testbar
 * (Muster: ScreenRecordingEngine M18.70).
 */
object LiveActivityOverlapResolver {

    /**
     * Ergebnis der Überlappungs-Analyse. [overwritten] wird per Repository
     * aktualisiert, [inserted] neu eingefügt. Ist [overwriteExistingStatus]
     * gesetzt, wird die bestehende Session AUCH in der Status-Spalte auf
     * diesen Wert geschrieben (z. B. FINISHED für eine laufende Session).
     */
    data class Resolution(
        val overwritten: List<ActivitySession>,
        val inserted: List<ActivitySession>,
        val overwriteExistingStatus: String? = null
    )

    /**
     * @param existing   die bestehende Session (live, endAt == null ODER abgeschlossen)
     * @param newStart   Start der neuen Aktivität
     * @param newEnd     Ende der neuen Aktivität; null = läuft (Live-Start,
     *                   dann gilt Ende = now)
     * @param now        aktuelle Zeit (Referenz für laufende Sessions)
     */
    fun resolve(
        existing: ActivitySession,
        newStart: Long,
        newEnd: Long?,
        now: Long
    ): Resolution {
        if (existing.deletedAt != null) return Resolution(emptyList(), emptyList())

        val existingEnd = existing.endAt ?: now
        val newEndEff = newEnd ?: now
        // Keine Überlappung → nichts tun. Grenzfall newStart == existingEnd
        // (nahtloser Wechsel) ist KEINE Überlappung.
        if (newStart >= existingEnd || newEndEff <= existing.startAt) {
            return Resolution(emptyList(), emptyList())
        }

        val startsBefore = existing.startAt < newStart
        val endsAfter = existingEnd > newEndEff
        val nowMs = System.currentTimeMillis()
        val base = existing.copy(updatedAt = nowMs, revision = existing.revision + 1)

        return when {
            // (b) Neue liegt KOMPLETT in der bestehenden → Split in zwei Teile:
            //     [existingStart, newStart] + [newEnd, existingEnd].
            startsBefore && endsAfter -> {
                Resolution(
                    overwritten = listOf(
                        base.copy(endAt = newStart)
                    ),
                    inserted = listOf(
                        base.copy(
                            id = UUID.randomUUID().toString(),
                            startAt = newEndEff,
                            endAt = existingEnd.takeIf { existing.endAt != null },
                            // Der zweite Teil ist eine ABGESCHLOSSENE Session —
                            // nie den Live-Status (RUNNING/PAUSED) der
                            // Originalsession erben, sonst gäbe es zwei
                            // Live-Sessions.
                            sessionStatus = "FINISHED",
                            currentPauseStartedAt = null,
                            revision = 1,
                            createdAt = nowMs
                        )
                    )
                )
            }
            // (a) Neue startet mitten in bestehender (bestehende endet nach
            //     dem Start der neuen) → bestehende endet exakt am neuen Start.
            startsBefore -> Resolution(
                overwritten = listOf(
                    base.copy(endAt = newStart)
                ),
                inserted = emptyList()
            )
            // Bestehende beginnt in der neuen, endet aber danach
            // → nur der Rest nach der neuen bleibt: startAt = newEnd.
            endsAfter -> Resolution(
                overwritten = listOf(
                    base.copy(startAt = newEndEff)
                ),
                inserted = emptyList()
            )
            // Bestehende liegt komplett IN der neuen (und ist NICHT die
            // laufende Session selbst) → Überlappung ist 100 % → nicht
            // löschen (Regel c), sondern auf 0-Länge kürzen: startAt = endAt
            // = newEnd. Damit bleibt der Datensatz erhalten, ist aber im
            // neuen Zeitraum unsichtbar (Regel c: kein Löschen ganzer
            // Sessions).
            else -> Resolution(
                overwritten = listOf(
                    base.copy(startAt = newEndEff, endAt = newEndEff)
                ),
                inserted = emptyList()
            )
        }
    }
}
