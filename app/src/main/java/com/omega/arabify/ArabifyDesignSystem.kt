package com.omega.arabify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ARABIFY design system v2: deep-space navy with cyan/violet neon accents and
 * bundled Cairo type. Gamer energy, professional restraint: one accent family,
 * glow reserved for primary actions, hierarchy carried by type scale.
 */
internal object ArabifyPalette {
    val canvas = Color(0xFF070B16)
    val surface = Color(0xFF0E1526)
    val surfaceRaised = Color(0xFF141C33)
    val surfaceAccent = Color(0xFF1A2440)
    val surfaceElevated = Color(0xFF1F2A4A)
    val outline = Color(0xFF28334F)
    val outlineNeon = Color(0xFF2DE0D0)
    val primary = Color(0xFF2DE0D0)
    val primaryDeep = Color(0xFF0E9C93)
    val violet = Color(0xFF8B5CF6)
    val violetSoft = Color(0xFFA78BFA)
    val accent = Color(0xFFFFB454)
    val tertiary = Color(0xFF5EC8F8)
    val textPrimary = Color(0xFFF2F5FF)
    val textSecondary = Color(0xFFA9B4D4)
    val textMuted = Color(0xFF77839F)
    val textDisabled = Color(0xFF535D78)
    val textBody = Color(0xFFC6CFE8)
    val textOnCard = Color(0xFFE4E9FA)
    val success = Color(0xFF4ADE80)
    val warning = Color(0xFFFFB454)
    val warningText = Color(0xFFF5C97E)
    val danger = Color(0xFFFF6B6B)
    val info = Color(0xFF5EC8F8)
}

/** Signature gradients. */
internal object ArabifyGradients {
    val neon = Brush.horizontalGradient(listOf(ArabifyPalette.primary, ArabifyPalette.violet))
    val neonSoft = Brush.horizontalGradient(
        listOf(ArabifyPalette.primary.copy(alpha = 0.22f), ArabifyPalette.violet.copy(alpha = 0.22f))
    )
    val hero = Brush.linearGradient(
        listOf(Color(0xFF0D1B3A), Color(0xFF101830), Color(0xFF160F2E))
    )
    val wordmark = Brush.horizontalGradient(
        listOf(ArabifyPalette.primary, Color(0xFF7CD4FC), ArabifyPalette.violetSoft)
    )
}

/** Cairo (variable, OFL) bundled for every app string; Latin falls back to it too. */
internal val ArabifyFontFamily = FontFamily(
    Font(R.font.cairo_var, weight = FontWeight.Normal),
    Font(R.font.cairo_var, weight = FontWeight.SemiBold),
    Font(R.font.cairo_var, weight = FontWeight.Bold),
    Font(R.font.cairo_var, weight = FontWeight.Black),
)

internal object ArabifySpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xLarge = 24.dp
    val xxLarge = 32.dp
}

internal object ArabifyCorners {
    val control = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(20.dp)
    val feature = RoundedCornerShape(28.dp)
}

@Composable
internal fun ArabifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ArabifyPalette.primary,
            onPrimary = Color(0xFF03211E),
            primaryContainer = Color(0xFF0E3A3F),
            onPrimaryContainer = Color(0xFFBFF5EE),
            secondary = ArabifyPalette.violetSoft,
            onSecondary = Color(0xFF1D0B45),
            secondaryContainer = Color(0xFF35206B),
            onSecondaryContainer = Color(0xFFE7DCFF),
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
            displayLarge = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 30.sp, fontWeight = FontWeight.Black),
            headlineMedium = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
            titleLarge = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
            titleMedium = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
            bodyLarge = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 14.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 12.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = ArabifyFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

/** Full-width neon call to action: the one glowing element per screen section. */
@Composable
internal fun ArabifyCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = ArabifyCorners.control
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFF04110F),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = ArabifyPalette.textDisabled,
        ),
        contentPadding = PaddingValues(),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) ArabifyGradients.neon else Brush.linearGradient(listOf(ArabifyPalette.surfaceElevated, ArabifyPalette.surfaceElevated)))
            .border(1.dp, if (enabled) ArabifyPalette.primary.copy(alpha = 0.7f) else ArabifyPalette.outline, shape),
    ) {
        Text(text, fontFamily = ArabifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
