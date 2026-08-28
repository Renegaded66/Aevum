package com.d_drostes_apps.aevum.domain.activity

import android.content.Context
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivitySession

sealed class SessionValidationResult {
    data object Valid : SessionValidationResult()
    data class Invalid(val message: String) : SessionValidationResult()
    data class Warning(val message: String) : SessionValidationResult()
}

object SessionTimeValidator {
    fun validate(
        title: String,
        startAt: Long,
        endAt: Long?,
        existingSessions: List<ActivitySession> = emptyList(),
        editingSessionId: String? = null,
        allowOverlayActivityTypeIds: Set<String> = setOf("digital", "driving", "transport"),
        context: Context? = null
    ): SessionValidationResult {
        if (title.isBlank()) return SessionValidationResult.Invalid(
            context?.getString(R.string.validator_title_required) ?: "Bitte gib deiner Aktivität einen Namen."
        )
        if (endAt == null) return SessionValidationResult.Valid
        // M16.2: endAt <= startAt ist nur dann ein Fehler, wenn der Zeitraum
        // nicht über Mitternacht geht. Da der Editor (setEndMinuteOfDay /
        // updateEnd) übernachtende Zeiträume bereits korrekt als
        // endAt = nextDay|10:00 berechnet, kommt hier nur endAt <= startAt
        // an, wenn der User wirklich eine ungültige Zeit eingibt (z.B.
        // Start=10:00, Ende=08:00 am selben Tag ohne Mitternacht-Logik).
        // Die Validierung bleibt bestehen, aber die Meldung ist klarer.
        if (endAt <= startAt) return SessionValidationResult.Invalid(
            context?.getString(R.string.validator_end_after_start)
                ?: "Die Endzeit muss nach der Startzeit liegen. Wenn die Aktivität über Mitternacht geht, wähle eine Endzeit am nächsten Tag."
        )

        val overlaps = existingSessions.filter { session ->
            session.id != editingSessionId &&
                session.deletedAt == null &&
                rangesOverlap(startAt, endAt, session.startAt, session.endAt)
        }

        if (overlaps.isEmpty()) return SessionValidationResult.Valid

        val onlyOverlay = overlaps.all { session ->
            session.activityTypeId in allowOverlayActivityTypeIds
        }

        return if (onlyOverlay) {
            SessionValidationResult.Warning(
                context?.getString(R.string.validator_overlay_only)
                    ?: "Diese Aktivität überlappt nur mit Overlay-Aktivitäten. Das ist meistens in Ordnung."
            )
        } else {
            SessionValidationResult.Warning(
                if (overlaps.size == 1) {
                    context?.getString(R.string.validator_overlaps_one)
                        ?: "Diese Aktivität überschneidet sich mit 1 bestehender Aktivität. Du kannst sie trotzdem speichern."
                } else {
                    context?.getString(R.string.validator_overlaps_many, overlaps.size)
                        ?: "Diese Aktivität überschneidet sich mit ${overlaps.size} bestehender Aktivitäten. Du kannst sie trotzdem speichern."
                }
            )
        }
    }

    fun rangesOverlap(startA: Long, endA: Long?, startB: Long, endB: Long?): Boolean {
        val aEnd = endA ?: Long.MAX_VALUE
        val bEnd = endB ?: Long.MAX_VALUE
        return startA < bEnd && startB < aEnd
    }
}
