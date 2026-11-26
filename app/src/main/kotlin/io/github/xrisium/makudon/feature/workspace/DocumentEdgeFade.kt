package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/** Background gradients reproduce an alpha fade without a viewport-sized offscreen layer. */
@Composable
internal fun BoxScope.DocumentEdgeFade(
    model: WorkspaceViewModel,
    fromStart: () -> Float,
    fromEnd: () -> Float,
) {
    if (model.ui.workspace?.fadeDocumentEdges == false) return
    val background = MaterialTheme.colorScheme.background
    val currentStart by rememberUpdatedState(fromStart)
    val currentEnd by rememberUpdatedState(fromEnd)
    Box(Modifier.matchParentSize().drawWithCache {
        val length = minOf(24.dp.toPx(), size.height / 2)
        val transparent = background.copy(alpha = 0f)
        val topBrush = Brush.verticalGradient(listOf(background, transparent), 0f, length)
        val bottomBrush = Brush.verticalGradient(listOf(transparent, background), 0f, length)
        onDrawBehind {
            if (length <= 0f) return@onDrawBehind
            // Pixel changes invalidate only drawing, never the full editor's composition or layout.
            val top = currentStart().coerceIn(0f, length)
            val bottom = currentEnd().coerceIn(0f, length)
            if (top > 0f) {
                withTransform({ scale(1f, top / length, Offset.Zero) }) {
                    drawRect(topBrush, size = Size(size.width, length))
                }
            }
            if (bottom > 0f) {
                withTransform({ translate(0f, size.height - bottom); scale(1f, bottom / length, Offset.Zero) }) {
                    drawRect(bottomBrush, size = Size(size.width, length))
                }
            }
        }
    })
}
