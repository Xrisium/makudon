package io.github.xrisium.makudon.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.xrisium.makudon.domain.MarkdownDocument
import io.github.xrisium.makudon.domain.TextSelection

internal object ReadingBodyCopyKey

/** Only the body replaces Copy; read-only table cells retain their own native selection callback. */
internal fun Modifier.readingActions(reading: Boolean, model: WorkspaceViewModel, document: MarkdownDocument,
    layout: () -> TextLayoutResult?, projection: () -> MarkdownProjection): Modifier = composed {
    if (!reading) return@composed Modifier
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val currentDocument by rememberUpdatedState(document)
    val currentLayout by rememberUpdatedState(layout)
    val currentProjection by rememberUpdatedState(projection)
    val field = model.textState ?: return@composed Modifier
    Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.CopyKey }
        .appendTextContextMenuComponents {
            if (!field.selection.collapsed) item(ReadingBodyCopyKey, context.getString(android.R.string.copy)) {
                val source = field.text.toString()
                val doc = currentDocument.takeIf { it.source == source } ?: provisionalDocument(null, source)
                val selection = TextSelection(field.selection.start, field.selection.end)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", readingCopy(doc, selection)))
                field.edit { this.selection = TextRange(selection.max) }
                close()
            }
        }.observeReadingTap(true, { field.selection.collapsed }) { position ->
            val result = currentLayout()
            val point = position.copy(y = position.y + model.editorScroll.value)
            if (result != null && point.y >= result.size.height) model.immersive = !model.immersive
            else result?.characterAt(point)?.let { displayOffset ->
                val at = currentProjection().sourceOffset(displayOffset)
                currentDocument.elements.firstOrNull { it.kind == "Link" && at in it.range.start until it.range.end }?.let { uri.openUri(it.destination) }
            }
        }
}

/** A link owns its glyph bounds, including the trailing half of its last character. */
internal fun TextLayoutResult.characterAt(point: Offset): Int? {
    val at = getOffsetForPosition(point)
    if (at < layoutInput.text.length && getBoundingBox(at).contains(point)) return at
    if (at == 0) return null
    val previous = at - Character.charCount(layoutInput.text.text.codePointBefore(at))
    return previous.takeIf { getBoundingBox(it).contains(point) }
}

/** Observe a short tap without consuming native selection, scrolling or handle gestures. */
internal fun Modifier.observeReadingTap(enabled: Boolean, collapsed: () -> Boolean, tap: (Offset) -> Unit): Modifier = composed {
    val currentCollapsed by rememberUpdatedState(collapsed)
    val currentTap by rememberUpdatedState(tap)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val wasCollapsed = currentCollapsed()
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1) moved = true
                if (!change.pressed) {
                    if (wasCollapsed && !moved && currentCollapsed() && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) currentTap(change.position)
                    break
                }
            }
        }
    }
}
