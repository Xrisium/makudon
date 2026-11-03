package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
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
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownEditingField(model: WorkspaceViewModel, markdown: Boolean, bodyStyle: TextStyle, editable: Boolean, findOpen: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit) {
    val field = model.textState ?: return
    val colors = MaterialTheme.colorScheme
    val source = field.text.toString()
    val typeset = markdown && model.ui.editorMode == EditorMode.Typeset
    var focused by remember { mutableStateOf(false) }
    var layout by remember(field) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var visibleSourceStart by remember(field) { mutableIntStateOf(0) }
    var visibleSourceEnd by remember(field) { mutableIntStateOf(2048) }
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    val displayDocument = model.tableBoundaryDocument ?: model.markdownDocument
    val parsed = remember(displayDocument, source) { provisionalDocument(displayDocument, source) }
    val sourceSelection = field.selection.let { TextSelection(it.start, it.end) }
    val composition = field.composition?.let { SourceRange(it.start, it.end) }
    val active = if (focused || findOpen) sourceSelection else null
    val tables = if (typeset) parsed.elements.filter { it.kind == "TableBlock" } else emptyList()
    val projection = if (typeset) projection(parsed, active, composition, tables) else MarkdownProjection(emptyList())
    val tableLayouts = tables.associate { table -> table.range.start to key(table.range.start) { rememberTableLayout(table, parsed, bodyStyle) } }
    val tableHeights = tableLayouts.mapValues { with(density) { it.value.height.toSp().value } }
    fun window(start: Int, end: Int) = SourceRange((start - 1024).coerceAtLeast(0), (end + 1024).coerceAtMost(source.length))
    val windows = listOf(window(visibleSourceStart.coerceAtMost(source.length), visibleSourceEnd.coerceAtMost(source.length)), window(sourceSelection.min, sourceSelection.min), window(sourceSelection.max, sourceSelection.max))
    val output = remember(typeset, parsed, projection, bodyStyle, colors.primary, colors.surfaceVariant, tableHeights, windows) {
        if (typeset) liveTransformation(parsed, projection, bodyStyle, colors.primary, colors.surfaceVariant, tableHeights, windows) else null
    }
    val currentOutput by rememberUpdatedState(output)
    // AST/style refreshes must not restart the native IME's composing session.
    val stableOutput = remember(field, typeset) {
        if (typeset) OutputTransformation { currentOutput?.let { with(it) { transformOutput() } } } else null
    }
    val input = remember(model, field, markdown) { InputTransformation {
        if (model.ui.busy || model.ui.transitioning || model.ui.storageBlocked || model.ui.page != Page.Editor || model.ui.editorMode == EditorMode.Reading) { revertAllChanges(); return@InputTransformation }
        val before = originalText.toString()
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
    // Resolve anchors only after the new mode has supplied a matching text layout.
    val restoreAnchor = remember(field, model.ui.editorMode) { model.editorAnchor to model.editorAnchorOffset }
    LaunchedEffect(model.ui.editorMode) {
        withFrameNanos { }
        layout?.let { result ->
            val at = projection.offset(restoreAnchor.first).coerceIn(0, result.layoutInput.text.length)
            model.editorScroll.scrollTo(result.getLineTop(result.getLineForOffset(at)).roundToInt() + restoreAnchor.second)
        }
    }
    LaunchedEffect(layout, projection, parsed, viewportHeight) {
        val result = layout ?: return@LaunchedEffect
        snapshotFlow { model.editorScroll.value }.collect { scroll ->
            val visible = result.getOffsetForPosition(Offset(0f, scroll.toFloat()))
            val original = projection.sourceOffset(visible).coerceIn(0, source.length)
            val bottom = result.getOffsetForPosition(Offset(0f, (scroll + viewportHeight).toFloat()))
            visibleSourceStart = (original / 1024) * 1024
            visibleSourceEnd = (((projection.sourceOffset(bottom).coerceIn(original, source.length) / 1024) + 1) * 1024).coerceAtMost(source.length)
            val block = parsed.root.children.lastOrNull { it.range.start <= original }
            val start = block?.range?.start ?: original
            val at = projection.offset(start).coerceIn(0, result.layoutInput.text.length)
            model.editorAnchor = start
            model.editorAnchorOffset = (scroll - result.getLineTop(result.getLineForOffset(at)).roundToInt()).coerceAtLeast(0)
        }
    }
    val description = stringResource(R.string.edit_body)
    val taskDescription = stringResource(R.string.md_toggle_task)
    val editToken = model.editToken()
    Box(modifier.clipToBounds().onSizeChanged { viewportHeight = it.height }) {
        BasicTextField(state = field, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key in listOf(Key.Z, Key.Y)) { model.undo(it.key == Key.Y || it.isShiftPressed); true } else false
            }.testTag(if (typeset) "typeset-editor" else "source-editor").semantics { contentDescription = description },
            readOnly = !editable, textStyle = bodyStyle.copy(color = colors.onBackground), cursorBrush = SolidColor(colors.primary), inputTransformation = input,
            outputTransformation = stableOutput, scrollState = model.editorScroll,
            onTextLayout = { get -> layout = get() },
            decorator = TextFieldDecorator { inner -> Box { if (source.isEmpty()) Text(stringResource(R.string.editor_placeholder), style = bodyStyle, color = colors.onSurfaceVariant); inner() } })
        val result = layout
        if (typeset && result != null && result.layoutInput.text.text.length > 0) {
            for (table in tables) {
                val offset = projection.offset(table.range.start)
                if (offset < result.layoutInput.text.length && result.layoutInput.text[offset] == '\uFFFC') {
                    val top = result.getLineTop(result.getLineForOffset(offset)) - model.editorScroll.value
                    val tableLayout = tableLayouts.getValue(table.range.start)
                    val heightPx = tableLayout.height.toFloat()
                    if (top + heightPx >= 0 && top < viewportHeight) {
                        Column(Modifier.offset { IntOffset(with(density) { 24.dp.roundToPx() }, top.roundToInt() + with(density) { 16.dp.roundToPx() }) }
                            .fillMaxWidth().padding(end = 48.dp).wrapContentHeight(align = androidx.compose.ui.Alignment.Top, unbounded = true)
                            .height(with(density) { heightPx.toDp() }).background(colors.background)) {
                            MarkdownEditableTable(model, parsed, bodyStyle, editable, Modifier.fillMaxSize(), tableLayout,
                                -top - 200f, viewportHeight - top + 200f, onFocus)
                        }
                    }
                }
            }
            val tasks = Regex("(?m)^( *)(?:[-+*]|\\d+[.)]) \\[([ xX])] ").findAll(source)
            for (task in tasks) {
                val range = SourceRange(task.range.first, task.range.last + 1)
                if (active?.let { range.intersects(it.min, it.max) } == true || parsed.protected(range.start, range.end)) continue
                val offset = projection.offset(task.range.first + task.groupValues[1].length)
                if (offset >= result.layoutInput.text.length) continue
                val rect = result.getBoundingBox(offset)
                val y = rect.top - model.editorScroll.value
                if (y < -rect.height || y > with(density) { 2000.dp.toPx() }) continue
                val checked = task.groupValues[2].equals("x", true)
                Box(Modifier.offset { IntOffset(rect.left.roundToInt() + with(density) { 14.dp.roundToPx() }, y.roundToInt() + with(density) { 8.dp.roundToPx() }) }
                    .size(48.dp).semantics { contentDescription = taskDescription; role = Role.Checkbox; toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
                    .clickable(enabled = editable, onClickLabel = stringResource(R.string.md_toggle_task)) {
                        val at = task.groups[2]!!.range.first
                        model.applyEditorEdit(EditResult(source.replaceRange(at, at + 1, if (checked) " " else "x"), model.currentSelection()), editToken)
                    })
            }
        }
    }
}
