package io.github.xrisium.makudon.feature.workspace

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.TrashNode
import io.github.xrisium.makudon.ui.theme.EditorStyle
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrashPage(model: WorkspaceViewModel) {
    val inventory = model.trashInventory
    val busy = model.ui.busy
    val folder = inventory.nodes.firstOrNull { it.id == model.trashFolder }
    val preview = inventory.nodes.firstOrNull { it.id == model.trashPreview }
    val selection = model.trashSelection
    var menu by remember { mutableStateOf(false) }
    var purge by remember { mutableStateOf<List<String>?>(null) }
    val visible = inventory.nodes.filter { it.parent == folder?.id }.sortedByDescending { it.deleted }
    BackHandler(purge != null) { if (!busy) purge = null }
    Column(Modifier.fillMaxSize()) {
        PageBar(preview?.entry?.name ?: folder?.entry?.name ?: stringResource(R.string.trash_title), model::back)
        if (preview == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selection != null) stringResource(R.string.selected_count, selection.size) else stringResource(R.string.trash_summary, inventory.roots.size, Formatter.formatShortFileSize(LocalContext.current, inventory.bytes)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (selection != null) TextButton(onClick = model::exitTrashSelection, enabled = !busy) { Text(stringResource(R.string.exit_selection)) }
                else Box {
                    Tool(R.drawable.ic_more_horizontal, stringResource(R.string.item_actions), enabled = !busy) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.trash_select)) }, enabled = visible.isNotEmpty(), onClick = { menu = false; model.selectTrash() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.trash_clear), color = MaterialTheme.colorScheme.error) }, enabled = inventory.roots.isNotEmpty(), onClick = { menu = false; purge = inventory.roots.map { it.id } })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(if (preview == null) R.string.trash_retention else R.string.trash_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (inventory.cleanupPending) item {
                Column {
                    Text(stringResource(R.string.trash_cleanup_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::retryTrashCleanup, enabled = !busy) { Text(stringResource(R.string.trash_retry_cleanup)) }
                }
            }
            model.trashRestored?.let { result -> item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp)) {
                        result.entries.forEachIndexed { i, entry -> Text(stringResource(R.string.trash_restored_item, entry.name, result.locations[i]), style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = model::viewRestored, enabled = !busy) { Text(stringResource(R.string.trash_view_restored)) }
                    }
                }
            } }
            if (preview != null) {
                item { TrashMetadata(preview, model) }
                item { TrashActions(busy, { model.requestTrashRestore(listOf(preview.id)) }, { purge = listOf(preview.id) }) }
                item { SelectionContainer { Text(model.trashText.orEmpty(), Modifier.fillMaxWidth(), style = EditorStyle) } }
            } else {
                if (folder != null && selection == null) {
                    item { TrashMetadata(folder, model) }
                    item {
                        Column {
                            TextButton(onClick = { model.requestTrashRestore(listOf(folder.id)) }, enabled = !busy) { Text(stringResource(R.string.trash_restore_folder)) }
                            TextButton(onClick = { purge = listOf(folder.id) }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.trash_purge_folder)) }
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Glyph(R.drawable.ic_trash_2, Modifier.size(32.dp))
                        Text(stringResource(if (folder == null) R.string.trash_empty else R.string.trash_folder_empty), Modifier.padding(top = 16.dp))
                        if (folder == null) Text(stringResource(R.string.trash_empty_hint), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(visible, key = { it.id }) { node ->
                    val selectLabel = stringResource(R.string.trash_item_select, node.entry.name)
                    val openLabel = stringResource(R.string.trash_item_open, node.entry.name)
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().semantics {
                            customActions = listOf(CustomAccessibilityAction(selectLabel) { model.selectTrash(node.id); true })
                        }.combinedClickable(enabled = !busy, onClickLabel = if (selection == null) openLabel else selectLabel,
                            onClick = { if (selection == null) model.browseTrash(node.id) else model.toggleTrash(node.id) },
                            onLongClickLabel = selectLabel, onLongClick = { model.selectTrash(node.id) }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(when (node.entry.kind) {
                                io.github.xrisium.makudon.domain.DocumentKind.Folder -> R.drawable.ic_folder
                                io.github.xrisium.makudon.domain.DocumentKind.Plain -> R.drawable.ic_file_text
                                io.github.xrisium.makudon.domain.DocumentKind.Markdown -> R.drawable.ic_markdown
                            }); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(node.entry.name, style = MaterialTheme.typography.titleSmall)
                                TrashMetadata(node, model)
                            }
                            if (selection != null) Checkbox(checked = node.id in selection, onCheckedChange = null, enabled = !busy)
                            else Glyph(R.drawable.ic_chevron_right)
                        }
                    }
                }
            }
        }
        if (selection != null) {
            HorizontalDivider()
            TrashActions(busy || selection.isEmpty(), { model.requestTrashRestore(selection.toList()) }, { purge = selection.toList() })
        }
    }
    model.trashRestoreRequest?.let { ids ->
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(ids) { cancelFocus.requestFocus() }
        AlertDialog(onDismissRequest = model::dismissTrashRestore, title = { Text(stringResource(R.string.trash_restore_confirm)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ids.mapNotNull { id -> inventory.nodes.firstOrNull { it.id == id } }.forEach { Text(it.entry.name) }
                Text(stringResource(R.string.trash_root_warning)); Text(stringResource(R.string.trash_conflict_hint))
            } },
            confirmButton = { TextButton(onClick = model::confirmTrashRestore, enabled = !busy) { Text(stringResource(R.string.trash_restore)) } },
            dismissButton = { TextButton(onClick = model::dismissTrashRestore, enabled = !busy, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) } })
    }
    purge?.let { ids ->
        val roots = inventory.selection(ids)
        val removed = roots.flatMap { inventory.descendants(it.id) }.toSet()
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(ids) { cancelFocus.requestFocus() }
        AlertDialog(onDismissRequest = { if (!busy) purge = null }, title = { Text(stringResource(R.string.trash_purge_title, roots.size)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                roots.forEach { Text(it.entry.name) }
                Text(stringResource(R.string.trash_purge_warning, inventory.nodes.count { it.id in removed && it.entry.folder }, inventory.nodes.count { it.id in removed && !it.entry.folder }))
                Text(stringResource(R.string.trash_copies_hint), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { purge = null; model.purgeTrash(ids) }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.trash_purge)) } },
            dismissButton = { TextButton(onClick = { purge = null }, enabled = !busy, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun TrashActions(disabled: Boolean, restore: () -> Unit, purge: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = restore, enabled = !disabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.trash_restore)) }
        TextButton(onClick = purge, enabled = !disabled, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.trash_purge)) }
    }
}

@Composable
private fun TrashMetadata(node: TrashNode, model: WorkspaceViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.trash_original_location, node.location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.trash_deleted_time, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(node.deleted))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (node.entry.folder) {
            val children = model.trashInventory.descendants(node.id) - node.id
            Text(stringResource(R.string.trash_folder_count, model.trashInventory.nodes.count { it.id in children && it.entry.folder }, model.trashInventory.nodes.count { it.id in children && !it.entry.folder }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
