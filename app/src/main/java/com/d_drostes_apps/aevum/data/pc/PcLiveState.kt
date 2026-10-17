package com.d_drostes_apps.aevum.data.pc

import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager

/**
 * PC 1.6.0: Snapshot der aktuellen Handy-Aufzeichnung als Live-DTO.
 *
 * Eine gemeinsame Quelle für den Service-Relay UND die Verifikations-Pings
 * nach jedem Sync (auch „nichts läuft" wird gemeldet) — sonst driften die
 * beiden Meldungs-Bauer auseinander. Devon: „Am besten sollte die desktop
 * app erst erlauben, überhaupt irgendwas aufzuzeichnen […] wenn die desktop
 * app verifiziert hat, das auf dem handy nichts läuft."
 */
object PcLiveState {
    /**
     * null = am Handy läuft gerade nichts — genau diese Bestätigung braucht
     * der PC (ab 1.6.0) für seine Start-Erlaubnis.
     */
    fun snapshot(manager: LiveActivityManager): PcTrackerClient.LiveSessionSync? {
        val session = manager.liveSession.value?.takeIf { it.isLive } ?: return null
        val type = session.activityTypeId?.let { manager.cachedActivityType(it) }
        return PcTrackerClient.LiveSessionSync(
            sessionId = session.id,
            activityKey = session.activityTypeId.orEmpty(),
            activityName = type?.name ?: session.title,
            icon = type?.icon.orEmpty(),
            categoryKey = type?.defaultCategoryId.orEmpty(),
            startAtMs = session.startAt,
            isPaused = session.isPaused,
            blockEndAtMs = session.endAt ?: 0L
        )
    }
}
