package com.d_drostes_apps.aevum.ui.disclosure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * ════════════════════════════════════════════════════════════════════════
 * PERMISSION DISCLOSURE — Erklärung VOR jeder Berechtigungs-Anfrage
 * ════════════════════════════════════════════════════════════════════════
 *
 * WARUM DIESES MODUL EXISTIERT
 *
 * Die Play-Prüfung hat das Update 1.0.19 abgelehnt, weil der
 * Standort-Permission-Request ohne vorherige Offenlegung IN der App lief
 * („Anfragen zur Nutzereinwilligung und zu Laufzeitberechtigungen in der App
 * geht keine unmittelbare Offenlegung in der App voraus"). Derselbe Einwand
 * trifft jede andere Berechtigung: Wer auf „Aktivitätserkennung" tippt und
 * direkt den Systemdialog bekommt, hat in der App keine Erklärung gesehen.
 *
 * Dieses Modul schaltet deshalb vor JEDE Berechtigungs-Anfrage in der App
 * einen Erklärungsdialog — mit Zweck, konkreten Funktionen und der
 * Datenverarbeitung. Der Systemdialog kommt erst danach.
 *
 * ── WARUM HIER NICHTS PERSISTIERT WIRD ────────────────────────────────
 *
 * [LocationDisclosure] merkt sich die Zustimmung, weil Google für den
 * Hintergrund-Standort eine dokumentierte Einwilligung verlangt und die
 * Offenlegung nicht bei jedem Start erneut nerven soll.
 *
 * Hier ist das anders — und zwar bewusst:
 *
 *   1. Maßgeblich ist der AKTUELLE Berechtigungsstatus, nicht eine
 *      gespeicherte Antwort. Ist die Berechtigung erteilt, gibt es nichts
 *      zu erklären (der Status steht in der UI auf „erteilt").
 *   2. Wurde die Berechtigung erteilt UND danach wieder entzogen, ist der
 *      Status wieder „nicht erteilt" — der Dialog erscheint beim nächsten
 *      Tippen erneut. Genau so gewünscht.
 *   3. Es gibt keinen Zustand, der mit dem System auseinanderlaufen kann.
 *      Eine gespeicherte „schon erklärt"-Marke wäre eine Fehlerquelle:
 *      Nach einem Widerruf in den Systemeinstellungen würde sie den Dialog
 *      unterdrücken, obwohl der Nutzer neu zustimmen muss.
 *
 * Die einfache Regel lautet damit: **nicht erteilt → erst erklären.**
 */

/**
 * Die Berechtigungen, für die in der App ein Erklärungsdialog vorgeschaltet
 * wird.
 *
 * [permission] ist `null` bei Berechtigungen, die NICHT über den normalen
 * Runtime-Dialog laufen, sondern über eine spezielle Systemeinstellungsseite
 * (Sonderzugriff). Solche Fälle leitet der Dialog direkt dorthin.
 *
 * [isSettingsBased] unterscheidet beide Fälle in der UI.
 */
enum class PermissionDisclosureKind(
    val permission: String?,
    val isSettingsBased: Boolean
) {
    /** Bewegungserkennung: Fahrten, Gehen, Radfahren, Schlaf-Fusion. */
    ACTIVITY_RECOGNITION(Manifest.permission.ACTIVITY_RECOGNITION, false),

    /** Laufende Aktivitäten und automatische Wechsel sichtbar machen. */
    NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        },
        false
    ),

    /** Digital Balance (Bildschirmzeit, App-Limits) + Schlaf-Heuristik. */
    USAGE_ACCESS(null, true),

    /** Kalender-Regeln: Termine starten Aktivitäten. */
    CALENDAR(Manifest.permission.READ_CALENDAR, false)
}

/**
 * Die Pflichtinhalte eines Erklärungsdialogs als reine Daten — Grundlage für
 * die UI UND für die Tests. `pointResIds` muss mehrere Einträge haben: ein
 * Dialog, der den Zweck nicht konkret benennt, erfüllt seinen Zweck nicht.
 */
