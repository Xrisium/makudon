@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.xrisium.makudon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.ColorTheme

val UiFont = FontFamily(Font(R.font.noto_sans_sc, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))))
val EditorFont = FontFamily(Font(R.font.noto_sans_sc, weight = FontWeight(450), variationSettings = FontVariation.Settings(FontVariation.weight(450))))
val EditorStyle = TextStyle(fontFamily = EditorFont, fontWeight = FontWeight(450), fontSize = 18.sp, lineHeight = 33.sp)

// Shared by the applied theme and the settings previews.
fun themePalette(theme: ColorTheme): List<Color> = when (theme) {
    ColorTheme.Ink -> listOf(0xFF1E201F, 0xFF272A28, 0xFFE0E3DD, 0xFFA4ADA3, 0xFF3B423C, 0xFF303731)
    ColorTheme.Cream -> listOf(0xFFF3E9DA, 0xFFFFF9F0, 0xFF39312A, 0xFF74675B, 0xFFD8C5AE, 0xFFE9D7C0)
    ColorTheme.Mist -> listOf(0xFFE3EDF1, 0xFFF7FCFD, 0xFF29373D, 0xFF5C7078, 0xFFC0D4DC, 0xFFCADDE5)
    ColorTheme.Sage -> listOf(0xFFE5ECE0, 0xFFF9FCF5, 0xFF303B2D, 0xFF60705B, 0xFFC3D3BC, 0xFFCFDEC8)
    else -> listOf(0xFFF7F7F5, 0xFFFFFFFF, 0xFF272827, 0xFF686B67, 0xFFE3E4DF, 0xFFEEEFEA)
}.map { Color(it) }

@Composable
fun MakudonTheme(theme: ColorTheme = ColorTheme.System, ready: Boolean = true, content: @Composable () -> Unit) {
    val selected = if (theme == ColorTheme.System) { if (isSystemInDarkTheme()) ColorTheme.Ink else ColorTheme.Paper } else theme
    val targetColors = remember(selected) {
        val values = themePalette(selected)
        val dark = selected == ColorTheme.Ink
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = values[2], onPrimary = values[0], primaryContainer = values[5], onPrimaryContainer = values[2],
            secondary = values[3], onSecondary = values[0], secondaryContainer = values[5], onSecondaryContainer = values[2],
            background = values[0], onBackground = values[2], surface = values[1], onSurface = values[2],
            surfaceVariant = values[5], onSurfaceVariant = values[3], outline = values[4], outlineVariant = values[4],
            surfaceContainer = values[1], surfaceContainerHigh = values[1], surfaceContainerHighest = values[5], surfaceContainerLow = values[1],
            surfaceTint = Color.Transparent, error = Color(if (dark) 0xFFF1ADA5 else 0xFFA43838),
            errorContainer = Color(if (dark) 0xFF422D2D else 0xFFF8E6E4), onErrorContainer = Color(if (dark) 0xFFF1ADA5 else 0xFFA43838),
        )
    }
    val colors = animatedColors(targetColors, ready)
    val lightBars = colors.background.luminance() > 0.45f
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }
    fun text(size: Int, line: Int) = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = line.sp)
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = text(28, 38), headlineMedium = text(26, 36), headlineSmall = text(22, 32),
        titleLarge = text(20, 30), titleMedium = text(17, 26), titleSmall = text(14, 22),
        bodyLarge = text(16, 26), bodyMedium = text(14, 23), bodySmall = text(12, 20),
        labelLarge = text(14, 22), labelMedium = text(12, 19), labelSmall = text(11, 18),
    ), content = content)
}

// One progress value keeps every color role on the same frame. Retargeting starts
// at the currently displayed palette, so rapid theme changes never jump backwards.
@Composable
private fun animatedColors(target: ColorScheme, ready: Boolean): ColorScheme {
    var initialized by remember { mutableStateOf(ready) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target, ready) {
        if (!initialized) {
            from = target; to = target
            progress.snapTo(1f)
            initialized = ready
            return@LaunchedEffect
        }
        if (target === to && progress.value == 1f) return@LaunchedEffect
        from = interpolateColors(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    return interpolateColors(from, to, progress.value)
}

private fun interpolateColors(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme {
    if (from === to || fraction >= 1f) return to
    if (fraction <= 0f) return from
    return to.copy(
        primary = lerp(from.primary, to.primary, fraction),
        onPrimary = lerp(from.onPrimary, to.onPrimary, fraction),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, fraction),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, fraction),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, fraction),
        secondary = lerp(from.secondary, to.secondary, fraction),
        onSecondary = lerp(from.onSecondary, to.onSecondary, fraction),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, fraction),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, fraction),
        tertiary = lerp(from.tertiary, to.tertiary, fraction),
        onTertiary = lerp(from.onTertiary, to.onTertiary, fraction),
        tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, fraction),
        background = lerp(from.background, to.background, fraction),
        onBackground = lerp(from.onBackground, to.onBackground, fraction),
        surface = lerp(from.surface, to.surface, fraction),
        onSurface = lerp(from.onSurface, to.onSurface, fraction),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, fraction),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, fraction),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, fraction),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, fraction),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, fraction),
        error = lerp(from.error, to.error, fraction),
        onError = lerp(from.onError, to.onError, fraction),
        errorContainer = lerp(from.errorContainer, to.errorContainer, fraction),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, fraction),
        outline = lerp(from.outline, to.outline, fraction),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, fraction),
        scrim = lerp(from.scrim, to.scrim, fraction),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, fraction),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, fraction),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, fraction),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, fraction),
    )
}
