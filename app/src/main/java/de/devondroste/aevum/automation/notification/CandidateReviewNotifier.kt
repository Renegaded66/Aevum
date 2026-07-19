package de.devondroste.aevum.automation.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.MainActivity
import de.devondroste.aevum.R
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.repository.AutomationSettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class CandidateReviewNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: AutomationSettingsRepository
) {
    @SuppressLint("MissingPermission")
    suspend fun notifyIfEnabled(candidates: List<ActivityCandidate>): CandidateNotificationResult {
        if (candidates.isEmpty()) return CandidateNotificationResult.NoCandidate
        val settings = settingsRepository.get().first()
        if (settings?.reviewNotificationsEnabled != true) return CandidateNotificationResult.DisabledByUser
        if (!hasNotificationPermission()) return CandidateNotificationResult.MissingPermission

        ensureChannel()
        val newest = candidates.maxBy { it.createdAt }
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (candidates.size == 1) {
            "Aevum hat „${newest.suggestedTitle}“ erkannt. Bitte kurz prüfen."
        } else {
            "Aevum hat ${candidates.size} neue Vorschläge erkannt. Bitte kurz prüfen."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Neue Aktivität prüfen")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()
        if (!hasNotificationPermission()) return CandidateNotificationResult.MissingPermission
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return CandidateNotificationResult.Delivered(candidates.size)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Aevum Review", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Zurückhaltende Hinweise für neue überprüfbare Aktivitätsvorschläge"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "aevum_candidate_review"
        const val NOTIFICATION_ID = 6201
    }
}

sealed class CandidateNotificationResult {
    data object NoCandidate : CandidateNotificationResult()
    data object DisabledByUser : CandidateNotificationResult()
    data object MissingPermission : CandidateNotificationResult()
    data class Delivered(val count: Int) : CandidateNotificationResult()
}
