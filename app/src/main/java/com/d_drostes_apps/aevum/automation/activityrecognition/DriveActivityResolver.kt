package com.d_drostes_apps.aevum.automation.activityrecognition

import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * M35: Eine automatisch erkannte Fahrt bekommt eine vom Nutzer wählbare
 * Aktivität.
 *
 * User: „Außerdem ist es gerade so, dass jede Fahrt mit dem Zug auch als
 * Autofahrt aufgezeichnet wird. Ich möchte, dass man der automatischen
 * Aufzeichnung nutzerdefiniert eine Activity zuordnen kann.“
 *
 * Die ERKENNUNG (Activity Recognition + GPS) bleibt unverändert. Nur der
 * ActivityType der gestarteten Session (Name, Icon, Farbe, Statistik) kommt
 * aus den Einstellungen. `driving` bleibt der Default — Bestandsgeräte
 * verhalten sich exakt wie bisher.
 *
 * `radfahren` ist bewusst NIE als Fahrt-Ziel erlaubt: Die Rad-Erkennung
 * benutzt denselben sourceType (ACTIVITY_RECOGNITION_AUTO), und die
 * Unterscheidung Fahrt/Rad hängt an der Activity-ID. Eine Fahrt-Aktivität
 * „radfahren“ würde jeden Stop-/Watchdog-Pfad falsch klassifizieren.
 */
data class DriveActivity(
    val typeId: String,
    val title: String
)

object DriveActivityResolver {

    const val DEFAULT_TYPE_ID = "driving"
    const val BICYCLE_TYPE_ID = "radfahren"
    const val OTHER_TYPE_ID = "other"

    /**
     * Reine Auswahl der Activity-ID: leer/null und `radfahren` fallen auf
     * den Default zurück. So kann eine defekte Einstellung die Fahrt nie
     * lahmlegen.
     */
    fun resolveTypeId(configured: String?): String =
        configured?.trim()
            ?.takeIf { it.isNotEmpty() && it != BICYCLE_TYPE_ID }
            ?: DEFAULT_TYPE_ID

    /**
     * Löst ID + Anzeigename auf. Fehlt der Typ (vom Nutzer gelöscht), wird
     * der Default `driving` verwendet; fehlt auch der, greift der lokale
     * Fallback-Text des Aufrufers.
     */
    suspend fun resolve(
        settingsRepository: AutomationSettingsRepository,
        activityTypeRepository: ActivityTypeRepository,
        fallbackTitle: String
    ): DriveActivity {
        val configured = settingsRepository.getSync()?.driveActivityTypeId
        val resolvedId = resolveTypeId(configured)
        val type = activityTypeRepository.getById(resolvedId).first()
        if (type != null) return DriveActivity(resolvedId, type.name)

        // Konfigurierter Typ gelöscht → Default versuchen. Ist auch der
        // Default gelöscht (System-Typen sind umbenennbar, aber driving ist
        // löschbar), fällt die Fahrt auf den geschützten Fallback „other"
        // zurück — sonst würde der Session-Insert an der FK-Constraint
        // scheitern und die Erkennung wäre dauerhaft stumm.
        val fallback = activityTypeRepository.getById(DEFAULT_TYPE_ID).first()
        if (fallback != null) return DriveActivity(fallback.id, fallback.name)

        val other = activityTypeRepository.getById(OTHER_TYPE_ID).first()
        return DriveActivity(
            typeId = other?.id ?: OTHER_TYPE_ID,
            title = other?.name ?: fallbackTitle
        )
    }
}
