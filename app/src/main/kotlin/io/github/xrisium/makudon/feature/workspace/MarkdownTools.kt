package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

internal enum class MarkdownToolGroup(val label: Int) {
    Text(R.string.md_group_text), Paragraph(R.string.md_group_paragraph), Insert(R.string.md_group_insert),
}

private data class FormatTool(val label: Int, val icon: Int, val action: MarkdownAction? = null)
private val textTools = listOf(
    FormatTool(R.string.md_heading, R.drawable.ic_heading),
    FormatTool(R.string.md_bold, R.drawable.ic_bold, MarkdownAction.Bold),
    FormatTool(R.string.md_italic, R.drawable.ic_italic, MarkdownAction.Italic),
    FormatTool(R.string.md_strike, R.drawable.ic_strikethrough, MarkdownAction.Strike),
    FormatTool(R.string.md_link, R.drawable.ic_link),
)
private val paragraphTools = listOf(
    FormatTool(R.string.md_list, R.drawable.ic_list),
    FormatTool(R.string.md_quote, R.drawable.ic_quote, MarkdownAction.Quote),
    FormatTool(R.string.md_indent, R.drawable.ic_list_indent_increase, MarkdownAction.Indent),
    FormatTool(R.string.md_outdent, R.drawable.ic_list_indent_decrease, MarkdownAction.Outdent),
)
private val insertTools = listOf(
    FormatTool(R.string.md_inline_code, R.drawable.ic_code, MarkdownAction.InlineCode),
    FormatTool(R.string.md_code_block, R.drawable.ic_square_code, MarkdownAction.CodeBlock),
    FormatTool(R.string.md_rule, R.drawable.ic_minus, MarkdownAction.Rule),
    FormatTool(R.string.md_table, R.drawable.ic_table_2, MarkdownAction.Table),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownTools(model: WorkspaceViewModel, enabled: Boolean, group: MarkdownToolGroup,
    onGroup: (MarkdownToolGroup) -> Unit, onLink: () -> Unit) {
    val transition = updateTransition(group, label = "format-group")
    val moving = transition.isRunning || transition.currentState != transition.targetState
    val distance = with(LocalDensity.current) { 48.dp.toPx() }
    Column(Modifier.fillMaxWidth().clipToBounds().testTag("markdown-tools").discreteTabSwipe(
        enabled = enabled && !moving, minimumDistance = distance, edgeInset = 0f, eventPass = PointerEventPass.Initial,
        onSwipe = { direction -> MarkdownToolGroup.entries.getOrNull(group.ordinal + direction)?.let(onGroup) },
    )) {
        transition.AnimatedContent(transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it * direction } togetherWith
                slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it * direction } using null
        }) { shown ->
            Box(Modifier.fillMaxWidth().blockInteractions(moving || shown != group)) {
                MarkdownToolRow(model, enabled, shown, onLink)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownToolRow(model: WorkspaceViewModel, enabled: Boolean, group: MarkdownToolGroup, onLink: () -> Unit) {
    var menu by remember(group) { mutableIntStateOf(0) }
    val selection = model.currentSelection()
    val parsed = model.markdownDocument
    val source = model.textState?.text?.toString()
    fun active(kind: String) = parsed?.takeIf { it.source == source }?.elements?.any { it.kind == kind && it.range.start <= selection.min && it.range.end >= selection.max } == true
    val heading = parsed?.takeIf { it.source == source }?.elements?.firstOrNull { it.kind == "Heading" && it.range.start <= selection.min && it.range.end >= selection.max }?.level ?: 0
    val task = parsed?.takeIf { it.source == source }?.elements?.any { it.kind == "ListItem" && it.range.start <= selection.min && it.range.end >= selection.max && it.children.any { child -> child.kind == "TaskListItemMarker" } } == true
    val tools = when (group) { MarkdownToolGroup.Text -> textTools; MarkdownToolGroup.Paragraph -> paragraphTools; MarkdownToolGroup.Insert -> insertTools }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = available).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            tools.forEach { tool ->
                Box {
                    val selected = when (tool.label) {
                        R.string.md_heading -> active("Heading"); R.string.md_bold -> active("StrongEmphasis")
                        R.string.md_italic -> active("Emphasis"); R.string.md_strike -> active("Strikethrough")
                        R.string.md_list -> active("ListItem"); R.string.md_quote -> active("BlockQuote"); R.string.md_link -> active("Link")
                        R.string.md_inline_code -> active("Code"); R.string.md_code_block -> active("FencedCodeBlock") || active("IndentedCodeBlock")
                        else -> false
                    }
                    val name = stringResource(tool.label)
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(), tooltip = { PlainTooltip { Text(name) } }) {
                        IconButton(onClick = {
                            if (!model.ui.busy && !model.ui.transitioning && !model.ui.storageBlocked) {
                                if (tool.action != null) model.format(tool.action)
                                else if (tool.label == R.string.md_link) onLink()
                                else menu = tool.label
                            }
                        }, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = name; this.selected = selected },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
                            Glyph(tool.icon)
                        }
                    }
                    UpwardFormatMenu(menu == tool.label, { menu = 0 }) {
                        if (tool.label == R.string.md_heading) {
                            val icons = listOf(R.drawable.ic_type, R.drawable.ic_heading_1, R.drawable.ic_heading_2, R.drawable.ic_heading_3, R.drawable.ic_heading_4, R.drawable.ic_heading_5, R.drawable.ic_heading_6)
                            (0..6).forEach { level ->
                                DropdownMenuItem(text = { Text(if (level == 0) stringResource(R.string.md_body) else stringResource(R.string.md_heading_level, level)) },
                                    leadingIcon = { Glyph(icons[level]) }, trailingIcon = { if (heading == level) Glyph(R.drawable.ic_check) },
                                    onClick = { model.format(MarkdownAction.Heading, level); menu = 0 })
                            }
                        } else if (tool.label == R.string.md_list) {
                            listOf(FormatTool(R.string.md_bullet, R.drawable.ic_list, MarkdownAction.Bullet),
                                FormatTool(R.string.md_ordered, R.drawable.ic_list_ordered, MarkdownAction.Ordered),
                                FormatTool(R.string.md_task, R.drawable.ic_list_checks, MarkdownAction.Task),
                                FormatTool(R.string.md_unlist, R.drawable.ic_list_minus, MarkdownAction.Unlist)).forEach { item ->
                                val chosen = when (item.action) { MarkdownAction.Task -> task; MarkdownAction.Ordered -> active("OrderedList") && !task; MarkdownAction.Bullet -> active("BulletList") && !task; else -> !active("ListItem") }
                                DropdownMenuItem(text = { Text(stringResource(item.label)) }, leadingIcon = { Glyph(item.icon) },
                                    trailingIcon = { if (chosen) Glyph(R.drawable.ic_check) }, onClick = { model.format(checkNotNull(item.action)); menu = 0 })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Position against the actual 48dp tool, never against the hidden footer or IME. */
@Composable
private fun UpwardFormatMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    var anchorTop by remember { mutableIntStateOf(0) }
    val gap = with(density) { 4.dp.roundToPx() }
    val provider = remember(gap, topInset) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
            return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(topInset))
        }
    } }
    // A zero-size sibling inherits the tool Box coordinates without enlarging its hit area.
    Box(Modifier.onGloballyPositioned { anchorTop = it.positionInWindow().y.toInt() }) {
        if (expanded) Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
                modifier = Modifier.widthIn(min = 224.dp, max = 300.dp).heightIn(max = with(density) { (anchorTop - topInset - gap).coerceAtLeast(48).toDp() }).testTag("format-popup")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp), content = content)
            }
        }
    }
}

