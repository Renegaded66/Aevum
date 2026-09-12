package com.d_drostes_apps.aevum.automation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.124-REGRESSION (User: "Benachrichtigung mit der Aufzeichnung wird
 * nicht mehr automatisch entfernt sobald die Aufzeichnung vorbei ist"):
 * JEDER Stop-Pfad, der eine Auto-/Walking-Session beendet, muss die
 * Live-Notification SOFORT entfernen (LiveActivityService.stop) — der
 * 1-10s-Tick des Services (bei Screen aus 10s, nach Prozess-Kill gar
 * nicht) ist kein verlässlicher Entfernungs-Pfad.
 *
 * Der Test scannt die Stop-Pfad-Quellen auf das Muster
 * `live.stop()` + `LiveActivityService.stop(...)` im selben Block.
 * Gleiche Datei-Scan-Technik wie StickyGuardInitRegressionTest
 * (M18.123) — die Pfade sind Worker-Klassen mit Android-Dependencies
 * (WorkManager/Hilt), daher kein reiner JVM-Unit-Test des Verhaltens,
 * sondern Struktur-Regression gegen das Vergessen künftiger Stop-Pfade.
 */
class DriveStopNotificationRegressionTest {

    private data class StopPathCheck(
        val file: String,
        val description: String,
        /** Funktion/Bereich, der die Session beendet. */
        val anchorBefore: String,
        val anchorAfter: String
    )

    private val paths: List<StopPathCheck> = listOf(
        StopPathCheck(
            file = "automation/activityrecognition/DriveWorkers.kt",
            description = "DriveStopWorker (Google-EXIT) beendet Session",
            anchorBefore = "bridge.markDriveStopped(System.currentTimeMillis())",
            anchorAfter = "triggerRepo.insert("
        ),
        StopPathCheck(
            file = "automation/activityrecognition/DriveWorkers.kt",
            description = "DriveWatchdogWorker.stopDrivingSession (5-Min-Regel)",
            anchorBefore = "bridge.markDriveStopped(now)",
            anchorAfter = "triggerRepo.insert("
        ),
        StopPathCheck(
            file = "automation/activityrecognition/WalkingWorkers.kt",
            description = "WalkingStopWorker beendet Session",
            anchorBefore = "live.stop()",
            anchorAfter = "WalkingWatchdogWorker.cancel(applicationContext)"
        ),
        StopPathCheck(
            file = "automation/activityrecognition/WalkingWorkers.kt",
            description = "WalkingWatchdogWorker (5-Min-Regel) beendet Session",
            anchorBefore = "// Wanderung beenden.",
            anchorAfter = "Walking-Session gestoppt (5 min ohne Signal)"
        ),
        StopPathCheck(
            file = "automation/geofence/CurrentZoneProvider.kt",
            description = "Geofence-Auto-Stop (direct_auto_stop)",
            anchorBefore = "liveActivityManager.stop()",
            anchorAfter = "Auto-Stop: ${"$"}{prevGf?.name}"
        ),
        StopPathCheck(
            file = "automation/geofence/GeofenceTransitionProcessor.kt",
            description = "Geofence-Auto-Stop (Processor-EXIT)",
            anchorBefore = "liveActivityManager.cancelAutoDiscard(geofence.id)",
            anchorAfter = "Auto-Stop: ${"$"}{existing.title}"
        )
    )

    private fun source(rel: String): String {
        val candidates = listOf(
            File(".").resolve("src/main/java/com/d_drostes_apps/aevum/$rel"),
            File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/$rel"),
            File("../..").resolve("app/src/main/java/com/d_drostes_apps/aevum/$rel")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("Quelldatei nicht gefunden: $rel (CWD=${File(".").absolutePath})")
    }

    @Test
    fun `jeder Stop-Pfad entfernt die Live-Notification sofort`() {
        for (check in paths) {
            val src = source(check.file)
            // Block zwischen den Ankern: enthält live.stop() UND
            // LiveActivityService.stop(...) — der Notification-Stop muss
            // im SELBEN Stop-Block stehen (nicht irgendwo in der Datei).
            val start = src.indexOf(check.anchorBefore)
            assertWithMessage("${check.file}: Anker '${check.anchorBefore}' nicht gefunden (${check.description})")
                .that(start).isAtLeast(0)
            val end = src.indexOf(check.anchorAfter, start)
            assertWithMessage("${check.file}: Anker '${check.anchorAfter}' nicht gefunden (${check.description})")
                .that(end).isAtLeast(0)
            val block = src.substring(start, end)

            assertWithMessage("${check.file}: Session-Stop fehlt im Block (${check.description})")
                .that(block.contains("live.stop()") || block.contains("liveActivityManager.stop()"))
                .isTrue()
            assertWithMessage(
                "${check.file}: LiveActivityService.stop() fehlt im Stop-Block — " +
                    "Notification würde hängen bleiben (${check.description}, M18.124)"
            )
                .that(block.contains("LiveActivityService.stop("))
                .isTrue()
        }
    }
}
