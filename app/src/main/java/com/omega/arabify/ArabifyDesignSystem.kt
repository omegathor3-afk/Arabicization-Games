package com.omega.arabify

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colors shared by every ARABIFY screen and state. */
internal object ArabifyPalette {
    val canvas = Color(0xFF080D0C)
    val surface = Color(0xFF111A17)
    val surfaceRaised = Color(0xFF17231E)
    val surfaceAccent = Color(0xFF1B2823)
    val surfaceElevated = Color(0xFF202B26)
    val outline = Color(0xFF2A3B33)
    val primary = Color(0xFF9AD8BC)
    val accent = Color(0xFFD6A66D)
    val tertiary = Color(0xFF78BFA7)
    val textPrimary = Color(0xFFF0F1E8)
    val textSecondary = Color(0xFFB8C6BE)
    val textMuted = Color(0xFF9BAEA5)
    val textDisabled = Color(0xFF78877F)
    val textBody = Color(0xFFD4DDD6)
    val textOnCard = Color(0xFFE8EEE9)
    val success = Color(0xFFA8D9C0)
    val warning = Color(0xFFFFC46B)
    val warningText = Color(0xFFE2BB80)
    val danger = Color(0xFFFF9B91)
    val info = Color(0xFF9BDCF2)
}

internal object ArabifySpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val xxLarge = 32.dp
}

internal object ArabifyCorners {
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(20.dp)
    val feature = RoundedCornerShape(28.dp)
}

@Composable
internal fun ArabifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ArabifyPalette.primary,
            onPrimary = Color(0xFF09271B),
            primaryContainer = Color(0xFF173D2D),
            onPrimaryContainer = Color(0xFFD0F3E0),
            secondary = ArabifyPalette.accent,
            onSecondary = Color(0xFF2E1A06),
            secondaryContainer = Color(0xFF4A321A),
            onSecondaryContainer = Color(0xFFFFE0B8),
            tertiary = ArabifyPalette.tertiary,
            background = ArabifyPalette.canvas,
            onBackground = ArabifyPalette.textPrimary,
            surface = ArabifyPalette.surface,
            onSurface = ArabifyPalette.textPrimary,
            surfaceVariant = ArabifyPalette.surfaceElevated,
            onSurfaceVariant = ArabifyPalette.textSecondary,
            outline = ArabifyPalette.outline,
            error = ArabifyPalette.danger
        ),
        typography = Typography(
            displayLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
            headlineMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp),
            titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp),
            titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
            bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
