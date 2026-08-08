package com.d_drostes_apps.aevum.ui.screens.automation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * M12.1: Ein-Schuss-Signal vom Dashboard zur Automation-Settings-Screen.
 *
 * Wenn der Nutzer im Dashboard auf "Bildschirmzeit aktivieren" tippt, soll
 * die Automation-Screen direkt zum Digital-Balance-Block scrollen.
 *
 * Wir nutzen einen einfachen In-Memory-Bus statt SavedStateHandle, damit
 * die Composable-Signatur unverändert bleibt (kein Refactoring).
 */
object AutomationScrollSignal {
    private val pending = AtomicBoolean(false)

    fun requestScrollToUsage() {
        pending.set(true)
    }

    /** Liefert genau einmal true und resettet das Signal. */
    fun consumeScrollToUsage(): Boolean = pending.getAndSet(false)
}
