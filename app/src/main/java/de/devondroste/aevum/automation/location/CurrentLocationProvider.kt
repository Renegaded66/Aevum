package de.devondroste.aevum.automation.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class CurrentLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): CurrentLocationResult {
        if (!hasForegroundLocation()) return CurrentLocationResult.MissingPermission
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(MAX_UPDATE_AGE_MS)
            .setDurationMillis(LOCATION_TIMEOUT_MS)
            .build()
        return try {
            val location = client.getCurrentLocation(request, CancellationTokenSource().token).await()
            if (location == null) {
                CurrentLocationResult.Unavailable("Android konnte aktuell keine Position ermitteln.")
            } else {
                CurrentLocationResult.Success(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
                )
            }
        } catch (security: SecurityException) {
            CurrentLocationResult.MissingPermission
        } catch (error: Exception) {
            CurrentLocationResult.Unavailable(error.message ?: "Position konnte nicht ermittelt werden")
        }
    }

    private fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_UPDATE_AGE_MS = 2 * 60 * 1000L
        const val LOCATION_TIMEOUT_MS = 8 * 1000L
    }
}

sealed class CurrentLocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val ageMs: Long
    ) : CurrentLocationResult()
    data object MissingPermission : CurrentLocationResult()
    data class Unavailable(val message: String) : CurrentLocationResult()
}