@Composable
internal fun MarkdownLinkDialog(model: WorkspaceViewModel, token: WorkspaceViewModel.EditToken, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val source = model.textState?.text?.toString().orEmpty()
    val existing = remember(token) { model.markdownDocument?.elements?.firstOrNull { it.kind == "Link" && it.range.start <= token.selection.min && it.range.end >= token.selection.max } }
    var name by remember(token) { mutableStateOf(existing?.let { mdText(it, checkNotNull(model.markdownDocument), androidx.compose.ui.graphics.Color.Unspecified).text } ?: source.substring(token.selection.min, token.selection.max)) }
    var address by remember(token) { mutableStateOf(existing?.destination.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.md_link)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.md_link_name)) }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.md_link_url)) }, singleLine = true)
            if (existing != null) {
                TextButton(onClick = { if (model.applyLink(token, name, address, true)) onDismiss() }) { Text(stringResource(R.string.md_remove_link)) }
                TextButton(onClick = { onOpen(address) }) { Text(stringResource(R.string.md_open_link)) }
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotEmpty() && address.isNotBlank(), onClick = { if (model.applyLink(token, name, address)) onDismiss() }) { Text(stringResource(R.string.md_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun EditorFindBar(reading: Boolean, query: String, replacement: String, caseSensitive: Boolean, count: Int, target: Int,
    onQuery: (String) -> Unit, onReplacement: (String) -> Unit, onCase: (Boolean) -> Unit, onTarget: (Int) -> Unit,
    onReplace: (Boolean) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, label = { Text(stringResource(R.string.md_query)) }, singleLine = true, modifier = Modifier.weight(1f))
            Tool(R.drawable.ic_x, stringResource(R.string.md_close), onClick = onClose)
        }
        if (!reading) OutlinedTextField(replacement, onReplacement, label = { Text(stringResource(R.string.md_replacement)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(caseSensitive, onCase)
            Text(stringResource(R.string.md_case), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.md_matches, if (count == 0) 0 else Math.floorMod(target, count) + 1, count), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            TextButton(enabled = count > 0, onClick = { onTarget(target - 1) }) { Text(stringResource(R.string.md_previous)) }
            TextButton(enabled = count > 0, onClick = { onTarget(target + 1) }) { Text(stringResource(R.string.md_next)) }
        }
        if (!reading) Row {
            TextButton(enabled = count > 0, onClick = { onReplace(false) }) { Text(stringResource(R.string.md_replace)) }
            TextButton(enabled = count > 0, onClick = { onReplace(true) }) { Text(stringResource(R.string.md_replace_all)) }
        }
    }
}
