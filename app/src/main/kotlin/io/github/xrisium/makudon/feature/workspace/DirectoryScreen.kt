package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

fun sortLabel(key: SortKey) = when (key) {
    SortKey.Name -> R.string.name; SortKey.Modified -> R.string.sort_modified; SortKey.Created -> R.string.sort_created; SortKey.Type -> R.string.sort_type; SortKey.Size -> R.string.sort_size
}
fun sortIcon(key: SortKey, descending: Boolean) = when (key) {
    SortKey.Name -> if (descending) R.drawable.ic_arrow_down_z_a else R.drawable.ic_arrow_down_a_z
    SortKey.Modified -> if (descending) R.drawable.ic_clock_arrow_down else R.drawable.ic_clock_arrow_up
    SortKey.Created -> if (descending) R.drawable.ic_calendar_arrow_down else R.drawable.ic_calendar_arrow_up
    SortKey.Type -> R.drawable.ic_file_type
    SortKey.Size -> if (descending) R.drawable.ic_arrow_down_wide_narrow else R.drawable.ic_arrow_down_narrow_wide
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectoryPage(model: WorkspaceViewModel, onAction: (Entry, String) -> Unit, onNewFolder: () -> Unit) {
    val ui = model.ui
    val workspace = ui.workspace ?: return
    var expanded by rememberSaveable { mutableStateOf(listOf(Workspace.ROOT, Workspace.INBOX)) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }
    val hasExpanded = workspace.entries.any { entry ->
        if (!entry.folder || entry.id == Workspace.ROOT || entry.id !in expanded) false
        else {
            var parent = entry.parent
            var visible = true
            while (parent != null) { if (parent !in expanded) visible = false; parent = workspace.entry(parent)?.parent }
            visible
        }
    }
    LaunchedEffect(ui.selected, workspace.entries.size) {
        val ancestors = mutableListOf<String>()
        var parent = workspace.entry(ui.selected)?.parent
        while (parent != null) { ancestors += parent; parent = workspace.entry(parent)?.parent }
        expanded = (expanded + ancestors).distinct()
    }
    Column {
        FlowRow(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.directory), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tool(R.drawable.ic_search, stringResource(R.string.search), active = search, onClick = { search = !search; if (!search) query = "" })
                Box {
                    Box {
                        Tool(sortIcon(workspace.sort, workspace.descending), stringResource(R.string.sort_description, stringResource(sortLabel(workspace.sort)), stringResource(if (workspace.descending) R.string.descending else R.string.ascending)), active = sortOpen, onClick = { sortOpen = !sortOpen })
                        if (workspace.sort == SortKey.Type) Glyph(if (workspace.descending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(12.dp))
                    }
                    DropdownMenu(sortOpen, { sortOpen = false }, shape = RoundedCornerShape(20.dp), containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(min = 230.dp, max = 290.dp)) {
                        Text(stringResource(R.string.sort), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SortKey.entries.forEach { key ->
                            DropdownMenuItem(text = { Text(stringResource(sortLabel(key))) }, leadingIcon = { Glyph(sortIcon(key, workspace.descending)) }, trailingIcon = { if (key == workspace.sort) Glyph(R.drawable.ic_check) }, onClick = { model.preferences(sort = key) }, enabled = !ui.busy)
                        }
                        HorizontalDivider(Modifier.padding(10.dp))
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            listOf(false, true).forEach { descending ->
                                TextButton(onClick = { model.preferences(descending = descending) }, modifier = Modifier.weight(1f).background(if (workspace.descending == descending) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)), enabled = !ui.busy) {
                                    Glyph(if (descending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(if (descending) R.string.descending else R.string.ascending), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !ui.busy) { model.preferences(foldersFirst = !workspace.foldersFirst) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(workspace.foldersFirst, { model.preferences(foldersFirst = it) }, enabled = !ui.busy)
                            Text(stringResource(R.string.folders_first), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Tool(if (hasExpanded) R.drawable.ic_fold_vertical else R.drawable.ic_unfold_vertical, stringResource(if (hasExpanded) R.string.collapse_all else R.string.expand_all), onClick = { expanded = if (hasExpanded) listOf(Workspace.ROOT) else workspace.entries.filter { it.folder }.map { it.id } })
                Tool(R.drawable.ic_folder_plus, stringResource(R.string.new_folder), enabled = !ui.busy, onClick = onNewFolder)
                Tool(R.drawable.ic_plus, stringResource(R.string.new_document), enabled = !ui.busy, onClick = { model.newDocument(Page.Directory) })
            }
        }
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Glyph(R.drawable.ic_search) }, trailingIcon = { Tool(R.drawable.ic_x, stringResource(R.string.close), onClick = { search = false; query = "" }) }, shape = RoundedCornerShape(16.dp), singleLine = true)
        Text(stringResource(R.string.directory_hint), Modifier.padding(start = 20.dp, end = 16.dp, top = 5.dp, bottom = 13.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val rows = buildList<Pair<Entry, Int>> {
            fun append(item: Entry, depth: Int) {
                add(item to depth)
                if (item.folder && item.id in expanded) workspace.sorted(workspace.entries.filter { it.parent == item.id }).forEach { append(it, depth + 1) }
            }
            if (query.isNotBlank()) workspace.sorted(workspace.entries.filter { !it.folder && it.filename.contains(query.trim(), ignoreCase = true) }).forEach { add(it to 0) }
            else workspace.entry(Workspace.ROOT)?.let { append(it, 0) }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (rows.isEmpty()) item { Text(stringResource(R.string.search_empty), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.first.id }) { (entry, depth) ->
                EntryRow(entry, depth, expanded.contains(entry.id), entry.folder && ui.selected == entry.id, ui.busy, model.clipboard?.let { it.cut && it.id == entry.id } == true,
                    path = if (query.isNotBlank()) workspace.path(entry.parent ?: Workspace.ROOT) else null,
                    pasteLocation = workspace.path(if (entry.folder) entry.id else entry.parent ?: Workspace.ROOT),
                    onClick = { if (entry.folder) model.select(entry.id) else model.open(entry.id) },
                    onToggle = { expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id },
                    clipboard = model.clipboard,
                    onAction = { action -> if (entry.folder) model.select(entry.id); onAction(entry, action) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, depth: Int, expanded: Boolean, selected: Boolean, busy: Boolean, cut: Boolean, path: String?, pasteLocation: String, onClick: () -> Unit, onToggle: () -> Unit, clipboard: WorkspaceViewModel.Clipboard?, onAction: (String) -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val actionLabel = stringResource(R.string.item_actions)
    val colors = MaterialTheme.colorScheme
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected || menu) colors.surfaceVariant else Color.Transparent)
            .combinedClickable(enabled = !busy, onLongClickLabel = actionLabel, onLongClick = { menu = true }, onClick = onClick)
            .semantics { customActions = listOf(CustomAccessibilityAction(actionLabel) { menu = true; true }) }
            .padding(start = (depth * 17).coerceAtMost(102).dp, end = 10.dp).defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.folder) Tool(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, stringResource(if (expanded) R.string.collapse_folder else R.string.expand_folder, entry.name), onClick = onToggle)
            else Spacer(Modifier.width(48.dp))
            Glyph(if (entry.folder) { if (expanded) R.drawable.ic_folder_open else R.drawable.ic_folder } else { if (entry.kind == DocumentKind.Markdown) R.drawable.ic_markdown else R.drawable.ic_file_text }, Modifier.alpha(if (cut) .65f else 1f), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 9.dp, top = 10.dp, bottom = 10.dp).alpha(if (cut) .65f else 1f)) {
                Text(entry.filename, style = MaterialTheme.typography.bodyMedium)
                if (path != null) Text(path, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (entry.unreadable) Text(stringResource(R.string.open_failed), style = MaterialTheme.typography.labelSmall, color = colors.error)
            }
        }
        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp), containerColor = colors.surface, modifier = Modifier.widthIn(min = 240.dp, max = 290.dp)) {
            Text(entry.filename, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            val actions = buildList {
                add(Triple("info", R.string.more_info, R.drawable.ic_info)); add(Triple("rename", R.string.rename, R.drawable.ic_pencil_line))
                add(Triple("copy", R.string.copy, R.drawable.ic_copy)); add(Triple("cut", R.string.cut, R.drawable.ic_scissors)); add(Triple("paste", R.string.paste, R.drawable.ic_clipboard_paste))
                if (entry.folder) { add(Triple("folder", R.string.new_folder, R.drawable.ic_folder_plus)); add(Triple("document", R.string.new_document, R.drawable.ic_file_plus_2)) }
                add(Triple("delete", R.string.delete, R.drawable.ic_trash_2))
            }
            actions.forEach { (action, label, icon) ->
                if (action == "delete") HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                val disabled = busy || (entry.id == Workspace.ROOT && action in listOf("rename", "delete", "copy", "cut")) || (entry.id == Workspace.INBOX && action == "cut") || (action == "paste" && clipboard == null)
                DropdownMenuItem(text = { Text(stringResource(label), color = if (action == "delete" && !disabled) colors.error else Color.Unspecified) }, leadingIcon = { Glyph(icon, tint = if (action == "delete" && !disabled) colors.error else colors.onSurfaceVariant) }, enabled = !disabled, onClick = { menu = false; onAction(action) })
            }
            Text(if (clipboard == null) stringResource(R.string.clipboard_empty) else stringResource(R.string.paste_location, pasteLocation), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (entry.id == Workspace.ROOT) Text(stringResource(R.string.root_protected), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
