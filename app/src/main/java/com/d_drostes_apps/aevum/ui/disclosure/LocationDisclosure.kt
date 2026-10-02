package com.d_drostes_apps.aevum.ui.disclosure

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * ════════════════════════════════════════════════════════════════════════
 * PROMINENT IN-APP DISCLOSURE — Standort im Hintergrund
 * ════════════════════════════════════════════════════════════════════════
 *
 * Google-Play-Auflage (Richtlinie „Nutzerdaten – Pflicht zur deutlichen
 * Offenlegung und Einwilligung"), ausgelöst durch die Ablehnung des
 * Updates 1.0.19 (versionCode 20):
 *
 *   „Die deutliche Offenlegung in der App enthält keine Informationen
 *    darüber, wie abgerufene oder erhobene Standortdaten verwendet werden.
 *    Anfragen zur Nutzereinwilligung und zu Laufzeitberechtigungen in der
 *    App geht keine unmittelbare Offenlegung in der App voraus."
 *
 * Daraus folgen die vier harten Regeln, die dieses Modul umsetzt:
 *
 *  1. Der Dialog erscheint VOR jedem Request einer Standort-Laufzeit-
 *     berechtigung (`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`) und vor
 *     dem Weiterleiten in die App-Details zum Setzen von „Immer erlauben"
 *     (Hintergrund-Standort auf Android 10+).
 *  2. Er enthält das Wort „Standort" und benennt ausdrücklich den Zugriff
 *     im Hintergrund („auch wenn die App geschlossen ist oder nicht
 *     verwendet wird").
 *  3. Er listet ALLE Funktionen auf, die im Hintergrund auf den Standort
 *     zugreifen (nicht nur die, für die der Nutzer gerade tippt).
 *  4. Die Einwilligung ist eine ausdrückliche Handlung (Button). Wegtippen
 *     oder Zurück gilt NICHT als Zustimmung: `onDismissRequest` schließt den
 *     Dialog ohne Einwilligung, der Permission-Request unterbleibt.
 *
 * ── Warum die Logik getrennt von der UI liegt ──────────────────────────
 * [DisclosureGate] und [LocationDisclosureText] sind bewusst frei von
 * Android- und Compose-Abhängigkeiten. So lassen sie sich als reine
 * JVM-Tests prüfen (siehe LocationDisclosureTest) — insbesondere die
 * Pflicht-Inhalte und die Reihenfolge Zustimmung → Request.
 */

/**
 * Persistenz der Zustimmung. Bewusst simpel (SharedPreferences) und frei
 * von DI, damit auch der Nicht-Hilt-Kontext (z. B. Application) sie nutzen
 * kann.
 */
object LocationDisclosure {

    const val PREFS_NAME = "aevum_location_disclosure"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

    /**
     * Bei inhaltlichen Änderungen der Offenlegung erhöhen: die Offenlegung
     * erscheint dann erneut, weil die alte Zustimmung sich auf einen anderen
     * Text bezog.
     */
    const val DISCLOSURE_VERSION = 1

    /** Wurde die Offenlegung in der aktuellen Fassung bestätigt? */
    fun isAccepted(context: Context): Boolean =
        prefs(context).getInt(KEY_ACCEPTED_VERSION, 0) >= DISCLOSURE_VERSION

    /** Zustimmung festhalten — wird NUR aus dem Bestätigungs-Button gerufen. */
    fun markAccepted(context: Context) {
        prefs(context).edit()
            .putInt(KEY_ACCEPTED_VERSION, DISCLOSURE_VERSION)
            .putBoolean(KEY_ONBOARDING_SHOWN, true)
            .apply()
    }

    /**
     * Wurde die Offenlegung beim App-Start schon einmal gezeigt?
     *
     * Die Richtlinie verlangt, dass die Offenlegung „bei der normalen Nutzung
     * der App" erscheint und nicht erst nach Navigation in ein Menü. Deshalb
     * erscheint sie einmalig beim ersten Start (Dashboard). Wer sie dort
     * wegtippt, wird nicht bei jedem Start erneut gefragt — sie kommt
     * spätestens wieder, wenn er eine Standort-Funktion auslöst (dort ist sie
     * ohnehin Pflicht) oder über den Datenschutz-Screen.
     */
    fun wasOnboardingShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_SHOWN, false)

    /** Vermerkt, dass die Start-Offenlegung gezeigt wurde (auch bei Abbruch). */
    fun markOnboardingShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    /** Zurücksetzen (z. B. für Tests oder „Einstellungen zurücksetzen"). */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Reine Entscheidungslogik des Gates — ohne Compose, ohne Android.
 *
 * [Action] ist das, was der Nutzer auslösen wollte; [needsDisclosure]
 * entscheidet, ob zuerst die Offenlegung gezeigt werden muss.
 */
class DisclosureGate(
    private val disclosureAccepted: Boolean
) {
    enum class Action {
        /** Vordergrund-Standort anfragen (Runtime-Permission). */
        REQUEST_FOREGROUND_LOCATION,

        /** In die App-Details führen, um „Immer erlauben" zu setzen. */
        REQUEST_BACKGROUND_VIA_SETTINGS
    }

    /**
     * true  = Offenlegung zuerst zeigen, das eigentliche Ziel merken.
     * false = Offenlegung liegt bereits bestätigt vor, direkt ausführen.
     */
    fun needsDisclosure(action: Action): Boolean = !disclosureAccepted

    /**
     * Nach Klick auf „Einverstanden": Zustimmung persistieren (Aufrufer)
     * und DANN die gemerkte Aktion ausführen. Diese Methode existiert, damit
     * die Reihenfolge in der UI nicht verdreht werden kann — sie liefert
     * immer die auszuführende Aktion zurück.
     */
    fun actionAfterConsent(action: Action): Action = action
}

/**
 * Die Pflichtinhalte der Offenlegung als reine Daten — Grundlage für die UI
 * UND für die Tests. `featureCount` muss > 1 sein: die Richtlinie verlangt
 * eine Liste ALLER Hintergrund-Standort-Funktionen, nicht nur einer.
 */
data class LocationDisclosureText(
    val titleRes: Int,
    val leadRes: Int,
    val featuresTitleRes: Int,
    val featureResIds: List<Int>,
    val whyTitleRes: Int,
    val whyRes: Int,
    val dataTitleRes: Int,
    val dataRes: Int,
    val privacyLinkRes: Int,
    val acceptRes: Int,
    val declineRes: Int
) {
    val featureCount: Int get() = featureResIds.size

    companion object {
        /**
         * Einzige Quelle der Offenlegung. Wird der Text inhaltlich
         * geändert, müssen die Strings in
         * `res/values/strings_disclosure.xml` (EN, Fallback) und
         * `res/values-de/strings_disclosure.xml` (DE) mitgezogen werden.
         */
        fun standard(): LocationDisclosureText = LocationDisclosureText(
            titleRes = R.string.disclosure_location_title,
            leadRes = R.string.disclosure_location_lead,
            featuresTitleRes = R.string.disclosure_location_features_title,
            featureResIds = listOf(
                R.string.disclosure_location_feature_geofence,
                R.string.disclosure_location_feature_drive,
                R.string.disclosure_location_feature_movement,
                R.string.disclosure_location_feature_places
            ),
            whyTitleRes = R.string.disclosure_location_why_title,
            whyRes = R.string.disclosure_location_why,
            dataTitleRes = R.string.disclosure_location_data_title,
            dataRes = R.string.disclosure_location_data,
            privacyLinkRes = R.string.disclosure_location_privacy_link,
            acceptRes = R.string.disclosure_location_accept,
            declineRes = R.string.disclosure_location_decline
        )
    }
}

/**
 * Der Disclosure-Dialog.
 *
 * Ein normaler Klick außerhalb oder der Zurück-Knopf ruft [onDecline] —
 * NICHT [onAccept]. Damit kann die Offenlegung nicht versehentlich als
 * erteilt gelten (Richtlinie: „Must not interpret navigation away from the
 * disclosure as consent").
 */
@Composable
fun LocationDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit
) {
    val text = remember { LocationDisclosureText.standard() }

    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Text(
                stringResource(text.titleRes),
                fontSize = 20.sp,
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
                // Satz nach Google-Vorgabe: "…erhebt Standortdaten, um
                // [Funktion] … zu ermöglichen, auch wenn die App geschlossen
                // ist oder nicht verwendet wird."
                Text(
                    stringResource(text.leadRes),
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    stringResource(text.featuresTitleRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                text.featureResIds.forEach { res ->
                    Text("•  " + stringResource(res), lineHeight = 20.sp)
                }

                Spacer(Modifier.height(AevumSpacing.xs))
                Text(
                    stringResource(text.whyTitleRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(text.whyRes), lineHeight = 20.sp)

                Spacer(Modifier.height(AevumSpacing.xs))
                Text(
                    stringResource(text.dataTitleRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(text.dataRes), lineHeight = 20.sp)

                TextButton(
                    onClick = onOpenPrivacyPolicy,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        stringResource(text.privacyLinkRes),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp
                    )
                }
            }
        },
        // Ausdrückliche Handlung nötig — erst dieser Klick gilt als
        // Einwilligung und erst danach darf der Permission-Request laufen.
        confirmButton = {
            Button(onClick = onAccept) {
                Text(stringResource(text.acceptRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(text.declineRes))
            }
        }
    )
}

/**
 * Compose-Zustand für die Einbaustellen: kapselt „Offenlegung nötig?" und
 * „welche Aktion wartet?" an einer Stelle, damit kein Aufrufer die
 * Reihenfolge falsch verdrahten kann.
 *
 * Verwendung in einem Screen:
 * ```
 * val gate = rememberLocationDisclosureGate()
 * ...
 * onClick = { gate.request(DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION) { action ->
 *     runLocationRequest(action)
 * } }
 * ...
 * gate.pendingAction?.let { action ->
 *     LocationDisclosureDialog(
 *         onAccept = { gate.consent { runLocationRequest(it) } },
 *         onDecline = { gate.dismiss() },
 *         onOpenPrivacyPolicy = { openPrivacyPolicy(context) }
 *     )
 * }
 * ```
 */
class LocationDisclosureGateState(
    private val context: Context
) {
    /**
     * Aktion, die auf die Offenlegung wartet. `null` = kein Dialog offen.
     * Compose-State, damit das Erscheinen/Verschwinden des Dialogs eine
     * Recomposition auslöst.
     */
    var pendingAction: DisclosureGate.Action? by mutableStateOf(null)
        private set

    /**
     * Einstiegspunkt für jede standortbezogene Aktion.
     *
     * Liegt die bestätigte Offenlegung vor, läuft [onReady] sofort. Sonst
     * wird die Aktion gemerkt und der Dialog gezeigt — [onReady] läuft dann
     * erst nach ausdrücklicher Zustimmung.
     */
    fun request(
        action: DisclosureGate.Action,
        onReady: (DisclosureGate.Action) -> Unit
    ) {
        if (LocationDisclosure.isAccepted(context)) {
            onReady(action)
        } else {
            pendingAction = action
        }
    }

    /**
     * Bestätigung (Button „Einverstanden"): Zustimmung persistieren, Dialog
     * schließen und die gemerkte Aktion ausführen. [onReady] läuft immer
     * NACH dem Persistieren.
     */
    fun consent(onReady: (DisclosureGate.Action) -> Unit) {
        val action = pendingAction ?: return
        LocationDisclosure.markAccepted(context)
        pendingAction = null
        onReady(action)
    }

    /** Wegtippen/Zurück/„Nicht jetzt" — KEINE Einwilligung, keine Aktion. */
    fun dismiss() {
        pendingAction = null
    }
}

/** Compose-Helfer für [LocationDisclosureGateState]. */
@Composable
fun rememberLocationDisclosureGate(): LocationDisclosureGateState {
    val context = LocalContext.current
    return remember { LocationDisclosureGateState(context) }
}

/**
 * Öffnet die Datenschutzerklärung im Browser. Fehler werden geschluckt —
 * ein fehlender Browser darf den Permission-Flow nicht blockieren.
 */
fun openPrivacyPolicy(context: Context) {
    try {
        val url = context.getString(R.string.disclosure_privacy_url)
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        android.util.Log.e("LocationDisclosure", "Datenschutzerklärung konnte nicht geöffnet werden", e)
    }
}
