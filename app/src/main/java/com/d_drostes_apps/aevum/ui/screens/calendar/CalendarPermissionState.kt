package com.d_drostes_apps.aevum.ui.screens.calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * M18.129: Der Berechtigungs-Zustand für READ_CALENDAR.
 *
 * VIER Zustände, nicht zwei — Muster aus dem Skill
 * `android-permission-flow-composition`:
 *
 *   NotAsked          → Erklärung + "Kalender verbinden"
 *   Granted           → Banner verschwindet, Regeln sichtbar
 *   Denied            → "Erneut versuchen" (Android zeigt den Dialog
 *                       noch einmal)
 *   PermanentlyDenied → NUR "App-Einstellungen öffnen", weil ein
 *                       weiterer Dialog nichts mehr bewirkt
 *
 * Warum keinen Boolean: ein `if (hasPermission) { … }` ohne `else`-Pfad
 * ist genau die Falle, bei der der Nutzer einen Knopf drückt und nichts
 * passiert — er hält die App für kaputt.
 */
sealed class CalendarPermissionState {

    /** Noch nie gefragt — oder der Nutzer hat den Dialog weggewischt. */
    object NotAsked : CalendarPermissionState()

    /** System-Berechtigung erteilt. */
    object Granted : CalendarPermissionState()

    /** Mindestens einmal abgelehnt, erneuter Dialog möglich. */
    object Denied : CalendarPermissionState()

    /**
     * Endgültig abgelehnt ("Nicht mehr fragen") oder vom System blockiert.
     * Ein weiterer Dialog würde stillschweigend nichts tun.
     */
    object PermanentlyDenied : CalendarPermissionState()

    val isGranted: Boolean get() = this is Granted

    companion object {
        /**
         * Zustand aus der Umgebung ableiten.
         *
         * Die Unterscheidung Denied/PermanentlyDenied nutzt die
         * Android-Heuristik: `shouldShowRequestPermissionRationale == false`
         * UND nicht erteilt bedeutet "endgültig abgelehnt" — vorausgesetzt,
         * die App hat überhaupt schon gefragt.
         */
        fun fromContext(context: Context): CalendarPermissionState {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return Granted

            val activity = context.findActivity()
            // Ohne Activity ist die Rationale-Abfrage nicht möglich
            // (sie braucht eine echte Activity, nicht nur einen
            // ContextWrapper). Dann konservativ "Denied" — der Nutzer
            // bekommt den Dialog erneut gezeigt, was harmlos ist.
                ?: return Denied

            return if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.READ_CALENDAR
                )
            ) {
                Denied
            } else {
                // Kein Rationale-Dialog mehr nötig → entweder noch nie
                // gefragt (dann ist "PermanentlyDenied" zu hart, aber der
                // Settings-Deep-Link ist trotzdem ein gangbarer Weg) oder
                // endgültig abgelehnt. Beides führt hier zum gleichen,
                // ehrlichen UI-Pfad: ein Hinweis + Settings-Zugang.
                PermanentlyDenied
            }
        }

        /** Activity aus einer beliebig verschachtelten Context-Hülle holen. */
        private fun Context.findActivity(): Activity? {
            var ctx: Context? = this
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }
}
