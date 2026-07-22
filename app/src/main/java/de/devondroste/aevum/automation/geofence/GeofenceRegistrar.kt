package de.devondroste.aevum.automation.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val debugLogger: GeofenceDebugLogger
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    /**
     * M8.1: Refined permission model.
     *
     * Android 14+: "Allow all the time" implicitly grants ACCESS_BACKGROUND_LOCATION.
     * Pre-Q devices don't need background permission at all.
     * The registrar now distinguishes between "not applicable" (pre-Q) and "not granted".
     */
    data class PermissionStatus(
        val foregroundGranted: Boolean,
        val backgroundApplicable: Boolean, // false on pre-Q
        val backgroundGranted: Boolean
    )

    fun getPermissionStatus(): PermissionStatus {
        val foreground = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bgApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val bgGranted = if (!bgApplicable) true else has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return PermissionStatus(foreground, bgApplicable, bgGranted)
    }

    suspend fun refreshRegisteredGeofences(): GeofenceRegistrationResult {
        val perms = getPermissionStatus()
        if (!perms.foregroundGranted) {
            debugLogger.log("REGISTRAR", "Kein Foreground-Standort")
            return GeofenceRegistrationResult.MissingForegroundLocation
        }
        // M8.1: Don't block on background — try registration anyway on 14+ where
        // ACCESS_BACKGROUND_LOCATION check may be stale while "Allow all the time" is active.
        if (!perms.backgroundGranted && perms.backgroundApplicable) {
            debugLogger.log("REGISTRAR", "Background-Standort nicht explizit erteilt — Android 14+ kann trotzdem funktionieren")
            // Don't return error — proceed to try registration
        }

        val geofences = geofenceRepository.getAllEnabled().first()
            .filter { it.deletedAt == null }
            .take(MAX_ANDROID_GEOFENCES)

        debugLogger.log("REGISTRAR", "Registriere ${geofences.size} Geofences")

        return try {
            // Start foreground service for Android 15+ compatibility
            GeofenceForegroundService.start(context)

            client.removeGeofences(pendingIntent()).await()
            if (geofences.isNotEmpty()) {
                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(geofences.map { place ->
                        val f = Geofence.Builder()
                            .setRequestId(place.id)
                            .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.coerceAtLeast(MIN_RADIUS_METERS))
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setNotificationResponsiveness(RESPONSIVENESS_MS)
                            .setLoiteringDelay(LOITERING_DELAY_MS)
                            .build()
                        debugLogger.log("REGISTRAR", "  ${place.name}: r=${place.radiusMeters}m")
                        f
                    })
                    .build()
                client.addGeofences(request, pendingIntent()).await()
                debugLogger.log("REGISTRAR", "${geofences.size} Geofences registriert")
            }
            GeofenceRegistrationResult.Registered(geofences.size)
        } catch (security: SecurityException) {
            debugLogger.log("REGISTRAR", "SecurityException: ${security.message}")
            GeofenceRegistrationResult.SecurityDenied
        } catch (error: Exception) {
            debugLogger.log("REGISTRAR", "Fehler: ${error.message}")
            GeofenceRegistrationResult.Failed(error.message ?: "Geofence Registrierung fehlgeschlagen")
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setAction(ACTION_GEOFENCE_EVENT)
        return PendingIntent.getBroadcast(
            context,
            42,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /** Opens the appropriate settings page for granting background location (Android 14+ compatible). */
    fun openBackgroundLocationSettings() {
        try {
            // Prefer direct location settings on Android 12+ where possible
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback to app settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_GEOFENCE_EVENT = "de.devondroste.aevum.ACTION_GEOFENCE_EVENT"
        private const val MAX_ANDROID_GEOFENCES = 100
        private const val MIN_RADIUS_METERS = 50f
        private const val RESPONSIVENESS_MS = 2 * 60 * 1000
        private const val LOITERING_DELAY_MS = 5 * 60 * 1000
    }
}

sealed class GeofenceRegistrationResult {
    data class Registered(val count: Int) : GeofenceRegistrationResult()
    data object MissingForegroundLocation : GeofenceRegistrationResult()
    data object MissingBackgroundLocation : GeofenceRegistrationResult()
    data object SecurityDenied : GeofenceRegistrationResult()
    data class Failed(val message: String) : GeofenceRegistrationResult()
}
