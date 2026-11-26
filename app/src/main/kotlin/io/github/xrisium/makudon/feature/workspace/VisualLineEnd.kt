package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class VisualLineEnd(val sourceOffset: Int, val displayOffset: Int, val line: Int)

/** A soft wrap has two visual caret positions for one editing offset. */
internal fun visualLineEnd(result: TextLayoutResult, point: Offset, projection: MarkdownProjection): VisualLineEnd? {
    if (point.y < 0 || point.y >= result.size.height) return null
    val line = result.getLineForVerticalPosition(point.y)
    if (line >= result.lineCount - 1 || point.x < result.getLineRight(line) - 1f) return null
    val end = result.getLineEnd(line)
    if (end <= 0 || result.layoutInput.text[end - 1] == '\n' || result.getLineStart(line + 1) != end) return null
    // Bidirectional paragraphs have more than a single right-edge affinity.
    if (result.getParagraphDirection(end - 1) != androidx.compose.ui.text.style.ResolvedTextDirection.Ltr) return null
    return VisualLineEnd(projection.sourceCaretOffset(end), end, line)
}

internal fun Modifier.observeLineEndTap(
    enabled: Boolean,
    hit: (Offset) -> VisualLineEnd?,
    select: (VisualLineEnd?) -> Unit,
): Modifier = composed {
    val currentHit by rememberUpdatedState(hit)
    val currentSelect by rememberUpdatedState(select)
    pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        currentSelect(null)
        var moved = false
        var up: Offset? = null
        var duration = 0L
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1) moved = true
            duration = change.uptimeMillis - down.uptimeMillis
            if (!change.pressed) up = change.position
        } while (up == null)
        if (!moved && duration < viewConfiguration.longPressTimeoutMillis) up?.let { currentSelect(currentHit(it)) }
    }
    }
}

@Composable
internal fun BoxScope.VisualLineEndCursor(end: VisualLineEnd?, result: TextLayoutResult?, active: Boolean, scroll: () -> Int) {
    var on by remember(end, active) { mutableStateOf(true) }
    LaunchedEffect(end, active) { if (end != null && active) while (true) { delay(500); on = !on } }
    val color = MaterialTheme.colorScheme.primary
    if (end != null && result != null && active) Canvas(Modifier.matchParentSize().padding(horizontal = 24.dp)) {
        if (on && end.line < result.lineCount) {
            val native = result.getCursorRect((end.displayOffset - 1).coerceAtLeast(0))
            val x = result.getLineRight(end.line).coerceAtMost(size.width - 1.dp.toPx())
            drawLine(color, Offset(x, native.top - scroll()), Offset(x, native.bottom - scroll()), strokeWidth = 2.dp.toPx())
        }
    }
}
