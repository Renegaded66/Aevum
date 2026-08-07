package de.devondroste.aevum.ui.screens.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.devondroste.aevum.domain.liveactivity.LiveActivityManager
import de.devondroste.aevum.domain.liveactivity.LiveActivityService
import de.devondroste.aevum.ui.components.categoryColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.19: Wechsel-Popup (Dialog-Activity, transparent).
 *
 * Wird aus der Live-Notification ("⇄ Wechseln") geöffnet und erscheint
 * als Popup ÜBER jeder App. Der User wählt eine neue Aktivität →
 * [LiveActivityManager.start] beendet die aktuelle Session automatisch
 * (forceFinish) und startet die neue — ein Aufruf, ein Zustandswechsel.
 * Die Notification wird über [LiveActivityService.start] sofort neu
 * aufgebaut (neue Farbe, neuer Titel, Timer zurückgesetzt).
 */
@AndroidEntryPoint
class SwitchActivity : ComponentActivity() {

    @Inject lateinit var liveActivityManager: LiveActivityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SwitchContent(
                    liveActivityManager = liveActivityManager,
                    onSelect = { typeId ->
                        CoroutineScope(Dispatchers.Main).launch {
                            liveActivityManager.start(typeId)
                            LiveActivityService.start(applicationContext)
                        }
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun SwitchContent(
    liveActivityManager: LiveActivityManager,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Vollflächiger, halbtransparenter Scrim — Popup-Feel über der App.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Der eigentliche Dialog
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF1E1E2E),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Aktivität wechseln",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "Die aktuelle Aktivität wird beendet und die neue gestartet.",
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0C0)
                )

                val types by liveActivityManager.favoriteActivityTypes.collectAsStateWithLifecycle()
                val allTypes by liveActivityManager.allActivityTypes.collectAsStateWithLifecycle()

                LazyColumn(
                    modifier = Modifier.height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Favoriten zuerst
                    items(types, key = { "fav-${it.id}" }) { type ->
                        SwitchRow(
                            icon = type.icon,
                            name = type.name,
                            categoryName = null,
                            accent = if (type.color != 0L) Color(type.color) else categoryColor(type.defaultCategoryId ?: "unknown"),
                            onClick = { onSelect(type.id) }
                        )
                    }
                    // Rest
                    items(
                        allTypes.filter { t -> types.none { it.id == t.id } },
                        key = { "all-${it.id}" }
                    ) { type ->
                        SwitchRow(
                            icon = type.icon,
                            name = type.name,
                            categoryName = null,
                            accent = if (type.color != 0L) Color(type.color) else categoryColor(type.defaultCategoryId ?: "unknown"),
                            onClick = { onSelect(type.id) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Abbrechen",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF8B8BA0),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: String,
    name: String,
    categoryName: String?,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (icon.isBlank()) "•" else icon,
                fontSize = 20.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (categoryName != null) {
                Text(
                    categoryName,
                    fontSize = 11.sp,
                    color = Color(0xFF9A9AB0)
                )
            }
        }
        Text(
            "→",
            fontSize = 18.sp,
            color = accent
        )
    }
}
