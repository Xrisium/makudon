package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun RecentOverviewSwitch(checked: Boolean, enabled: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    // One progress value keeps thumb travel, shrinking and color changes together in both directions.
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(240, easing = FastOutSlowInEasing), label = "recent-overview-switch")
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        Modifier.size(52.dp, 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
    ) {
        val alpha = if (enabled) 1f else 0.38f
        val top = (size.height - 32.dp.toPx()) / 2f
        val trackSize = Size(size.width, 32.dp.toPx())
        val radius = CornerRadius(16.dp.toPx())
        drawRoundRect(lerp(colors.surfaceContainerHighest, colors.primary, progress).copy(alpha = alpha), Offset(0f, top), trackSize, radius)
        val border = 1.dp.toPx()
        drawRoundRect(
            lerp(colors.outline, colors.primary, progress).copy(alpha = alpha),
            Offset(border, top + border), Size(size.width - border * 2, trackSize.height - border * 2),
            CornerRadius(15.dp.toPx()), style = Stroke(2.dp.toPx()),
        )
        val x = (16f + 20f * progress).dp.toPx()
        drawCircle(
            lerp(colors.outline, colors.onPrimary, progress).copy(alpha = alpha),
            radius = (8f + 4f * progress).dp.toPx(),
            center = Offset(if (rtl) size.width - x else x, size.height / 2f),
        )
    }
}
