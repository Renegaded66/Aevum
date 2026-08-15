package com.d_drostes_apps.aevum.ui.screens.apptracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.67: App-Aufzeichnung — Apps auswählen, die automatisch als
 * Activity aufgezeichnet werden.
 *
 * Layout (User-Spezifikation):
 *  - Oben: Suchleiste (Live-Suche über BEIDE Spalten)
 *  - Links: alle installierten Apps (nicht getrackt)
 *  - Rechts: Apps, die zur Aufzeichnung berücksichtigt werden
 *  - Klick auf eine App → wechselt die Spalte
 *  - 3-Punkte-Menü (rechts) → Activity zuordnen
 */
@Composable
fun AppTrackingScreen(
    onBack: () -> Unit,
    viewModel: AppTrackingViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Kopfzeile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "App-Aufzeichnung",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Apps automatisch als Activity aufzeichnen",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!state.hasUsagePermission) {
            PermissionHint(onOpenSettings = viewModel::openUsageAccessSettings)
            return
        }

        // Suchleiste
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs),
            placeholder = { Text("Apps suchen…", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Suche löschen", modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(AevumRadius.lg)
        )

        // Zwei Spalten
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = AevumSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            AppColumn(
                title = "Alle Apps",
                subtitle = "${state.allApps.size}",
                apps = state.allApps,
                accent = MaterialTheme.colorScheme.primary,
                onAppClick = { viewModel.addApp(it.packageName) },
                onAssignActivity = null,
                activityTypes = state.activityTypes,
                modifier = Modifier.weight(1f)
            )
            AppColumn(
                title = "Aufzeichnung",
                subtitle = "${state.trackedApps.size}",
                apps = state.trackedApps,
                accent = MaterialTheme.colorScheme.tertiary,
                onAppClick = { viewModel.removeApp(it.packageName) },
                onAssignActivity = { app, typeId -> viewModel.assignActivity(app.packageName, typeId) },
                activityTypes = state.activityTypes,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppColumn(
    title: String,
    subtitle: String,
    apps: List<TrackableApp>,
    accent: Color,
    onAppClick: (TrackableApp) -> Unit,
    onAssignActivity: ((TrackableApp, String) -> Unit)?,
    activityTypes: List<ActivityType>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Spalten-Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AevumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(AevumSpacing.xs))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AevumSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (title == "Aufzeichnung") "Tippe links auf eine App" else "Keine Treffer",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        accent = accent,
                        onClick = { onAppClick(app) },
                        onAssignActivity = onAssignActivity?.let { { typeId -> it(app, typeId) } },
                        activityTypes = activityTypes
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: TrackableApp,
    accent: Color,
    onClick: () -> Unit,
    onAssignActivity: ((String) -> Unit)?,
    activityTypes: List<ActivityType>
) {
    var menuOpen by remember { mutableStateOf(false) }

    AevumCard(
        variant = CardVariant.Elevated,
        contentPadding = PaddingValues(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App-Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AevumRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Text(
                        app.appLabel.take(1).uppercase(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            // App-Name + zugeordnete Activity
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.activityTypeId != null) {
                    val typeName = activityTypes.firstOrNull { it.id == app.activityTypeId }?.name
                    if (typeName != null) {
                        Text(
                            typeName,
                            fontSize = 11.sp,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // 3-Punkte-Menü (nur in der rechten Spalte)
            if (onAssignActivity != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Activity zuordnen",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text(
                            "Activity zuordnen",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs)
                        )
                        HorizontalDivider()
                        activityTypes.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            type.icon ?: "•",
                                            fontSize = 14.sp,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Text(type.name, fontSize = 13.sp)
                                        if (type.id == app.activityTypeId) {
                                            Spacer(Modifier.width(AevumSpacing.sm))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Ausgewählt",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onAssignActivity(type.id)
                                }
                            )
                        }
                    }
                }
            } else {
                // Linke Spalte: dezenter Plus-Button als Affordanz
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Zur Aufzeichnung hinzufügen",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun PermissionHint(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AevumSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nutzungszugriff erforderlich",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(AevumSpacing.sm))
        Text(
            "Aevum braucht den Nutzungszugriff, um zu erkennen, welche App gerade geöffnet ist.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AevumSpacing.lg)
        )
        Spacer(Modifier.height(AevumSpacing.md))
        androidx.compose.material3.Button(onClick = onOpenSettings) {
            Text("Nutzungszugriff erteilen")
        }
    }
}
