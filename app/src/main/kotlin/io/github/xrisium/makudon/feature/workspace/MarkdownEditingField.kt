package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownEditingField(model: WorkspaceViewModel, markdown: Boolean, bodyStyle: TextStyle, editable: Boolean, findOpen: Boolean, modifier: Modifier, outlineJump: OutlineJump?, onOutlineJumped: () -> Unit) {
    val field = model.textState ?: return
    val colors = MaterialTheme.colorScheme
    val source = field.text.toString()
    val typeset = markdown && model.ui.editorMode == EditorMode.Typeset
    var focused by remember { mutableStateOf(false) }
    var layout by remember(field) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var visibleSourceStart by remember(field) { mutableIntStateOf(0) }
    var visibleSourceEnd by remember(field) { mutableIntStateOf(1024) }
    val requester = remember { FocusRequester() }
    val editorInteractions = remember(field) { MutableInteractionSource() }
    val density = LocalDensity.current
    val displayDocument = model.tableBoundaryDocument ?: model.markdownDocument
    val parsed = remember(displayDocument, source) { provisionalDocument(displayDocument, source) }
    val sourceSelection = field.selection.let { TextSelection(it.start, it.end) }
    val composition = field.composition?.let { SourceRange(it.start, it.end) }
    val active = if (focused || findOpen) sourceSelection else null
    val tables = remember(typeset, parsed) { if (typeset) parsed.elements.filter { it.kind == "TableBlock" } else emptyList() }
    val imageParagraphs = remember(typeset, parsed, active, composition) { if (typeset) ImageReferences.standalone(parsed).filter { node ->
        active?.let { node.range.intersects(it.min, it.max) } != true && composition?.let { node.range.intersects(it.start, it.end) } != true
    } else emptyList() }
    val projection = remember(typeset, parsed, active, composition, tables, imageParagraphs) {
        if (typeset) projection(parsed, active, composition, tables + imageParagraphs) else MarkdownProjection(emptyList())
    }
    val textMeasurer = rememberTextMeasurer()
    val listIndents = remember(projection, textMeasurer, bodyStyle, density) {
        projection.changes.filter { it.text.trimStart().let { marker -> marker.startsWith("• ") || marker.startsWith("☐ ") || marker.startsWith("☑ ") } }
            .associate { change -> change.range.start to with(density) { textMeasurer.measure(change.text, bodyStyle).size.width.toSp().value } }
    }
    val imageIndents = remember(imageParagraphs, source, projection, textMeasurer, bodyStyle) {
        imageParagraphs.associate { node ->
            val start = source.lastIndexOf('\n', node.range.start - 1) + 1
            var prefix = source.substring(start, node.range.start)
            projection.changes.filter { it.range.start >= start && it.range.end <= node.range.start }.asReversed().forEach { change ->
                prefix = prefix.replaceRange(change.range.start - start, change.range.end - start, change.text)
            }
            node.range.start to if (prefix.isEmpty()) 0 else textMeasurer.measure(prefix, bodyStyle).size.width
        }
    }
    val tableLayouts = tables.associate { table -> table.range.start to key(table.range.start) { rememberTableLayout(table, parsed, bodyStyle) } }
    val imageHeights = imageParagraphs.associate { node ->
        val info = ImageReferences.key(node.children.single().destination)?.let(model::imageEntry)?.image
        val width = (viewportWidth - with(density) { 48.dp.roundToPx() } - imageIndents.getValue(node.range.start)).coerceAtLeast(1)
        node.range.start to if (info == null) with(density) { 72.dp.toPx() } else minOf(width * info.displayHeight.toFloat() / info.displayWidth, with(density) { 320.dp.toPx() })
    }
    val tableHeights = tableLayouts.mapValues { with(density) { it.value.height.toSp().value } } + imageHeights.mapValues { with(density) { it.value.toSp().value } }
    // Keep styles ahead of the viewport and around the selection without making
    // Android scan thousands of off-screen spans on every draw.
    fun window(start: Int, end: Int) = SourceRange((start - 256).coerceAtLeast(0), (end + 256).coerceAtMost(source.length))
    val windows = listOf(window(visibleSourceStart.coerceAtMost(source.length), visibleSourceEnd.coerceAtMost(source.length)), window(sourceSelection.min, sourceSelection.min), window(sourceSelection.max, sourceSelection.max))
    val output = remember(typeset, parsed, projection, bodyStyle, colors.primary, colors.surfaceVariant, tableHeights, listIndents, windows) {
        if (typeset) liveTransformation(parsed, projection, bodyStyle, colors.primary, colors.surfaceVariant, tableHeights, listIndents, windows) else null
    }
    val currentOutput by rememberUpdatedState(output)
    var trailing by remember(field, source, model.ui.editorMode) { mutableStateOf<VisualLineEnd?>(null) }
    val currentLayout by rememberUpdatedState(layout)
    val currentProjection by rememberUpdatedState(projection)
    val trailingMatchesLayout = trailing?.let { end -> layout?.let { result ->
        end.line < result.lineCount && result.getLineEnd(end.line) == end.displayOffset && projection.offset(end.sourceOffset) == end.displayOffset
    } } == true
    val trailingActive = focused && editable && trailingMatchesLayout && field.selection.collapsed && trailing?.sourceOffset == field.selection.start && field.composition == null
    LaunchedEffect(field.selection, focused, field.composition) {
        if (!field.selection.collapsed || field.selection.start != trailing?.sourceOffset || !focused || field.composition != null) trailing = null
    }
    // AST/style refreshes must not restart the native IME's composing session.
    val stableOutput = remember(field, typeset) {
        if (typeset) OutputTransformation { currentOutput?.let { with(it) { transformOutput() } } } else null
    }
    val input = remember(model, field, markdown) { InputTransformation {
        val before = originalText.toString()
        // Selection gestures also run this transformation. A read-only field must still
        // accept selection-only updates; revertAllChanges would collapse them as well.
        if (asCharSequence().toString() == before) return@InputTransformation
        if (model.ui.busy || model.ui.transitioning || model.ui.storageBlocked || model.ui.page != Page.Editor || model.ui.editorMode == EditorMode.Reading) { revertAllChanges(); return@InputTransformation }
        val beforeSelection = TextSelection(originalSelection.start, originalSelection.end)
        val raw = asCharSequence().toString()
        val normalized = TextRules.normalize(raw)
        try {
            TextRules.bytes(normalized)
            if (normalized != raw) for (index in raw.lastIndex downTo 0) {
                if (raw[index] == '\r') replace(index, index + 1, if (index < raw.lastIndex && raw[index + 1] == '\n') "" else "\n")
            }
            val doc = model.markdownDocument?.takeIf { it.source == before }
            val boundaryDocument = model.tableBoundaryDocument ?: doc
            if (model.ui.editorMode == EditorMode.Typeset && markdown && boundaryDocument != null) {
                model.tableBoundaryEdit(boundaryDocument, beforeSelection, asCharSequence().toString())?.let {
                    val proposed = asCharSequence().toString().replaceRange(it.range.start, it.range.end, it.replacement)
                    TextRules.bytes(proposed)
                    model.trackTableBoundary(boundaryDocument, beforeSelection)
                }
            }
            if (model.ui.editorMode == EditorMode.Typeset && markdown && model.tableBoundaryDocument == null && field.composition == null && beforeSelection.min == beforeSelection.max && doc != null && !doc.protected(beforeSelection.min, beforeSelection.max)) {
                MarkdownEdits.structural(before, asCharSequence().toString(), TextSelection(selection.start, selection.end))?.let {
                    TextRules.bytes(it.text); replace(0, length, it.text); selection = TextRange(it.selection.start, it.selection.end)
                }
            }
            model.recordInput(before, asCharSequence().toString(), beforeSelection, TextSelection(selection.start, selection.end))
        } catch (e: WorkspaceException) { revertAllChanges(); model.inputRejected(e.reason) }
    } }
    val lineHeight = with(density) { bodyStyle.lineHeight.toPx() }
    // Capture before the layout changes; recording is gated until restoration completes.
    val restoreAnchor = remember(field, model.ui.editorMode, bodyStyle, viewportWidth) {
        model.editorAnchor to (model.editorAnchorOffset * lineHeight / model.viewportLineHeight).roundToInt()
    }
    var restoring by remember(field, model.ui.editorMode, bodyStyle, viewportWidth) { mutableStateOf(true) }
    LaunchedEffect(restoreAnchor, model.ui.editorMode, bodyStyle, viewportWidth) {
        withFrameNanos { }
        val result = snapshotFlow { layout }.first { it != null && it.layoutInput.style.fontSize == bodyStyle.fontSize && it.layoutInput.style.lineHeight == bodyStyle.lineHeight } ?: return@LaunchedEffect
        val at = projection.offset(restoreAnchor.first).coerceIn(0, result.layoutInput.text.length)
        model.editorScroll.scrollTo((result.getLineTop(result.getLineForOffset(at)).roundToInt().toLong() + restoreAnchor.second).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        restoring = false
    }
    LaunchedEffect(outlineJump, layout, projection) {
        val jump = outlineJump ?: return@LaunchedEffect
        if (jump.source != field.text.toString()) { onOutlineJumped(); return@LaunchedEffect }
        val result = layout ?: return@LaunchedEffect
        val at = projection.offset(jump.offset).coerceIn(0, result.layoutInput.text.length)
        model.editorScroll.scrollTo(result.getLineTop(result.getLineForOffset(at)).roundToInt())
        onOutlineJumped()
    }
    LaunchedEffect(layout, projection, parsed, viewportHeight, restoring) {
        if (restoring) return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        snapshotFlow { model.editorScroll.value }.collect { scroll ->
            val visible = result.getOffsetForPosition(Offset(0f, scroll.toFloat()))
            val original = projection.sourceOffset(visible).coerceIn(0, source.length)
            val bottom = result.getOffsetForPosition(Offset(0f, (scroll + viewportHeight).toFloat()))
            visibleSourceStart = (original / 512) * 512
            visibleSourceEnd = (((projection.sourceOffset(bottom).coerceIn(original, source.length) / 512) + 1) * 512).coerceAtMost(source.length)
            val block = parsed.root.children.lastOrNull { it.range.start <= original }
            val start = if (markdown) block?.range?.start ?: original else result.getLineStart(result.getLineForOffset(visible))
            val at = projection.offset(start).coerceIn(0, result.layoutInput.text.length)
            model.recordViewport(start, (scroll - result.getLineTop(result.getLineForOffset(at)).roundToInt()).coerceAtLeast(0), lineHeight, reading = false)
        }
    }
    val description = stringResource(R.string.edit_body)
    Box(modifier.clipToBounds().onSizeChanged { viewportHeight = it.height; viewportWidth = it.width }) {
        BasicTextField(state = field, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).focusRequester(requester)
            .observeLineEndTap(editable, hit = { point -> currentLayout?.let { visualLineEnd(it, point.copy(y = point.y + model.editorScroll.value), currentProjection) } }, select = { end ->
                // A native double-tap selection takes precedence over visual caret affinity.
                trailing = end.takeIf { field.selection.collapsed }
                if (trailing != null) field.edit { selection = TextRange(checkNotNull(trailing).sourceOffset.coerceIn(0, length)) }
            })
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (editable && it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key in listOf(Key.Z, Key.Y)) { model.undo(it.key == Key.Y || it.isShiftPressed); true } else false
            }.testTag(if (model.ui.editorMode == EditorMode.Reading) "reading-view" else if (typeset) "typeset-editor" else "source-editor").semantics { contentDescription = description },
            readOnly = !editable, textStyle = bodyStyle.copy(color = colors.onBackground), cursorBrush = SolidColor(if (trailingActive) androidx.compose.ui.graphics.Color.Transparent else colors.primary), inputTransformation = input,
            outputTransformation = stableOutput, scrollState = model.editorScroll,
            interactionSource = editorInteractions,
            onTextLayout = { get -> layout = get() },
            decorator = TextFieldDecorator { inner -> Box { if (source.isEmpty()) Text(stringResource(R.string.editor_placeholder), style = bodyStyle, color = colors.onSurfaceVariant); inner() } })
        if (typeset && layout != null) MarkdownEditingOverlays(model, parsed, projection, checkNotNull(layout), source, active,
            imageParagraphs, imageIndents, imageHeights, tables, tableLayouts, bodyStyle, editable, viewportHeight, viewportWidth)
        VisualLineEndCursor(trailing, layout, trailingActive) { model.editorScroll.value }
        DocumentEdgeFade(model,
            fromStart = { if (model.editorScroll.maxValue == Int.MAX_VALUE) 0f else model.editorScroll.value.toFloat() },
            fromEnd = { if (model.editorScroll.maxValue == Int.MAX_VALUE) 0f else (model.editorScroll.maxValue - model.editorScroll.value).toFloat() })
        EditingNavigation(model, viewportHeight, editorInteractions)
    }
}

