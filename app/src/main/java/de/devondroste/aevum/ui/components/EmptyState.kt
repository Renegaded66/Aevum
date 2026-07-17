package de.devondroste.aevum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: String,
    message: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    AevumCard(
        modifier = modifier.fillMaxWidth(),
        variant = CardVariant.Filled
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AevumSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
        ) {
            icon?.let {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(AevumRadius.full))
                        .padding(AevumSpacing.md)
                ) {
                    it()
                }
            }
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = AevumSpacing.xs))
                
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 4
                )
            }
            
            if (actionLabel != null && onActionClick != null) {
                androidx.compose.material3.Button(
                    onClick = onActionClick,
                    modifier = Modifier.padding(top = AevumSpacing.md)
                ) {
                    Text(text = actionLabel, fontSize = 14.sp)
                }
            }
        }
    }
}