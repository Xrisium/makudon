package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

@Composable
internal fun MarkdownEditableTable(model: WorkspaceViewModel, node: MdElement, document: MarkdownDocument,
    style: TextStyle, editable: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rows = remember(node) { node.descendants().filter { it.kind == "TableRow" }.toList() }
    Box(modifier.horizontalScroll(rememberScrollState()).background(colors.background)) {
        LazyColumn(Modifier.width(160.dp * (rows.firstOrNull()?.children?.size ?: 1)).fillMaxHeight()) {
            itemsIndexed(rows, key = { row, _ -> row }) { row, item ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    MarkdownTableEdits.cells(document, item).forEachIndexed { column, cell ->
                        key(row, column) {
                            EditableCell(model, document, cell, mdText(item.children[column], document, colors.primary), if (row == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                                editable, stringResource(R.string.md_table_cell, row + 1, column + 1),
                                Modifier.width(160.dp).fillMaxHeight().heightIn(min = 48.dp).border(1.dp, colors.outlineVariant).padding(10.dp)
                                    .testTag("table-cell-$row-$column"), onFocus)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableCell(model: WorkspaceViewModel, document: MarkdownDocument, cell: MarkdownCellText, styled: AnnotatedString, style: TextStyle,
    editable: Boolean, label: String, modifier: Modifier, onFocus: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = remember { TextFieldState(cell.text) }
    val session = remember { model.editToken().session }
    var mapped by remember { mutableStateOf(cell) }
    var snapshot by remember { mutableStateOf(document.source) }
    var focused by remember { mutableStateOf(false) }
    val currentFocus by rememberUpdatedState(onFocus)
    val currentStyled by rememberUpdatedState(styled)
    // Input is published synchronously; this mirror only owns the platform IME composition.
    // Background parses and undo may refresh the mirror, but never replace an unchanged composition.
    LaunchedEffect(document, cell) {
        if (model.textState?.text?.toString() != document.source) return@LaunchedEffect
        if (model.markdownDocument?.source == document.source || state.text.toString() == cell.text) {
            if (state.text.toString() != cell.text) state.edit {
                replace(0, length, cell.text)
                selection = TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length))
            }
            mapped = cell; snapshot = document.source
        }
    }
    LaunchedEffect(state.text.toString()) { state.undoState.clearHistory() }
    DisposableEffect(Unit) { onDispose { if (focused) currentFocus(false) } }
    val input = remember(model, state) { InputTransformation {
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
    BasicTextField(state, modifier.onFocusChanged {
        focused = it.isFocused; currentFocus(it.isFocused)
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
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = true))
}
