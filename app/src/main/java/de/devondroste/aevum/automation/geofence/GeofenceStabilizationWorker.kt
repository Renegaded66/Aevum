package de.devondroste.aevum.automation.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.automation.geofence.GeofenceDebouncer.ConfirmationResult

/**
 * M11.2: Bestätigt einen pendenden Geofence-Übergang nach Ablauf der
 * Stabilisierungszeit. Wenn der Übergang immer noch pending ist
 * (nicht durch GPS-Flattern verworfen), wird er an den
 * GeofenceTransitionProcessor weitergegeben.
 *
 * Wird vom GeofenceBroadcastReceiver mit einer Verzögerung von
 * STABILIZATION_MS (2 Minuten) via WorkManager gequeued.
 */
class GeofenceStabilizationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeofenceEntryPoint {
        fun debouncer(): GeofenceDebouncer
        fun processor(): GeofenceTransitionProcessor
    }

    override suspend fun doWork(): Result {
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID) ?: return Result.failure()
        val transitionName = inputData.getString(KEY_TRANSITION) ?: return Result.failure()
        val occurredAt = inputData.getLong(KEY_OCCURRED_AT, System.currentTimeMillis())
        val transition = runCatching { GeofenceTransition.valueOf(transitionName) }.getOrNull()
            ?: return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, GeofenceEntryPoint::class.java
        )
        val debouncer = entryPoint.debouncer()
        val processor = entryPoint.processor()

        val result = debouncer.confirmPending(geofenceId, transition, System.currentTimeMillis())
        when (result) {
            ConfirmationResult.Confirmed -> {
                // Stabilisiert! Trigger verarbeiten.
                processor.processTransition(
                    geofenceId = geofenceId,
                    transition = transition,
                    occurredAt = occurredAt
                )
            }
            ConfirmationResult.Cancelled -> {
                // GPS-Flattern hat den pendenten Übergang verworfen.
            }
            ConfirmationResult.AlreadyEmitted -> {
                // Wurde bereits bestätigt (z.B. durch ein früheres Event).
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_GEOFENCE_ID = "geofence_id"
        const val KEY_TRANSITION = "transition"
        const val KEY_OCCURRED_AT = "occurred_at"

        const val WORK_PREFIX = "aevum.geofence.stabilize."
    }
}