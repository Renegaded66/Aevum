package de.devondroste.aevum.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AevumSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val xxxl = 64.dp
}

object AevumRadius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val full = 9999.dp
}

object AevumCategoryColors {
    val work = Color(0xFF6366F1)
    val sleep = Color(0xFF334155)
    val sport = Color(0xFF22C55E)
    val learning = Color(0xFF0EA5E9)
    val leisure = Color(0xFFF97316)
    val relationships = Color(0xFFEC4899)
    val household = Color(0xFFA855F7)
    val smartphone = Color(0xFF64748B)
    val driving = Color(0xFFF59E0B)
    val unknown = Color(0xFF94A3B8)
}

object AevumChartColors {
    val palette = listOf(
        Color(0xFF8B7CFF), // primary
        Color(0xFF2DD4BF), // secondary
        Color(0xFFFBBF24), // tertiary
        Color(0xFF6366F1), // work
        Color(0xFF22C55E), // sport
        Color(0xFFF97316), // leisure
        Color(0xFFEC4899), // relationships
        Color(0xFFA855F7), // household
    )
}

object AevumElevation {
    val card = 1.dp
    val elevated = 4.dp
    val modal = 8.dp
}

object AevumTypeScale {
    // Numbers - Monospace with tabular numerals
    val numbersLarge = 48.sp
    val numbersMedium = 32.sp
    val numbersSmall = 20.sp
}