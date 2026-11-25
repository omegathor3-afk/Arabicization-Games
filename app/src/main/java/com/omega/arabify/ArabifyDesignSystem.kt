package com.omega.arabify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ARABIFY design system v3: League of Legends client look - hextech deep
 * blue-black canvas, brushed-gold borders and actions, cream inscription
 * text, sharp cut-corner frames. Style match only: colors/shapes in the LoL
 * spirit; no Riot art, icons, or fonts are copied (Cairo stays our typeface).
 */
internal object ArabifyPalette {
    val canvas = Color(0xFF010A13)
    val surface = Color(0xFF0D1420)
    val surfaceRaised = Color(0xFF121A29)
    val surfaceAccent = Color(0xFF16202F)
    val surfaceElevated = Color(0xFF1B2636)
    val outline = Color(0xFF785A28)
    val outlineNeon = Color(0xFFC89B3C)
    val primary = Color(0xFFC89B3C)
    val primaryDeep = Color(0xFF785A28)
    val violet = Color(0xFF0AC8B9)
    val violetSoft = Color(0xFF4FD8CC)
    val accent = Color(0xFFF0C868)
    val tertiary = Color(0xFF0AC8B9)
    val textPrimary = Color(0xFFF0E6D2)
    val textSecondary = Color(0xFFA09B8C)
    val textMuted = Color(0xFF73716C)
    val textDisabled = Color(0xFF3C3C41)
    val textBody = Color(0xFFCDBE91)
    val textOnCard = Color(0xFFF0E6D2)
    val success = Color(0xFF4ADE80)
    val warning = Color(0xFFF0C868)
    val warningText = Color(0xFFF5C97E)
    val danger = Color(0xFFD13639)
    val info = Color(0xFF0AC8B9)
}

/** Signature gradients. */
internal object ArabifyGradients {
    val neon = Brush.linearGradient(
        listOf(Color(0xFF785A28), Color(0xFFC89B3C), Color(0xFFF0E6D2))
    )
    val neonSoft = Brush.horizontalGradient(
        listOf(ArabifyPalette.primary.copy(alpha = 0.22f), ArabifyPalette.violet.copy(alpha = 0.22f))
    )
    val hero = Brush.linearGradient(
        listOf(Color(0xFF010A13), Color(0xFF0D1420), Color(0xFF16202F))
    )
    val wordmark = Brush.horizontalGradient(
        listOf(Color(0xFF785A28), Color(0xFFC89B3C), Color(0xFFF0E6D2))
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
    val control = CutCornerShape(12.dp)
    val card = CutCornerShape(18.dp)
    val feature = CutCornerShape(24.dp)
}

@Composable
internal fun ArabifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ArabifyPalette.primary,
            onPrimary = Color(0xFF241C0E),
            primaryContainer = Color(0xFF3A2F17),
            onPrimaryContainer = Color(0xFFF0E6D2),
            secondary = ArabifyPalette.violetSoft,
            onSecondary = Color(0xFF01241F),
            secondaryContainer = Color(0xFF0E3A38),
            onSecondaryContainer = Color(0xFFBFF5EE),
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
            small = CutCornerShape(8.dp),
            medium = CutCornerShape(14.dp),
            large = CutCornerShape(18.dp)
        ),
        content = content
    )
}


/** Thin gold rule with a center diamond - the LoL client divider. */
@Composable
internal fun GoldDivider(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, ArabifyPalette.primary))))
        Box(Modifier.padding(horizontal = 6.dp).size(5.dp).rotate(45f).background(ArabifyPalette.primary))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(ArabifyPalette.primary, Color.Transparent))))
    }
}

/**
 * LoL-client frame: cut corners, dark-gold outer border with a bright gold
 * hairline inset - the hextech panel signature. Style match only.
 */
@Composable
internal fun HextechPanel(
    modifier: Modifier = Modifier,
    containerColor: Color = ArabifyPalette.surface,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ArabifyCorners.card)
            .background(containerColor)
            .border(1.dp, ArabifyPalette.primaryDeep, ArabifyCorners.card)
            .padding(4.dp)
            .border(1.dp, ArabifyPalette.outlineNeon.copy(alpha = 0.55f), CutCornerShape(14.dp))
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/** Gold diamond + label + fading rule - LoL section headers. */
@Composable
internal fun HextechSectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(5.dp).rotate(45f).background(ArabifyPalette.primary))
        Text(title, color = ArabifyPalette.primary, fontFamily = ArabifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(ArabifyPalette.primaryDeep, Color.Transparent))))
    }
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
            contentColor = Color(0xFF241C0E),
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
