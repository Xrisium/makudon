package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

internal data class TableRowLayout(val cells: List<MarkdownCellText>, val styled: List<AnnotatedString>, val top: Int, val height: Int)
internal data class TableLayout(val rows: List<TableRowLayout>, val height: Int)

internal fun measureTableLayout(node: MdElement, document: MarkdownDocument, style: TextStyle, measurer: TextMeasurer,
    density: Density, link: Color, highlight: Color, query: String = "", caseSensitive: Boolean = true): TableLayout {
    val width = with(density) { 140.dp.roundToPx() }
    val padding = with(density) { 20.dp.roundToPx() }
    val minimum = with(density) { 48.dp.roundToPx() }
    var top = 0
    val rows = node.descendants().filter { it.kind == "TableRow" }.mapIndexed { index, row ->
        val cells = MarkdownTableEdits.cells(document, row)
        val styled = row.children.map { mdText(it, document, link, query, caseSensitive, highlight) }
        val rowStyle = if (index == 0) style.copy(fontWeight = FontWeight.Bold) else style
        val height = maxOf(minimum, styled.mapIndexed { column, text ->
            // Provisional AST literals can lag behind native input; measure the current cell.
            val visible = if (text.text == cells[column].text) text else AnnotatedString(cells[column].text)
            measurer.measure(if (visible.isEmpty()) AnnotatedString(" ") else visible, rowStyle, constraints = Constraints(maxWidth = width)).size.height + padding
        }.maxOrNull() ?: minimum)
        TableRowLayout(cells, styled, top, height).also { top += height }
    }.toList()
    return TableLayout(rows, top)
}

@Composable
internal fun MarkdownEditableTable(model: WorkspaceViewModel, document: MarkdownDocument,
    style: TextStyle, editable: Boolean, modifier: Modifier, layout: TableLayout, visibleTop: Float, visibleBottom: Float, interactions: MutableInteractionSource) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Box(modifier.scrollable(model.editorScroll, Orientation.Vertical, reverseDirection = true, interactionSource = interactions)
        .horizontalScroll(rememberScrollState()).background(colors.background)) {
        Column(Modifier.width(160.dp * (layout.rows.firstOrNull()?.cells?.size ?: 1))) {
            var position = 0
            layout.rows.forEachIndexed { row, item ->
                if (item.top + item.height >= visibleTop && item.top <= visibleBottom) key(row) {
                    if (item.top > position) Spacer(Modifier.height(with(density) { (item.top - position).toDp() }))
                    Row(Modifier.height(with(density) { item.height.toDp() })) {
                        item.cells.forEachIndexed { column, cell ->
                            key(row, column) {
                                EditableCell(model, document, cell, item.styled[column], if (row == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                                    editable, stringResource(R.string.md_table_cell, row + 1, column + 1),
                                    Modifier.width(160.dp).fillMaxHeight().heightIn(min = 48.dp).border(1.dp, colors.outlineVariant).padding(10.dp)
                                        .testTag("table-cell-$row-$column"))
                            }
                        }
                    }
                    position = item.top + item.height
                }
            }
            if (position < layout.height) Spacer(Modifier.height(with(density) { (layout.height - position).toDp() }))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableCell(model: WorkspaceViewModel, document: MarkdownDocument, cell: MarkdownCellText, styled: AnnotatedString, style: TextStyle,
    editable: Boolean, label: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = remember { TextFieldState(cell.text) }
    val session = remember { model.editToken().session }
    var mapped by remember { mutableStateOf(cell) }
    var snapshot by remember { mutableStateOf(document.source) }
    var focused by remember { mutableStateOf(false) }
    val currentStyled by rememberUpdatedState(styled)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val uri = LocalUriHandler.current
    // Input is published synchronously; this mirror only owns the platform IME composition.
    // Background parses and undo may refresh the mirror, but never replace an unchanged composition.
    LaunchedEffect(document, cell, state.composition) {
        if (model.textState?.text?.toString() != document.source) return@LaunchedEffect
        if (model.markdownDocument?.source == document.source || state.text.toString() == cell.text) {
            // A late parse can describe an older cell while the IME owns a newer
            // composition. Replacing the mirror commits that composition and makes
            // the next candidate append; retry reconciliation when composition ends.
            if (state.composition != null && state.text.toString() != cell.text) return@LaunchedEffect
            if (state.text.toString() != cell.text) state.edit {
                replace(0, length, cell.text)
                selection = TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length))
            }
            mapped = cell; snapshot = document.source
        }
    }
    LaunchedEffect(state.text.toString()) { state.undoState.clearHistory() }
    val input = remember(model, state) { InputTransformation {
        // Selection-only gestures also run this filter in a read-only cell.
        // Reverting them through the model's edit gate would break native Copy.
        if (asCharSequence().toString() == originalText.toString()) return@InputTransformation
        val before = model.textState?.text?.toString()
        if (model.editToken().session != session || before != snapshot || originalText.toString() != mapped.text) { revertAllChanges(); return@InputTransformation }
        val normalized = TextRules.normalize(asCharSequence().toString()).replace('\n', ' ')
        if (normalized != asCharSequence().toString()) replace(0, length, normalized)
        try {
            val changed = mapped.replace(before, normalized)
            if (model.applyEditorEdit(changed.result, model.editToken())) {
                mapped = changed.cell; snapshot = changed.result.text
            } else revertAllChanges()
        } catch (e: WorkspaceException) { revertAllChanges(); model.inputRejected(e.reason) }
    } }
    // Stable transformations keep Android's active InputConnection across parse/style refreshes.
    val output = remember(state) { OutputTransformation {
        val styled = currentStyled
        val visible = asCharSequence().toString()
        var prefix = 0
        while (prefix < minOf(visible.length, styled.length) && visible[prefix] == styled[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(visible.length, styled.length) - prefix && visible[visible.lastIndex - suffix] == styled[styled.lastIndex - suffix]) suffix++
        fun offset(at: Int) = when { at <= prefix -> at; at >= styled.length - suffix -> at + visible.length - styled.length; else -> prefix }
        styled.spanStyles.forEach { span ->
            val start = offset(span.start).coerceIn(0, length); val end = offset(span.end).coerceIn(start, length)
            if (start < end) addStyle(span.item, start, end)
        }
    } }
    BasicTextField(state, modifier.observeReadingTap(model.ui.editorMode == EditorMode.Reading, { state.selection.collapsed }) { point ->
        layout?.takeIf { currentStyled.text == state.text.toString() }?.characterAt(point)?.let { offset ->
            (currentStyled.getLinkAnnotations(offset, offset + 1).firstOrNull()?.item as? LinkAnnotation.Url)?.let { uri.openUri(it.url) }
        }
    }.onFocusChanged {
        focused = it.isFocused
        if (it.isFocused && model.textState?.text?.toString() == snapshot) {
            model.textState?.edit { selection = TextRange(mapped.units.firstOrNull()?.start ?: mapped.range.start) }
        }
    }.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key in listOf(Key.Z, Key.Y)) {
            model.undo(it.key == Key.Y || it.isShiftPressed); true
        } else false
    }.semantics { contentDescription = label }, readOnly = !editable, textStyle = style.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.primary), inputTransformation = input,
        outputTransformation = output,
        onTextLayout = { get -> layout = get() },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = true))
}
