package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownTools(model: WorkspaceViewModel, enabled: Boolean, onLink: () -> Unit) {
    var menu by remember { mutableIntStateOf(0) }
    val selection = model.currentSelection()
    val parsed = model.markdownDocument
    val source = model.textState?.text?.toString()
    fun active(kind: String) = parsed?.takeIf { it.source == source }?.elements?.any { it.kind == kind && it.range.start <= selection.min && it.range.end >= selection.max } == true
    val heading = parsed?.takeIf { it.source == source }?.elements?.firstOrNull { it.kind == "Heading" && it.range.start <= selection.min && it.range.end >= selection.max }?.level ?: 0
    val task = parsed?.takeIf { it.source == source }?.elements?.any { it.kind == "ListItem" && it.range.start <= selection.min && it.range.end >= selection.max && it.children.any { child -> child.kind == "TaskListItemMarker" } } == true
    val labels = listOf(R.string.md_heading, R.string.md_bold, R.string.md_list, R.string.md_quote, R.string.md_link, R.string.md_more)
    val glyphs = listOf("H", "B", "☷", "❞", "↗", "⋯")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val available = maxWidth
    Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = available).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEachIndexed { index, label ->
            Box {
                val selected = when (index) { 0 -> active("Heading"); 1 -> active("StrongEmphasis"); 2 -> active("ListItem"); 3 -> active("BlockQuote"); 4 -> active("Link"); else -> false }
                val name = stringResource(label)
                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(), tooltip = { PlainTooltip { Text(name) } }) {
                TextButton(onClick = { if (!model.ui.busy && !model.ui.transitioning && !model.ui.storageBlocked) when (index) { 1 -> model.format(MarkdownAction.Bold); 3 -> model.format(MarkdownAction.Quote); 4 -> onLink(); else -> menu = index + 1 } },
                    enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = name; this.selected = selected }, contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)) {
                    Text(glyphs[index], fontWeight = if (index == 1) FontWeight.Bold else FontWeight.Medium)
                }
                }
                DropdownMenu(menu == index + 1, { menu = 0 }, properties = PopupProperties(focusable = false)) {
                    when (index) {
                        0 -> (0..6).forEach { level ->
                            DropdownMenuItem(text = { Text(if (level == 0) stringResource(R.string.md_body) else stringResource(R.string.md_heading_level, level)) },
                                trailingIcon = { if (heading == level) Glyph(R.drawable.ic_check) },
                                onClick = { model.format(MarkdownAction.Heading, level); menu = 0 })
                        }
                        2 -> listOf(R.string.md_bullet to MarkdownAction.Bullet, R.string.md_ordered to MarkdownAction.Ordered, R.string.md_task to MarkdownAction.Task, R.string.md_unlist to MarkdownAction.Unlist).forEach { (text, action) ->
                            val chosen = when (action) { MarkdownAction.Task -> task; MarkdownAction.Ordered -> active("OrderedList") && !task; MarkdownAction.Bullet -> active("BulletList") && !task; else -> !active("ListItem") }
                            DropdownMenuItem(text = { Text(stringResource(text)) }, trailingIcon = { if (chosen) Glyph(R.drawable.ic_check) }, onClick = { model.format(action); menu = 0 })
                        }
                        5 -> listOf(R.string.md_italic to MarkdownAction.Italic, R.string.md_strike to MarkdownAction.Strike, R.string.md_inline_code to MarkdownAction.InlineCode,
                            R.string.md_code_block to MarkdownAction.CodeBlock, R.string.md_rule to MarkdownAction.Rule, R.string.md_table to MarkdownAction.Table,
                            R.string.md_indent to MarkdownAction.Indent, R.string.md_outdent to MarkdownAction.Outdent).forEach { (text, action) ->
                            val chosen = when (action) { MarkdownAction.Italic -> active("Emphasis"); MarkdownAction.Strike -> active("Strikethrough"); MarkdownAction.InlineCode -> active("Code"); MarkdownAction.CodeBlock -> active("FencedCodeBlock") || active("IndentedCodeBlock"); else -> false }
                            DropdownMenuItem(text = { Text(stringResource(text)) }, trailingIcon = { if (chosen) Glyph(R.drawable.ic_check) }, onClick = { model.format(action); menu = 0 })
                        }
                    }
                }
            }
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