/** Only overlays and navigation observe scroll pixels; the continuous input owns its own placement. */
@Composable
private fun MarkdownEditingOverlays(
    model: WorkspaceViewModel, parsed: MarkdownDocument, projection: MarkdownProjection, result: TextLayoutResult,
    source: String, active: TextSelection?, imageParagraphs: List<MdElement>, imageIndents: Map<Int, Int>,
    imageHeights: Map<Int, Float>, tables: List<MdElement>, tableLayouts: Map<Int, TableLayout>,
    bodyStyle: TextStyle, editable: Boolean, viewportHeight: Int, viewportWidth: Int,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val taskDescription = stringResource(R.string.md_toggle_task)
    val editToken = model.editToken()
    if (result.layoutInput.text.isNotEmpty()) {
        for (paragraph in imageParagraphs) {
            val offset = projection.offset(paragraph.range.start)
            if (offset < result.layoutInput.text.length && result.layoutInput.text[offset] == '\uFFFC') {
                val top = result.getLineTop(result.getLineForOffset(offset)) - model.editorScroll.value
                val height = imageHeights.getValue(paragraph.range.start)
                if (top + height >= 0 && top < viewportHeight) {
                    val indent = imageIndents.getValue(paragraph.range.start)
                    val width = (viewportWidth - with(density) { 48.dp.roundToPx() } - indent).coerceAtLeast(1)
                    Box(Modifier.offset { IntOffset(with(density) { 24.dp.roundToPx() } + indent, top.roundToInt()) }
                        .width(with(density) { width.toDp() }).height(with(density) { height.toDp() }).background(colors.background)) {
                        MarkdownImage(model, paragraph.children.single(), Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (table in tables) {
            val offset = projection.offset(table.range.start)
            if (offset < result.layoutInput.text.length && result.layoutInput.text[offset] == '\uFFFC') {
                val top = result.getLineTop(result.getLineForOffset(offset)) - model.editorScroll.value
                val tableLayout = tableLayouts.getValue(table.range.start)
                val heightPx = tableLayout.height.toFloat()
                if (top + heightPx >= 0 && top < viewportHeight) {
                    Column(Modifier.offset { IntOffset(with(density) { 24.dp.roundToPx() }, top.roundToInt()) }
                        .fillMaxWidth().padding(end = 48.dp).wrapContentHeight(align = androidx.compose.ui.Alignment.Top, unbounded = true)
                        .height(with(density) { heightPx.toDp() }).background(colors.background)) {
                        MarkdownEditableTable(model, parsed, bodyStyle, editable, Modifier.fillMaxSize(), tableLayout,
                            -top - 200f, viewportHeight - top + 200f)
                    }
                }
            }
        }
        // Cache source targets, then hit-test only the visible lines, not every task in the document.
        val tasks = remember(source, parsed, projection, active) {
            Regex("(?m)^( *)(?:[-+*]|\\d+[.)]) \\[([ xX])] ").findAll(source).mapNotNull { task ->
                val range = SourceRange(task.range.first, task.range.last + 1)
                if (active?.let { range.intersects(it.min, it.max) } == true || parsed.protected(range.start, range.end)) null
                else TaskTarget(projection.offset(task.range.first + task.groupValues[1].length),
                    checkNotNull(task.groups[2]).range.first, task.groupValues[2].equals("x", true))
            }.toList()
        }
        val scroll = model.editorScroll.value
        val firstLine = (result.getLineForVerticalPosition(scroll.toFloat()) - 1).coerceAtLeast(0)
        val lastLine = (result.getLineForVerticalPosition((scroll + viewportHeight).toFloat()) + 1).coerceAtMost(result.lineCount - 1)
        val firstOffset = result.getLineStart(firstLine)
        val lastOffset = result.getLineEnd(lastLine)
        val from = tasks.binarySearchBy(firstOffset) { it.displayOffset }.let { if (it < 0) -it - 1 else it }
        for (index in from until tasks.size) {
            val task = tasks[index]
            val offset = task.displayOffset
            if (offset > lastOffset) break
            if (offset >= result.layoutInput.text.length) continue
            val rect = result.getBoundingBox(offset)
            val y = rect.top - scroll
            if (y < -rect.height || y > viewportHeight) continue
            val checked = task.checked
            Box(Modifier.offset { IntOffset(rect.left.roundToInt() + with(density) { 14.dp.roundToPx() }, y.roundToInt() - with(density) { 8.dp.roundToPx() }) }
                .size(48.dp).semantics { contentDescription = taskDescription; role = Role.Checkbox; toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
                .clickable(enabled = editable, onClickLabel = stringResource(R.string.md_toggle_task)) {
                    val at = task.sourceOffset
                    model.applyEditorEdit(EditResult(source.replaceRange(at, at + 1, if (checked) " " else "x"), model.currentSelection()), editToken)
                })
        }
    }
}

private data class TaskTarget(val displayOffset: Int, val sourceOffset: Int, val checked: Boolean)

@Composable
private fun BoxScope.EditingNavigation(model: WorkspaceViewModel, viewportHeight: Int, interactions: InteractionSource) {
    val dragging by interactions.collectIsDraggedAsState()
    DocumentNavigation(model, DocumentScrollRange(viewportHeight.toFloat(), model.editorScroll.maxValue.toFloat().takeIf { it < Int.MAX_VALUE }?.plus(viewportHeight) ?: 0f, model.editorScroll.value.toFloat()), model.editorScroll.isScrollInProgress, dragging) { progress ->
        model.editorScroll.scrollTo((model.editorScroll.maxValue * progress).roundToInt())
    }
}
