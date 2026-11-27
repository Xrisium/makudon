package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.xrisium.makudon.domain.SourceRange

internal data class VisualLineEnd(val sourceOffset: Int)

/** The native caret and handle share the same upstream position before this display-only wedge. */
internal fun caretProjection(base: MarkdownProjection, source: String, offset: Int): MarkdownProjection {
    if (offset !in source.indices) return base
    val end = offset + Character.charCount(source.codePointAt(offset))
    val existing = base.changes.firstOrNull { it.range.start < end && it.range.end > offset }
    if (existing != null) {
        if (existing.range.start != offset || existing.text.isEmpty() || existing.text.contains('\uFFFC')) return base
        return MarkdownProjection(base.changes.map { if (it === existing) it.copy(text = "\u200B" + it.text) else it })
    }
    return MarkdownProjection((base.changes + DisplayChange(SourceRange(offset, end), "\u200B" + source.substring(offset, end))).sortedBy { it.range.start })
}

/** A soft wrap has two visual caret positions for one editing offset. */
internal fun visualLineEnd(result: TextLayoutResult, point: Offset, projection: MarkdownProjection): VisualLineEnd? {
    if (point.y < 0 || point.y >= result.size.height) return null
    val line = result.getLineForVerticalPosition(point.y)
    if (line >= result.lineCount - 1 || point.x < result.getLineRight(line) - 1f) return null
    val end = result.getLineEnd(line)
    if (end <= 0 || result.layoutInput.text[end - 1] == '\n' || result.getLineStart(line + 1) != end) return null
    // Bidirectional paragraphs have more than a single right-edge affinity.
    if (result.getParagraphDirection(end - 1) != androidx.compose.ui.text.style.ResolvedTextDirection.Ltr) return null
    return VisualLineEnd(projection.sourceCaretOffset(end))
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
            val target = currentHit(down.position)
            // Prepare the display before the native tap completes. Changing visual text
            // after onTap would make Foundation hide its insertion handle as a text edit.
            currentSelect(target)
            var moved = false
            var up: Offset? = null
            var duration = 0L
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!moved && ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1)) {
                    moved = true
                    currentSelect(null)
                }
                duration = change.uptimeMillis - down.uptimeMillis
                if (!change.pressed) up = change.position
            } while (up == null)
            if (!moved && duration < viewConfiguration.longPressTimeoutMillis) currentSelect(target)
            else currentSelect(null)
        }
    }
}
