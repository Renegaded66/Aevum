package com.d_drostes_apps.aevum.automation.pctracker

/**
 * M22: Entscheidung, was mit einem Steuerbefehl des PCs passiert — pure
 * Logik, JVM-getestet.
 *
 * Devon: „Der desktop soll also sozusagen nur ans handy senden ‚Du musst
 * jetzt activity x starten/stoppen'. Trotzdem soll es natürlich auch
 * weiterhin auf dem desktop visualisiert werden."
 *
 * Der PC schickt nur den Schlüssel (die ActivityType-ID der App) — die
 * Entscheidung, ob überhaupt gestartet wird, gehört aufs Handy:
 *  - unbekannter Schlüssel (Aktivität gelöscht) → nichts tun, ehrlich melden,
 *  - dieselbe Aktivität läuft schon → nicht neu starten (kein Timer-Sprung),
 *  - sonst starten (eine laufende andere Session wird wie bei jedem
 *    manuellen Start überlappungsfrei abgelöst).
 */
object PcRemoteCommandPolicy {

    /** Was das Handy tun soll. */
    sealed interface Decision {
        data object Ignore : Decision
        data class UnknownActivity(val activityKey: String) : Decision
        data object AlreadyRunning : Decision
        data class Start(val activityTypeId: String) : Decision
    }

    /** Der vom PC ausgestellte Befehl, losgelöst vom HTTP-Client. */
    data class Command(val command: String, val activityKey: String?)

    /**
     * @param typeExists Existiert die Aktivität (noch) in der App?
     * @param liveActivityTypeId Aktivität der laufenden Session (null = keine).
     * @param liveIsRunning Läuft die Session wirklich (nicht pausiert)?
     */
    fun decide(
        command: Command?,
        typeExists: Boolean,
        liveActivityTypeId: String?,
        liveIsRunning: Boolean
    ): Decision {
        if (command?.command != "start") return Decision.Ignore
        val key = command.activityKey?.takeIf { it.isNotBlank() } ?: return Decision.Ignore
        if (!typeExists) return Decision.UnknownActivity(key)
        if (liveIsRunning && liveActivityTypeId == key) return Decision.AlreadyRunning
        return Decision.Start(key)
    }
}
