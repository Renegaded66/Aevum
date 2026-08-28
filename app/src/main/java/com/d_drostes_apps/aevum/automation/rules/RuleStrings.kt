package com.d_drostes_apps.aevum.automation.rules

import android.content.Context
import com.d_drostes_apps.aevum.R

/**
 * Liefert lokalisierte Titel/Reasons für die Rule-Engines.
 *
 * Die Engines sind reine JVM-Klassen (Unit-Tests ohne Android-Runtime).
 * Deshalb ist der Context optional: In der App wird er von Hilt injiziert
 * (Ressourcen in der aktuellen Sprache), in Tests ist er null und es
 * greifen die deutschen Fallback-Literale (Tests prüfen exakt diese).
 */
class RuleStrings(private val context: Context?) {

    private fun s(key: Int): String = context?.getString(key) ?: fallback(key)

    private fun s(key: Int, vararg args: Any): String =
        context?.getString(key, *args) ?: String.format(fallback(key), *args)

    fun commuteToWork() = s(R.string.rule_title_commute_to_work)
    fun commuteHome() = s(R.string.rule_title_commute_home)
    fun gymTrip() = s(R.string.rule_title_gym_trip)
    fun shopping(placeName: String) = s(R.string.rule_title_shopping, placeName)
    fun gymReturn() = s(R.string.rule_title_gym_return)
    fun transit(from: String, to: String) = s(R.string.rule_title_transit, from, to)
    fun work() = s(R.string.rule_title_work)
    fun gym() = s(R.string.rule_title_gym)
    fun home() = s(R.string.rule_title_home)
    fun trip() = s(R.string.rule_title_trip)

    fun reasonTravel(fromTo: String, title: String) = s(R.string.rule_reason_travel, fromTo, title)
    fun reasonStay(placeName: String) = s(R.string.rule_reason_stay, placeName)
    fun reasonAway(homeName: String) = s(R.string.rule_reason_away, homeName)
    fun reasonMerge(first: String, second: String) = s(R.string.rule_reason_merge, first, second)
    fun mergeFallback() = s(R.string.rule_reason_merge_fallback)

    fun activityFallback() = s(R.string.common_activity_fallback)

    private fun fallback(key: Int): String = when (key) {
        R.string.rule_title_commute_to_work -> "Arbeitsweg"
        R.string.rule_title_commute_home -> "Heimweg"
        R.string.rule_title_gym_trip -> "Anfahrt: Fitnessstudio"
        R.string.rule_title_shopping -> "Einkauf: %1\$s"
        R.string.rule_title_gym_return -> "Rückfahrt: Fitnessstudio"
        R.string.rule_title_transit -> "Unterwegs: %1\$s → %2\$s"
        R.string.rule_title_work -> "Arbeit"
        R.string.rule_title_gym -> "Fitnessstudio"
        R.string.rule_title_home -> "Zuhause"
        R.string.rule_title_trip -> "Ausflug"
        R.string.rule_reason_travel -> "Trigger-Paar erkannt: %1\$s. %2\$s als Wegzeit vorgeschlagen."
        R.string.rule_reason_stay -> "Trigger-Paar erkannt: %1\$s betreten → verlassen. Vorschlag bleibt überprüfbar."
        R.string.rule_reason_away -> "Trigger-Paar erkannt: %1\$s verlassen → wieder angekommen. Kein Ziel bekannt, daher vorsichtig als Ausflug vorgeschlagen."
        R.string.rule_reason_merge -> "%1\$s | Zusammengeführt mit %2\$s (Lücke < 5min)."
        R.string.rule_reason_merge_fallback -> "weiterem Kandidaten"
        R.string.common_activity_fallback -> "Aktivität"
        else -> ""
    }
}
