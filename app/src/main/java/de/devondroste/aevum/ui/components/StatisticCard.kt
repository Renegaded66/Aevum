package de.devondroste.aevum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.theme.AevumSpacing

data class Trend(
    val percent: Float,
    val isPositive: Boolean,
    val label: String = "vs. gestern"
)

@Composable
fun StatisticCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String? = null,
    icon: String? = null,
    trend: Trend? = null,
    subtitle: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null
) {
    AevumCard(modifier = modifier, variant = CardVariant.Filled, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    Text(text = icon, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(AevumSpacing.xs))
                }
                Text(
                    text = label.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(AevumSpacing.xs))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(
                    text = value,
                    fontSize = 30.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor,
                    fontFamily = FontFamily.Monospace
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
            if (trend != null) {
                Spacer(modifier = Modifier.height(AevumSpacing.xs))
                val trendColor = if (trend.isPositive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                Text(
                    text = "${if (trend.isPositive) "↗" else "↘"} ${"%.1f".format(trend.percent)}% ${trend.label}",
                    fontSize = 11.sp,
                    color = trendColor,
                    maxLines = 1
                )
            } else if (subtitle != null) {
                Spacer(modifier = Modifier.height(AevumSpacing.xs))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}
