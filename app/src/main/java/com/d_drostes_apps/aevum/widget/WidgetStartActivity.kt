package com.d_drostes_apps.aevum.widget

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.LocalizedActivity
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.repository.MascotRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.ui.components.categoryColor
import com.d_drostes_apps.aevum.ui.screens.dashboard.MascotView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M23: Auswahl-Popup des Homescreen-Widgets — erscheint als Duolingo-
 * artiger Dialog über dem Launcher, sobald der grüne Start-Knopf gedrückt
 * wird. Eine Auswahl startet die Aufzeichnung sofort; läuft bereits etwas,
 * bietet der Dialog zusätzlich „Stoppen" an.
 */
@AndroidEntryPoint
class WidgetStartActivity : LocalizedActivity() {

    @Inject lateinit var liveActivityManager: LiveActivityManager
    @Inject lateinit var mascotRepository: MascotRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val favorites by liveActivityManager.favoriteActivityTypes.collectAsStateWithLifecycle()
                val allTypes by liveActivityManager.allActivityTypes.collectAsStateWithLifecycle()
                val live by liveActivityManager.liveSession.collectAsStateWithLifecycle()
                val mascot = remember { mascotRepository.currentSync() }

                WidgetPickerContent(
                    favorites = favorites,
                    allTypes = allTypes,
                    liveSession = live,
                    mascot = mascot,
                    onStart = { typeId -> startActivityRecording(typeId) },
                    onStop = { stopCurrentRecording() },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun startActivityRecording(typeId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                liveActivityManager.start(activityTypeId = typeId)
                LiveActivityService.start(applicationContext)
            } finally {
                AevumWidgetUpdater.updateAll(applicationContext)
                finish()
            }
        }
    }

    private fun stopCurrentRecording() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                liveActivityManager.stop()
                LiveActivityService.stop(applicationContext)
            } finally {
                AevumWidgetUpdater.updateAll(applicationContext)
                finish()
            }
        }
    }
}

@Composable
private fun WidgetPickerContent(
    favorites: List<ActivityType>,
    allTypes: List<ActivityType>,
    liveSession: ActivitySession?,
    mascot: MascotRepository.MascotConfig,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    // Duolingo-Palette — bewusst unabhängig vom dunklen App-Theme, damit
    // das Popup auf jedem Launcher freundlich und bunt wirkt.
    val cream = Color(0xFFFFF8E7)
    val ink = Color(0xFF1B2333)
    val muted = Color(0xFF6B7280)
    val green = Color(0xFF58CC02)
    val greenDark = Color(0xFF46A302)
    val red = Color(0xFFFF4B4B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(28.dp)),
            color = cream,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (mascot.enabled) {
                        MascotView(
                            config = mascot,
                            smileLevel = 0.78f,
                            sleeping = false,
                            mascotSize = 58.dp,
                            modifier = Modifier.size(58.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(green.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚡", fontSize = 22.sp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.widget_picker_title),
                            fontSize = 21.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ink
                        )
                        Text(
                            stringResource(R.string.widget_picker_subtitle),
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = muted
                        )
                    }
                }

                if (liveSession != null && liveSession.isLive) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onStop),
                        color = red.copy(alpha = 0.13f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(red),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("■", fontSize = 14.sp, color = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.widget_picker_running, liveSession.title),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = red,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.widget_picker_stop),
                                    fontSize = 11.sp,
                                    color = muted
                                )
                            }
                        }
                    }
                }

                val ordered = favorites + allTypes.filter { t -> favorites.none { it.id == t.id } }
                if (ordered.isEmpty()) {
                    Text(
                        stringResource(R.string.widget_empty_activities),
                        fontSize = 13.sp,
                        color = muted,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ordered, key = { it.id }) { type ->
                            PickerRow(
                                type = type,
                                ink = ink,
                                green = green,
                                onClick = { onStart(type.id) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(R.string.widget_picker_cancel),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = greenDark,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 26.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    type: ActivityType,
    ink: Color,
    green: Color,
    onClick: () -> Unit
) {
    val accent = if (type.color != 0L) Color(type.color)
    else categoryColor(type.defaultCategoryId ?: "unknown")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                type.icon.takeIf { it.isNotBlank() } ?: "•",
                fontSize = 18.sp
            )
        }
        Text(
            type.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(green),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", fontSize = 11.sp, color = Color.White)
        }
    }
}
