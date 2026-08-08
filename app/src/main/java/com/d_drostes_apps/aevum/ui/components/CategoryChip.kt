package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.theme.AevumCategoryColors
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

@Composable
fun CategoryChip(
    modifier: Modifier = Modifier,
    categoryId: String,
    label: String = categoryId
) {
    val color = categoryColor(categoryId)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AevumRadius.full),
        color = color.copy(alpha = 0.14f),
        contentColor = color
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(AevumSpacing.xs))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val color = when (source) {
        "SLEEP" -> AevumCategoryColors.sleep
        "GEOFENCE" -> MaterialTheme.colorScheme.secondary
        "ACTIVITY" -> AevumCategoryColors.sport
        "USAGE" -> AevumCategoryColors.smartphone
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(AevumRadius.full), color = color.copy(alpha = 0.12f)) {
        Text(
            text = sourceLabel(source),
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

fun sourceLabel(source: String): String = when (source) {
    "SLEEP" -> "Schlaf"
    "GEOFENCE" -> "Ort"
    "ACTIVITY" -> "Bewegung"
    "USAGE" -> "Digital"
    "MANUAL" -> "Manuell"
    else -> source.lowercase().replaceFirstChar { it.titlecase() }
}

fun categoryColor(category: String): Color = when (category.lowercase()) {
    "arbeit" -> AevumCategoryColors.work
    "schlaf" -> AevumCategoryColors.sleep
    "sport" -> AevumCategoryColors.sport
    "lernen" -> AevumCategoryColors.learning
    "freizeit" -> AevumCategoryColors.leisure
    "beziehungen" -> AevumCategoryColors.relationships
    "haushalt" -> AevumCategoryColors.household
    "smartphone", "digital" -> AevumCategoryColors.smartphone
    "autofahren" -> AevumCategoryColors.driving
    else -> AevumCategoryColors.unknown
}
