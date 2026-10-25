package io.github.xrisium.makudon.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.testTag
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
    val busy = ui.busy
    val checked = model.selection
    val selecting = checked != null
    BackHandler(selecting) { /* Selection exits through its explicit X action. Dialogs handle their own Back. */ }
    val focus = LocalFocusManager.current
    var expanded by rememberSaveable { mutableStateOf(listOf(Workspace.ROOT)) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(search, ui.page, selecting) {
        if (!search || ui.page != Page.Directory || selecting) focus.clearFocus()
        if (selecting) { search = false; query = ""; sortOpen = false }
    }
    LaunchedEffect(selecting) {
        if (selecting) {
            val ancestors = mutableListOf<String>()
            checked.forEach { id ->
                var parent = workspace.entry(id)?.parent
                while (parent != null) { ancestors += parent; parent = workspace.entry(parent)?.parent }
            }
            expanded = (expanded + ancestors).distinct()
        }
    }
    val hasExpanded = workspace.entries.any { entry ->
        if (!entry.folder || entry.id == Workspace.ROOT || entry.id !in expanded) false
        else {
            var parent = entry.parent
            var visible = true
            while (parent != null) { if (parent !in expanded) visible = false; parent = workspace.entry(parent)?.parent }
            visible
        }
    }
    LaunchedEffect(ui.selected, workspace.entries.size, ui.directoryRevision) {
        val ancestors = mutableListOf<String>()
        listOfNotNull(workspace.entry(ui.selected)?.parent, ui.revealFolder).forEach { first ->
            var parent: String? = first
            while (parent != null) { ancestors += parent; parent = workspace.entry(parent)?.parent }
        }
        expanded = (expanded + ancestors).distinct()
        if (ui.revealFolder != null) model.revealHandled(ui.directoryRevision)
    }
    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).heightIn(min = 56.dp).testTag("selection-header"), verticalAlignment = Alignment.CenterVertically) {
                Tool(R.drawable.ic_x, stringResource(R.string.exit_selection), enabled = !busy, onClick = model::exitSelection)
                Text(stringResource(R.string.selected_count, checked.size), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = model::clearSelection, enabled = !busy && checked.isNotEmpty()) { Text(stringResource(R.string.clear_selection)) }
            }
        } else {
            FlowRow(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.directory), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.headlineMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tool(R.drawable.ic_search, stringResource(R.string.search), active = search, enabled = !busy, onClick = { search = !search; if (!search) query = "" })
                    Box {
                        Box {
                            Tool(sortIcon(workspace.sort, workspace.descending), stringResource(R.string.sort_description, stringResource(sortLabel(workspace.sort)), stringResource(if (workspace.descending) R.string.descending else R.string.ascending)), active = sortOpen, enabled = !busy, onClick = { sortOpen = !sortOpen })
                            if (workspace.sort == SortKey.Type) Glyph(if (workspace.descending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(12.dp))
                        }
                        DropdownMenu(sortOpen, { sortOpen = false }, shape = RoundedCornerShape(20.dp), containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(min = 230.dp, max = 290.dp)) {
                            Text(stringResource(R.string.sort), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SortKey.entries.forEach { key ->
                                DropdownMenuItem(text = { Text(stringResource(sortLabel(key))) }, leadingIcon = { Glyph(sortIcon(key, workspace.descending)) }, trailingIcon = { if (key == workspace.sort) Glyph(R.drawable.ic_check) }, onClick = { model.preferences(sort = key) }, enabled = !busy)
                            }
                            HorizontalDivider(Modifier.padding(10.dp))
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                listOf(false, true).forEach { descending ->
                                    TextButton(onClick = { model.preferences(descending = descending) }, modifier = Modifier.weight(1f).background(if (workspace.descending == descending) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)), enabled = !busy) {
                                        Glyph(if (descending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(if (descending) R.string.descending else R.string.ascending), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { model.preferences(foldersFirst = !workspace.foldersFirst) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(workspace.foldersFirst, { model.preferences(foldersFirst = it) }, enabled = !busy)
                                Text(stringResource(R.string.folders_first), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Tool(if (hasExpanded) R.drawable.ic_fold_vertical else R.drawable.ic_unfold_vertical, stringResource(if (hasExpanded) R.string.collapse_all else R.string.expand_all), enabled = !busy, onClick = { expanded = if (hasExpanded) listOf(Workspace.ROOT) else workspace.entries.filter { it.folder }.map { it.id } })
                    Tool(R.drawable.ic_folder_plus, stringResource(R.string.new_folder), enabled = !busy, onClick = onNewFolder)
                    Tool(R.drawable.ic_file_plus_2, stringResource(R.string.new_document), enabled = !busy, onClick = { model.newDocument(Page.Directory) })
                }
            }
            AnimatedVisibility(search, enter = expandVertically(tween(180)) + fadeIn(tween(180)), exit = shrinkVertically(tween(180)) + fadeOut(tween(180))) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Glyph(R.drawable.ic_search) }, trailingIcon = { Tool(R.drawable.ic_x, stringResource(R.string.close), onClick = { search = false; query = "" }) }, shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !busy)
            }
            Text(stringResource(R.string.directory_hint), Modifier.padding(start = 20.dp, end = 16.dp, top = 5.dp, bottom = 13.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.page == Page.Directory) WorkspaceNotice(model)
        }
        val rows = buildList<Pair<Entry, Int>> {
            fun append(item: Entry, depth: Int) {
                add(item to depth)
                if (item.folder && item.id in expanded) workspace.sorted(workspace.entries.filter { it.parent == item.id }).forEach { append(it, depth + 1) }
            }
            if (!selecting && query.isNotBlank()) workspace.sorted(workspace.entries.filter { !it.folder && it.filename.contains(query.trim(), ignoreCase = true) }).forEach { add(it to 0) }
            else workspace.entry(Workspace.ROOT)?.let { append(it, 0) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (rows.isEmpty()) item { Text(stringResource(R.string.search_empty), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.first.id }) { (entry, depth) ->
                Box(Modifier.animateItem(fadeInSpec = if (!search) tween(180) else null,
                    placementSpec = if (!search) tween(180, easing = FastOutSlowInEasing) else null,
                    fadeOutSpec = if (!search) tween(180) else null)) {
                    val selection = checked?.let { workspace.selectionOf(it, entry.id) }
                    EntryRow(entry, depth, expanded.contains(entry.id), if (selecting) selection == ItemSelection.Direct else entry.folder && ui.selected == entry.id, busy,
                        model.clipboard?.let { clip -> clip.cut && clip.ids.any { workspace.isWithin(entry.id, it) } } == true,
                        path = if (!selecting && query.isNotBlank()) workspace.path(entry.parent ?: Workspace.ROOT) else null,
                        pasteLocation = workspace.path(if (entry.folder) entry.id else entry.parent ?: Workspace.ROOT),
                        onClick = {
                            if (selecting && entry.id != Workspace.ROOT) model.toggleChecked(entry.id)
                            else if (entry.folder) {
                                if (!selecting) model.select(entry.id)
                                expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id
                            } else model.open(entry.id)
                        },
                        onToggle = { expanded = if (entry.id in expanded) expanded - entry.id else expanded + entry.id },
                        clipboard = model.clipboard,
                        onAction = { action -> if (!selecting && entry.folder && action != "select") model.select(entry.id); onAction(entry, action) },
                        selection = selection, selectionCount = checked?.size ?: 0,
                    )
                }
            }
        }
        if (selecting) SelectionActions(model)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, depth: Int, expanded: Boolean, selected: Boolean, busy: Boolean, cut: Boolean, path: String?, pasteLocation: String, onClick: () -> Unit, onToggle: () -> Unit, clipboard: WorkspaceViewModel.Clipboard?, onAction: (String) -> Unit, selection: ItemSelection?, selectionCount: Int) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val actionLabel = stringResource(R.string.item_actions)
    val selectionLabel = stringResource(R.string.multiselect)
    val inheritedLabel = stringResource(R.string.selection_inherited)
    val colors = MaterialTheme.colorScheme
    val angle by animateFloatAsState(if (expanded) 90f else 0f, tween(160, easing = FastOutSlowInEasing), label = "folder-chevron")
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected || menu) colors.surfaceVariant else Color.Transparent)
            .combinedClickable(enabled = !busy, onLongClickLabel = actionLabel, onLongClick = { menu = true }, onClick = onClick)
            .semantics { customActions = buildList {
                add(CustomAccessibilityAction(actionLabel) { if (!busy) menu = true; !busy })
                if (selection == null) add(CustomAccessibilityAction(selectionLabel) { if (!busy) onAction("select"); !busy })
            } }
            .padding(start = (depth * 17).coerceAtMost(102).dp, end = 10.dp).defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.folder) Tool(R.drawable.ic_chevron_down, stringResource(if (expanded) R.string.collapse_folder else R.string.expand_folder, entry.name), enabled = !busy, rotation = angle - 90f, onClick = onToggle)
            else Spacer(Modifier.width(48.dp))
            Glyph(if (entry.folder) { if (expanded) R.drawable.ic_folder_open else R.drawable.ic_folder } else { if (entry.kind == DocumentKind.Markdown) R.drawable.ic_markdown else R.drawable.ic_file_text }, Modifier.alpha(if (cut) .65f else 1f), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 9.dp, top = 10.dp, bottom = 10.dp).alpha(if (cut) .65f else 1f)) {
                Text(entry.filename, style = MaterialTheme.typography.bodyMedium)
                if (path != null) Text(path, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (entry.unreadable) Text(stringResource(R.string.open_failed), style = MaterialTheme.typography.labelSmall, color = colors.error)
            }
            if (selection != null && entry.id != Workspace.ROOT) {
                TriStateCheckbox(state = when (selection) {
                    ItemSelection.Direct, ItemSelection.Inherited -> ToggleableState.On
                    ItemSelection.Partial -> ToggleableState.Indeterminate
                    ItemSelection.None -> ToggleableState.Off
                }, onClick = onClick, enabled = !busy && selection != ItemSelection.Inherited,
                    modifier = Modifier.semantics { contentDescription = entry.filename; if (selection == ItemSelection.Inherited) stateDescription = inheritedLabel })
            }
        }
        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp), containerColor = colors.surface, modifier = Modifier.widthIn(min = 240.dp, max = 290.dp)) {
            Text(if (selection != null) stringResource(R.string.selected_count, selectionCount) else entry.filename, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            val actions = buildList {
                if (selection == null) {
                    add(Triple("info", R.string.more_info, R.drawable.ic_info)); add(Triple("rename", R.string.rename, R.drawable.ic_pencil_line))
                    add(Triple("select", R.string.multiselect, R.drawable.ic_check))
                }
                add(Triple("copy", R.string.copy, R.drawable.ic_copy)); add(Triple("cut", R.string.cut, R.drawable.ic_scissors)); add(Triple("paste", R.string.paste, R.drawable.ic_clipboard_paste))
                if (selection == null && entry.folder) { add(Triple("folder", R.string.new_folder, R.drawable.ic_folder_plus)); add(Triple("document", R.string.new_document, R.drawable.ic_file_plus_2)) }
                add(Triple("delete", R.string.delete, R.drawable.ic_trash_2))
            }
            actions.forEach { (action, label, icon) ->
                if (action == "delete") HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                val disabled = busy || (selection == null && entry.id == Workspace.ROOT && action in listOf("rename", "delete", "copy", "cut")) ||
                    (selection != null && selectionCount == 0 && action in listOf("copy", "cut", "delete")) || (action == "paste" && clipboard == null)
                DropdownMenuItem(text = { Text(stringResource(label), color = if (action == "delete" && !disabled) colors.error else Color.Unspecified) }, leadingIcon = { Glyph(icon, tint = if (action == "delete" && !disabled) colors.error else colors.onSurfaceVariant) }, enabled = !disabled, onClick = { menu = false; onAction(action) })
            }
            Text(if (clipboard == null) stringResource(R.string.clipboard_empty) else if (selection != null) stringResource(if (clipboard.cut) R.string.clipboard_cut_count else R.string.clipboard_copy_count, clipboard.items.size) else stringResource(R.string.paste_location, pasteLocation), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (selection == null && entry.id == Workspace.ROOT) Text(stringResource(R.string.root_protected), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelectionActions(model: WorkspaceViewModel) {
    val ui = model.ui
    val clip = model.clipboard
    val count = model.selection?.size ?: 0
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Text(ui.error?.let { stringResource(errorLabel(it)) } ?: if (ui.message == R.string.paste_done) stringResource(R.string.paste_done) else if (clip == null) stringResource(R.string.clipboard_empty) else stringResource(if (clip.cut) R.string.clipboard_cut_count else R.string.clipboard_copy_count, clip.items.size),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall, color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.error != null) TextButton(onClick = model::reload, enabled = !ui.busy) { Text(stringResource(R.string.reload_directory)) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("selection-actions")) {
        listOf(Triple("copy", R.string.copy, R.drawable.ic_copy), Triple("cut", R.string.cut, R.drawable.ic_scissors), Triple("paste", R.string.paste, R.drawable.ic_clipboard_paste), Triple("delete", R.string.delete, R.drawable.ic_trash_2)).forEach { (action, label, icon) ->
            TextButton(onClick = { when (action) { "copy" -> model.copySelection(false); "cut" -> model.copySelection(true); "paste" -> model.requestPaste(); else -> model.requestDelete() } },
                enabled = !ui.busy && if (action == "paste") clip != null else count > 0,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (action == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Glyph(icon); Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
