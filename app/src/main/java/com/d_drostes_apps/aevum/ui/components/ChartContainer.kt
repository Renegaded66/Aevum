package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

data class ChartLegendItem(
    val label: String,
    val color: Color,
    val value: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartContainer(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    legendItems: List<ChartLegendItem> = emptyList(),
    showLegend: Boolean = true,
    content: @Composable () -> Unit
) {
    AevumCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle != null) {
                        Spacer(Modifier.height(AevumSpacing.xs))
                        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(AevumSpacing.lg))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
            if (showLegend && legendItems.isNotEmpty()) {
                Spacer(Modifier.height(AevumSpacing.lg))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    legendItems.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(item.color, CircleShape))
                            Text(
                                text = " ${item.label} ${item.value}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
