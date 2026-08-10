package com.d_drostes_apps.aevum.domain.digital

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M18.61g-FIX 2: Vollbild-Sperr-Popup (User: "immer ein Pop Up von Aevum
 * erscheint wenn man gesperrte Apps öffnet").
 *
 * Ersetzt das TYPE_APPLICATION_OVERLAY-Overlay: Eine eigene Activity ist
 * zuverlässiger, weil sie KEINE "Über anderen Apps anzeigen"-Berechtigung
 * braucht (die App hat sie nie angefragt → wm.addView() warf still →
 * keine Sperre). Die Activity drängt die gesperrte App garantiert in den
 * Hintergrund (sie wird pausiert — Instagram läuft nicht weiter).
 *
 * Der Service startet sie mit FLAG_ACTIVITY_NEW_TASK; taskAffinity=""
 * + excludeFromRecents + singleInstance isolieren sie vom Task der
 * gesperrten App. Drückt der User "Schließen", ist die gesperrte App
 * wieder im Vordergrund → der Service (2s-Check) startet das Popup
 * erneut — der Sperr-Loop. Nur "Noch 5 Minuten" / "Heute ignorieren"
 * entsperren wirklich.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val limitMinutes = intent.getIntExtra(EXTRA_LIMIT_MINUTES, 0)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)

        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }

        setContent {
            MaterialTheme {
                BlockScreen(
                    appLabel = label,
                    limitMinutes = limitMinutes,
                    profileName = profileName,
                    onExtend = {
                        sendAction(AppBlockService.ACTION_EXTEND, pkg)
                        finish()
                    },
                    onIgnoreToday = {
                        sendAction(AppBlockService.ACTION_IGNORE_TODAY, pkg)
                        finish()
                    },
                    onClose = {
                        sendAction(AppBlockService.ACTION_CLOSE, pkg)
                        finish()
                    }
                )
            }
        }
    }

    /** M18.61g-FIX 2: Button-Aktion an den AppBlockService melden. */
    private fun sendAction(action: String, pkg: String) {
        try {
            sendBroadcast(
                Intent(action).setPackage(packageName).putExtra(AppBlockService.EXTRA_PKG, pkg)
            )
        } catch (_: Exception) { /* Service nicht erreichbar — egal */ }
    }

    companion object {
        private const val EXTRA_PKG = "blocked_pkg"
        private const val EXTRA_LIMIT_MINUTES = "limit_minutes"
        private const val EXTRA_PROFILE_NAME = "profile_name"

        fun start(
            context: Context,
            pkg: String,
            limitMinutes: Int,
            profileName: String?
        ) {
            val intent = Intent(context, BlockActivity::class.java).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_LIMIT_MINUTES, limitMinutes)
                putExtra(EXTRA_PROFILE_NAME, profileName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun BlockScreen(
    appLabel: String,
    limitMinutes: Int,
    profileName: String?,
    onExtend: () -> Unit,
    onIgnoreToday: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141620)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🔒", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "$appLabel gesperrt",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (profileName != null) {
                    "Profil \"$profileName\" ist aktiv — diese App ist gesperrt."
                } else {
                    "Tägliches Limit von $limitMinutes Minuten erreicht."
                },
                fontSize = 15.sp,
                color = Color(0xFFB0B8C8),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onExtend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Noch 5 Minuten", fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onIgnoreToday,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFB0B8C8)
                )
            ) {
                Text("Heute ignorieren", fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFB0B8C8)
                )
            ) {
                Text("Schließen", fontSize = 15.sp)
            }
        }
    }
}
