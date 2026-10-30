package com.d_drostes_apps.aevum.ui.screens.activitytypes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.ui.components.pickOnColor
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val ACTIVITY_CREATE_TAG = "activity_create"
const val ACTIVITY_CREATE_NAME_TAG = "activity_create_name"
const val ACTIVITY_CREATE_SAVE_TAG = "activity_create_save"

private val ICON_CHOICES = listOf(
    "✨", "💼", "🧠", "🏋️", "🏃", "🚴", "🧘", "📚",
    "📖", "✍️", "🎸", "🎧", "🎨", "💻", "📱", "🎮",
    "🍽️", "☕", "🧹", "🛒", "🚗", "🚆", "✈️", "👥",
    "❤️", "🌙", "😴", "🚿", "🐕", "🌱", "🙏", "🎯",
)

/** Ruhige, kräftige Palette — passend zu den Seed-Farben der Aktivitäten. */
private val COLOR_CHOICES = listOf(
    0xFF29B6F6, 0xFF5C6BC0, 0xFF7E57C2, 0xFF3949AB,
    0xFF43A047, 0xFF26A69A, 0xFF66BB6A, 0xFFA855F7,
    0xFFF59E0B, 0xFFFFA726, 0xFFEF5350, 0xFFEC407A,
    0xFF8D6E63, 0xFF78909C, 0xFF0288D1, 0xFF9E9E9E,
)

/**
 * Eigener Anlegen-Screen für Aktivitäten (M33).
 *
 * Vorher legte der Starter eine Aktivität über einen AlertDialog mit nur
 * einem Namensfeld an — Symbol, Farbe und Kategorie waren danach nur in
 * den Einstellungen nachziehbar. Hier entsteht die Aktivität in einem
 * Schritt, mit Live-Vorschau: Name → Symbol → Farbe → Kategorie → Favorit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityCreateScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ActivityCreateViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf("✨") }
    var color by rememberSaveable { mutableStateOf(COLOR_CHOICES.first()) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val accent = Color(color)
    val canSave = name.isNotBlank() && !saving

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .testTag(ACTIVITY_CREATE_TAG),
        ) {
            // Kopf
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.activitycreate_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.activitycreate_subtitle),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AevumSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg),
            ) {
                // Live-Vorschau
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accent.copy(alpha = 0.30f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                )
                            )
                        )
                        .border(
                            1.dp,
                            accent.copy(alpha = 0.45f),
                            RoundedCornerShape(24.dp),
                        )
                        .padding(AevumSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f))
                            .border(1.5.dp, accent.copy(alpha = 0.75f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, fontSize = 32.sp)
                    }
                    Spacer(Modifier.width(AevumSpacing.md))
                    Column {
                        Text(
                            text = name.trim().ifEmpty { stringResource(R.string.activitycreate_preview) },
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (name.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.activitycreate_preview_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.activitycreate_name)) },
                    placeholder = { Text(stringResource(R.string.activitycreate_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ACTIVITY_CREATE_NAME_TAG),
                )

                // Symbol
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.activitycreate_icon),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ICON_CHOICES.forEach { choice ->
                            val selected = choice == icon
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (selected) accent.copy(alpha = 0.22f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) accent.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(13.dp),
                                    )
                                    .clickable { icon = choice },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(choice, fontSize = 20.sp)
                            }
                        }
                    }
                }

                // Farbe
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.activitycreate_color),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        COLOR_CHOICES.forEach { choice ->
                            val selected = choice == color
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(choice))
                                    .border(
                                        width = if (selected) 2.5.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                        shape = CircleShape,
                                    )
                                    .clickable { color = choice },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = pickOnColor(Color(choice)),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Kategorie
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.activitycreate_category),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CategoryChoice(
                            label = stringResource(R.string.activitytypes_no_category),
                            selected = categoryId == null,
                            accent = accent,
                            onClick = { categoryId = null },
                        )
                        categories.forEach { category ->
                            CategoryChoice(
                                label = "${category.icon} ${category.name}",
                                selected = categoryId == category.id,
                                accent = accent,
                                onClick = { categoryId = category.id },
                            )
                        }
                    }
                }

                // Favorit
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                        .padding(horizontal = AevumSpacing.md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.activitycreate_favorite),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.activitycreate_favorite_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = favorite, onCheckedChange = { favorite = it })
                }

                Spacer(Modifier.height(AevumSpacing.sm))
            }

            // Speichern
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
                ) {
                    Surface(
                        onClick = {
                            if (!canSave) return@Surface
                            saving = true
                            viewModel.create(
                                name = name,
                                icon = icon,
                                color = color,
                                categoryId = categoryId,
                                favorite = favorite,
                                onDone = {
                                    saving = false
                                    onSaved()
                                },
                            )
                        },
                        enabled = canSave,
                        shape = RoundedCornerShape(18.dp),
                        color = if (canSave) accent else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag(ACTIVITY_CREATE_SAVE_TAG),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.activitycreate_save),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canSave) pickOnColor(accent)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChoice(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) accent.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@HiltViewModel
class ActivityCreateViewModel @Inject constructor(
    private val activityTypeRepository: ActivityTypeRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<Category>> = categoryRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Legt die Aktivität an. Kein stiller Fallback: schlägt der Insert fehl,
     * bleibt der Screen offen (die Exception läuft sichtbar auf) — ein
     * „gespeichert"-Gefühl ohne Datensatz wäre schlimmer als ein Fehler.
     */
    fun create(
        name: String,
        icon: String,
        color: Long,
        categoryId: String?,
        favorite: Boolean,
        onDone: () -> Unit,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            activityTypeRepository.insert(
                ActivityType(
                    id = "custom_${UUID.randomUUID().toString().take(8)}",
                    name = trimmed,
                    defaultCategoryId = categoryId,
                    isSystem = false,
                    propertiesJson = null,
                    isFavorite = favorite,
                    positivityScore = 50,
                    icon = icon,
                    color = color,
                )
            )
            onDone()
        }
    }
}