data class PermissionDisclosureText(
    val titleRes: Int,
    val leadRes: Int,
    val pointsTitleRes: Int,
    val pointResIds: List<Int>,
    val noteTitleRes: Int,
    val noteRes: Int
) {
    val pointCount: Int get() = pointResIds.size
}

/** Ordnet jeder Berechtigung ihren Text zu. Einzige Quelle der Dialoginhalte. */
fun disclosureTextFor(kind: PermissionDisclosureKind): PermissionDisclosureText =
    when (kind) {
        PermissionDisclosureKind.ACTIVITY_RECOGNITION -> PermissionDisclosureText(
            titleRes = R.string.perm_activity_title,
            leadRes = R.string.perm_activity_lead,
            pointsTitleRes = R.string.perm_disclosure_points_title,
            pointResIds = listOf(
                R.string.perm_activity_point_1,
                R.string.perm_activity_point_2,
                R.string.perm_activity_point_3
            ),
            noteTitleRes = R.string.perm_disclosure_note_title,
            noteRes = R.string.perm_activity_note
        )

        PermissionDisclosureKind.NOTIFICATIONS -> PermissionDisclosureText(
            titleRes = R.string.perm_notifications_title,
            leadRes = R.string.perm_notifications_lead,
            pointsTitleRes = R.string.perm_disclosure_points_title,
            pointResIds = listOf(
                R.string.perm_notifications_point_1,
                R.string.perm_notifications_point_2,
                R.string.perm_notifications_point_3
            ),
            noteTitleRes = R.string.perm_disclosure_note_title,
            noteRes = R.string.perm_notifications_note
        )

        PermissionDisclosureKind.USAGE_ACCESS -> PermissionDisclosureText(
            titleRes = R.string.perm_usage_title,
            leadRes = R.string.perm_usage_lead,
            pointsTitleRes = R.string.perm_disclosure_points_title,
            pointResIds = listOf(
                R.string.perm_usage_point_1,
                R.string.perm_usage_point_2,
                R.string.perm_usage_point_3
            ),
            noteTitleRes = R.string.perm_disclosure_note_title,
            noteRes = R.string.perm_usage_note
        )

        PermissionDisclosureKind.CALENDAR -> PermissionDisclosureText(
            titleRes = R.string.perm_calendar_title,
            leadRes = R.string.perm_calendar_lead,
            pointsTitleRes = R.string.perm_disclosure_points_title,
            pointResIds = listOf(
                R.string.perm_calendar_point_1,
                R.string.perm_calendar_point_2,
                R.string.perm_calendar_point_3
            ),
            noteTitleRes = R.string.perm_disclosure_note_title,
            noteRes = R.string.perm_calendar_note
        )
    }

/**
 * Ist die Berechtigung aktuell erteilt? `null` bei [PermissionDisclosureKind.permission]
 * bedeutet „läuft über eine Systemeinstellungsseite" — das ist NICHT
 * gleichbedeutend mit „nicht erteilt"; die Prüfung übernimmt dort der
 * Aufrufer über den jeweiligen Fach-Status (z. B. UsageStatsManager).
 */
