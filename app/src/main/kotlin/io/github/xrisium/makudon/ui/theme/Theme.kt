@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.xrisium.makudon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

@Composable
fun MakudonTheme(theme: ColorTheme = ColorTheme.System, content: @Composable () -> Unit) {
    val selected = if (theme == ColorTheme.System) { if (isSystemInDarkTheme()) ColorTheme.Ink else ColorTheme.Paper } else theme
    val values = when (selected) {
        ColorTheme.Ink -> listOf(0xFF1E201F, 0xFF272A28, 0xFFE0E3DD, 0xFFA4ADA3, 0xFF3B423C, 0xFF303731)
        ColorTheme.Cream -> listOf(0xFFF5F1E8, 0xFFFBF8F1, 0xFF34312D, 0xFF6D685F, 0xFFDFD9CF, 0xFFECE7DC)
        ColorTheme.Mist -> listOf(0xFFF1F4F6, 0xFFFAFCFD, 0xFF2C3439, 0xFF5E6B73, 0xFFDCE2E6, 0xFFE6EBEF)
        ColorTheme.Sage -> listOf(0xFFF1F4EF, 0xFFFAFCF8, 0xFF30372F, 0xFF626E60, 0xFFDCE3D9, 0xFFE5EBE2)
        else -> listOf(0xFFF7F7F5, 0xFFFFFFFF, 0xFF272827, 0xFF686B67, 0xFFE3E4DF, 0xFFEEEFEA)
    }.map { Color(it) }
    val dark = selected == ColorTheme.Ink
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = values[2], onPrimary = values[0], primaryContainer = values[5], onPrimaryContainer = values[2],
        secondary = values[3], onSecondary = values[0], secondaryContainer = values[5], onSecondaryContainer = values[2],
        background = values[0], onBackground = values[2], surface = values[1], onSurface = values[2],
        surfaceVariant = values[5], onSurfaceVariant = values[3], outline = values[4], outlineVariant = values[4],
        surfaceContainer = values[1], surfaceContainerHigh = values[1], surfaceContainerHighest = values[5], surfaceContainerLow = values[1],
        surfaceTint = Color.Transparent, error = Color(if (dark) 0xFFF1ADA5 else 0xFFA43838),
        errorContainer = Color(if (dark) 0xFF422D2D else 0xFFF8E6E4), onErrorContainer = Color(if (dark) 0xFFF1ADA5 else 0xFFA43838),
    )
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
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
