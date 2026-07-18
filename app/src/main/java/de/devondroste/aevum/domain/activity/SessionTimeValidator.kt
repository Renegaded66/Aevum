package de.devondroste.aevum.domain.activity

import de.devondroste.aevum.data.model.ActivitySession

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
        allowOverlayActivityTypeIds: Set<String> = setOf("digital", "driving", "transport")
    ): SessionValidationResult {
        if (title.isBlank()) return SessionValidationResult.Invalid("Bitte gib deiner Aktivität einen Namen.")
        if (endAt == null) return SessionValidationResult.Valid
        if (endAt <= startAt) return SessionValidationResult.Invalid("Die Endzeit muss nach der Startzeit liegen.")

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
            SessionValidationResult.Warning("Diese Aktivität überlappt nur mit Overlay-Aktivitäten. Das ist meistens in Ordnung.")
        } else {
            SessionValidationResult.Warning("Diese Aktivität überschneidet sich mit ${overlaps.size} bestehender Aktivität${if (overlaps.size == 1) "" else "en"}. Du kannst sie trotzdem speichern.")
        }
    }

    fun rangesOverlap(startA: Long, endA: Long?, startB: Long, endB: Long?): Boolean {
        val aEnd = endA ?: Long.MAX_VALUE
        val bEnd = endB ?: Long.MAX_VALUE
        return startA < bEnd && startB < aEnd
    }
}