fun isPermissionGranted(context: Context, kind: PermissionDisclosureKind): Boolean? {
    val permission = kind.permission ?: return null
    return ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Wird Android den Systemdialog überhaupt noch zeigen?
 *
 * ── WARUM HIER EIN GEMERKTER ABLEHNUNGS-STATUS NÖTIG IST ──────────────
 *
 * `shouldShowRequestPermissionRationale` (SSRPR) allein reicht NICHT:
 *
 * | Zustand                    | SSRPR | granted |
 * |----------------------------|-------|---------|
 * | noch nie gefragt           | false | false   |
 * | einmal abgelehnt           | true  | false   |
 * | dauerhaft abgelehnt        | false | false   |
 * | erteilt                    | false | false->true |
 *
 * „noch nie gefragt" und „dauerhaft abgelehnt" haben **denselben** SSRPR-Wert.
 * Wer nur SSRPR auswertet, schickt einen Erstnutzer fälschlich in die
 * Systemeinstellungen, statt ihm den normalen Dialog zu zeigen.
 *
 * Deshalb wird zusätzlich gemerkt, ob für diese Berechtigung schon einmal ein
 * Systemdialog lief und ABGELEHNT wurde ([markDenied]). Erst dann ist
 * `SSRPR == false` ein sicheres Signal für „dauerhaft abgelehnt".
 *
 * Wichtig: Das ist **keine Einwilligungs-Marke**, sondern eine technische
 * Tatsache über den Dialogverlauf. Sie berührt die gewünschte Regel nicht —
 * die Erklärung erscheint immer, wenn die Berechtigung nicht erteilt ist.
 * Ein Grant-und-danach-Widerruf setzt [clearDenied] beim Grant zurück; beim
 * nächsten Tippen läuft wieder der normale Dialog.
 */
fun willShowSystemDialog(context: Context, kind: PermissionDisclosureKind): Boolean {
    val permission = kind.permission ?: return false
    if (isPermissionGranted(context, kind) == true) return false
    // Einmal abgelehnt → Android zeigt den Dialog erneut.
    val activity = context as? android.app.Activity ?: return !PermissionDisclosureMemory.wasDenied(context, kind)
    if (androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
        return true
    }
    // SSRPR == false: entweder noch nie gefragt (Dialog erscheint) oder
    // dauerhaft abgelehnt (Dialog erscheint NICHT). Der gemerkte Verlauf
    // entscheidet.
    return !PermissionDisclosureMemory.wasDenied(context, kind)
}

/**
 * Technischer Verlauf der Berechtigungs-Dialoge — NICHT die Einwilligung.
 *
 * Getrennt von [PermissionDisclosureMemory] mit klarem Namen, damit niemand
 * diesen Zustand für eine „schon erklärt"-Marke hält: er steuert
 * ausschließlich, ob beim Bestätigen der Systemdialog aufgerufen werden kann
 * oder ob in die App-Einstellungen geführt werden muss.
 */
object PermissionDisclosureMemory {

    private const val PREFS = "aevum_permission_dialog_history"

    private fun key(kind: PermissionDisclosureKind) = "denied_${kind.name}"

    /** true, wenn für diese Berechtigung schon einmal ein Dialog abgelehnt wurde. */
    fun wasDenied(context: Context, kind: PermissionDisclosureKind): Boolean =
        prefs(context).getBoolean(key(kind), false)

    /** Nach einem abgelehnten Systemdialog aufrufen. */
    fun markDenied(context: Context, kind: PermissionDisclosureKind) {
        prefs(context).edit().putBoolean(key(kind), true).apply()
    }

    /**
     * Nach einem Grant aufrufen: der Verlauf beginnt von vorn. Ohne das würde
     * ein späterer Widerruf fälschlich als „dauerhaft abgelehnt" gelten und
     * der Nutzer landete in den Einstellungen statt im normalen Dialog.
     */
    fun clearDenied(context: Context, kind: PermissionDisclosureKind) {
        prefs(context).edit().remove(key(kind)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Öffnet die App-Detailseite (Berechtigungen des Nutzers). */
fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        android.util.Log.e("PermissionDisclosure", "App-Einstellungen nicht öffenbar", e)
    }
}

/**
 * Der Erklärungsdialog.
 *
 * [requiresSettings] ist `true`, wenn die Berechtigung nicht mehr über den
 * Systemdialog erteilt werden kann (Sonderzugriff wie Nutzungszugriff, oder
 * dauerhaft abgelehnt). Dann heißt der Bestätigungsbutton
 * „Einstellungen öffnen"/„Open settings" statt „Erlauben"/„Allow" — der
 * Nutzer wird nicht in einen Dialog geschickt, der nicht mehr erscheint.
 *
 * Wegtippen oder Zurück ruft [onDecline] — kein Request, keine Aktion.
 */
@Composable
fun PermissionDisclosureDialog(
    kind: PermissionDisclosureKind,
    requiresSettings: Boolean,
    onAllow: () -> Unit,
    onDecline: () -> Unit
) {
    val text = remember(kind) { disclosureTextFor(kind) }

    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Text(
                stringResource(text.titleRes),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // Was die Berechtigung ermöglicht und warum sie gebraucht wird.
                Text(
                    stringResource(text.leadRes),
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    stringResource(text.pointsTitleRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                text.pointResIds.forEach { res ->
                    Text("•  " + stringResource(res), lineHeight = 20.sp)
                }

                Spacer(Modifier.height(AevumSpacing.xs))

                // Datenverarbeitung: lokal, widerrufbar.
                Text(
                    stringResource(text.noteTitleRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(text.noteRes), lineHeight = 20.sp)
            }
        },
        confirmButton = {
            Button(onClick = onAllow) {
                Text(
                    stringResource(
                        if (requiresSettings) {
                            R.string.perm_disclosure_open_settings
                        } else {
                            R.string.perm_disclosure_allow
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.perm_disclosure_decline))
            }
        }
    )
}

/**
 * Zustandshalter für die Einbaustellen.
 *
 * Er merkt sich, WELCHE Berechtigung gerade erklärt wird und ob dafür der
 * Systemdialog noch zur Verfügung steht. Die Entscheidung „erklären oder
 * direkt anfragen" liegt an einer Stelle, damit kein Aufrufer sie vergessen
 * kann.
 *
 * Verwendung:
 * ```
 * val gate = rememberPermissionDisclosureGate()
 * ...
 * onClick = { gate.request(kind) { action -> runPermissionRequest(action) } }
 * ...
 * gate.pending?.let { pending ->
 *     PermissionDisclosureDialog(
 *         kind = pending.kind,
 *         requiresSettings = pending.requiresSettings,
 *         onAllow = { gate.consent { runPermissionRequest(it) } },
 *         onDecline = { gate.dismiss() }
 *     )
 * }
 * ```
 */
class PermissionDisclosureGateState(private val context: Context) {

    /** Eine wartende Erklärungs-Anfrage. */
    data class Pending(val kind: PermissionDisclosureKind, val requiresSettings: Boolean)

    var pending: Pending? by mutableStateOf(null)
        private set

    /**
     * Einstiegspunkt für jede Berechtigungs-Aktion.
     *
     * [alreadyGranted] muss der AUFRUFER liefern, wenn die Berechtigung nicht
     * über `checkSelfPermission` prüfbar ist (Sonderzugriff wie
     * Nutzungszugriff) — dort kennt nur der Fach-Code den echten Status.
     *
     * Ist die Berechtigung bereits erteilt, läuft [onReady] sofort (es gibt
     * nichts zu erklären). Sonst wird der Dialog gezeigt und [onReady] läuft
     * erst nach ausdrücklicher Zustimmung.
     */
    fun request(
        kind: PermissionDisclosureKind,
        alreadyGranted: Boolean = isPermissionGranted(context, kind) == true,
        onReady: (Pending) -> Unit
    ) {
        if (alreadyGranted) {
            onReady(Pending(kind, requiresSettings = false))
        } else {
            // Läuft die Berechtigung über eine Systemeinstellungsseite
            // (Sonderzugriff) oder zeigt Android keinen Dialog mehr, führt
            // der Bestätigungsbutton in die Einstellungen.
            val needsSettings = kind.isSettingsBased || !willShowSystemDialog(context, kind)
            pending = Pending(kind, requiresSettings = needsSettings)
        }
    }

    /**
     * Bestätigung: Dialog schließen und die gemerkte Aktion ausführen.
     * [onReady] läuft immer NACH dem Schließen — die Reihenfolge kann in der
     * UI nicht verdreht werden.
     */
    fun consent(onReady: (Pending) -> Unit) {
        val current = pending ?: return
        pending = null
        onReady(current)
    }

    /** Wegtippen/Zurück/„Nicht jetzt" — keine Aktion. */
    fun dismiss() {
        pending = null
    }
}

/** Compose-Helfer für [PermissionDisclosureGateState]. */
@Composable
fun rememberPermissionDisclosureGate(): PermissionDisclosureGateState {
    val context = LocalContext.current
    return remember { PermissionDisclosureGateState(context) }
}
