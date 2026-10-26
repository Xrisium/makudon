package io.github.xrisium.makudon.feature.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import java.text.DateFormat
import java.util.Date

fun restoreModeLabel(mode: RestoreMode) = when (mode) {
    RestoreMode.Separate -> R.string.backup_separate
    RestoreMode.Incremental -> R.string.backup_incremental
    RestoreMode.Replace -> R.string.backup_replace
}
fun restoreModeDescription(mode: RestoreMode) = when (mode) {
    RestoreMode.Separate -> R.string.backup_separate_description
    RestoreMode.Incremental -> R.string.backup_incremental_description
    RestoreMode.Replace -> R.string.backup_replace_description
}
private fun choiceLabel(choice: ConflictChoice) = when (choice) {
    ConflictChoice.Local -> R.string.backup_keep_local
    ConflictChoice.Backup -> R.string.backup_use_archive
    ConflictChoice.Both -> R.string.backup_keep_both
}

@Composable
fun BackupPage(model: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val backup = model.backup
    val state = backup.ui
    val inventory = state.inventory
    val plan = state.plan
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(inventory != null, plan?.mode, plan?.decisions?.size, state.exportReady, state.exportRenames != null) {
        list.scrollToItem(0)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), backup::read)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), backup::export)
    Column(modifier.fillMaxSize()) {
        PageBar(stringResource(R.string.backup_title), model::back)
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(state.progress ?: R.string.backup_checking), Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { error -> item {
                Text(stringResource(if (error == Failure.Cleanup) R.string.backup_done_cleanup else errorLabel(error)), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.exportReady) Text(stringResource(R.string.backup_export_retry_hint), style = MaterialTheme.typography.bodySmall)
                if (state.needsReload) TextButton(onClick = backup::refresh, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
            } }
            state.message?.let { item { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            when {
                state.exportReady -> item {
                    Text(stringResource(R.string.backup_export_ready))
                    Button(onClick = { exporter.launch(backup.pendingExportName) }, enabled = !state.busy) { Text(stringResource(R.string.backup_save_zip)) }
                }
                state.exportRenames != null -> {
                    item { Text(stringResource(R.string.backup_rename_notice)) }
                    items(state.exportRenames) { rename -> Text(stringResource(R.string.backup_rename_item, rename.from, rename.to), style = MaterialTheme.typography.bodySmall) }
                    item { Button(onClick = backup::confirmExportNames, enabled = !state.busy) { Text(stringResource(R.string.backup_confirm_names)) } }
                }
                inventory != null -> {
                    item { Text(stringResource(R.string.backup_archive_count, inventory.documents, inventory.nodes.count { it.kind == DocumentKind.Folder }), style = MaterialTheme.typography.titleMedium) }
                    if (plan == null) {
                        items(RestoreMode.entries) { mode ->
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (state.mode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.fillMaxWidth().selectable(selected = state.mode == mode, enabled = !state.busy,
                                    role = Role.RadioButton, onClick = { backup.chooseMode(mode) }).padding(16.dp)) {
                                    RadioButton(selected = state.mode == mode, onClick = null)
                                    Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(restoreModeLabel(mode)), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(restoreModeDescription(mode)), style = MaterialTheme.typography.bodySmall,
                                            color = if (mode == RestoreMode.Replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item { Button(onClick = backup::preview, enabled = !state.busy) { Text(stringResource(R.string.backup_preview)) } }
                    } else {
                        item {
                            Text(stringResource(restoreModeLabel(plan.mode)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(restoreModeDescription(plan.mode)), style = MaterialTheme.typography.bodySmall,
                                color = if (plan.mode == RestoreMode.Replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.backup_plan_counts, plan.added, plan.updated, plan.unchanged, plan.removed), Modifier.padding(top = 12.dp))
                            plan.folder?.let { Text(stringResource(R.string.backup_target_folder, it), Modifier.padding(top = 8.dp)) }
                        }
                        val unresolved = plan.conflicts.firstOrNull { it.source.id !in plan.decisions }
                        if (unresolved != null) item(key = unresolved.source.id) {
                            ConflictCard(unresolved, plan.conflicts.indexOf(unresolved) + 1, plan.conflicts.size, state.busy) { choice -> backup.decide(unresolved.source.id, choice) }
                        }
                        items(plan.conflicts.filter { it.source.id in plan.decisions }, key = { "resolved-${it.source.id}" }) { conflict ->
                            Column {
                                Text(stringResource(R.string.backup_resolved_choice, conflict.path, stringResource(choiceLabel(checkNotNull(plan.decisions[conflict.source.id])))), style = MaterialTheme.typography.bodySmall)
                                var edit by remember { mutableStateOf(false) }
                                TextButton(onClick = { edit = !edit }, enabled = !state.busy) { Text(stringResource(R.string.backup_edit_choice)) }
                                if (edit) ConflictCard(conflict, plan.conflicts.indexOf(conflict) + 1, plan.conflicts.size, state.busy) { choice -> backup.decide(conflict.source.id, choice); edit = false }
                            }
                        }
                        item {
                            Button(onClick = { confirm = "restore" }, enabled = !state.busy && plan.resolved && !state.needsReload) { Text(stringResource(R.string.backup_execute)) }
                            TextButton(onClick = { backup.chooseMode(state.mode) }, enabled = !state.busy) { Text(stringResource(R.string.backup_change_mode)) }
                        }
                    }
                    if (inventory.renames.isNotEmpty()) {
                        item { Text(stringResource(R.string.backup_rename_notice)) }
                        items(inventory.renames) { Text(stringResource(R.string.backup_rename_item, it.from, it.to), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (inventory.skipped.isNotEmpty()) {
                        item { Text(stringResource(R.string.backup_skipped_count, inventory.skipped.size), style = MaterialTheme.typography.titleSmall) }
                        items(inventory.skipped) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                else -> {
                    item { Text(stringResource(R.string.backup_format_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Button(onClick = backup::prepareExport, enabled = !backup.locked) { Text(stringResource(R.string.backup_export)) } }
                    item { OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !backup.locked) { Text(stringResource(R.string.backup_import)) } }
                    state.snapshot?.let { snapshot -> item {
                        HorizontalDivider()
                        Text(stringResource(R.string.backup_snapshot), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.backup_snapshot_details,
                            if (snapshot.time > 0) DateFormat.getDateTimeInstance().format(Date(snapshot.time)) else stringResource(R.string.backup_cleanup_pending),
                            snapshot.documents, snapshot.bytes / 1024), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                        if (snapshot.retired > 0) Text(stringResource(R.string.backup_cleanup_pending), color = MaterialTheme.colorScheme.error)
                        if (snapshot.time > 0) OutlinedButton(onClick = { confirm = "undo" }, enabled = !backup.locked) { Text(stringResource(R.string.backup_undo)) }
                        TextButton(onClick = { confirm = "clear" }, enabled = !backup.locked) { Text(stringResource(R.string.backup_clear_snapshot)) }
                    } }
                }
            }
            if (backup.locked && !state.needsReload) item {
                TextButton(onClick = { backup.cancel() }, enabled = !state.committing) { Text(stringResource(R.string.backup_cancel)) }
            }
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(when (confirm) { "undo" -> R.string.backup_undo; "clear" -> R.string.backup_clear_snapshot; else -> R.string.backup_confirm_restore })) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (confirm) {
                "undo" -> Text(stringResource(R.string.backup_undo_warning))
                "clear" -> Text(stringResource(R.string.backup_clear_warning))
                else -> if (plan != null) {
                    Text(stringResource(restoreModeLabel(plan.mode)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(restoreModeDescription(plan.mode)), color = if (plan.mode == RestoreMode.Replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.backup_replace_count, if (plan.mode == RestoreMode.Replace) model.ui.workspace?.entries?.count { !it.folder } ?: 0 else 0))
                    Text(stringResource(R.string.backup_confirm_summary, plan.added, plan.updated, inventory?.skipped?.size ?: 0))
                    Text(stringResource(R.string.backup_snapshot_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !state.busy, onClick = {
            when (confirm) { "undo" -> backup.undo(); "clear" -> backup.clearSnapshot(); else -> backup.restore() }
            confirm = null
        }, colors = ButtonDefaults.textButtonColors(contentColor = if (confirm == "clear" || plan?.mode == RestoreMode.Replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(stringResource(R.string.confirm)) }
    }, dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ConflictCard(conflict: RestoreConflict, index: Int, total: Int, busy: Boolean, confirm: (ConflictChoice) -> Unit) {
    var selected by remember(conflict.source.id) { mutableStateOf(ConflictChoice.Both) }
    OutlinedCard(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_conflict_number, index, total), style = MaterialTheme.typography.titleSmall)
            Text(conflict.path)
            Text(stringResource(R.string.backup_content_differs), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.backup_local_info, conflict.local.bytes, DateFormat.getDateTimeInstance().format(Date(conflict.local.modified))), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.backup_archive_info, conflict.source.bytes, DateFormat.getDateTimeInstance().format(Date(conflict.source.modified))), style = MaterialTheme.typography.bodySmall)
            ConflictChoice.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().selectable(selected == choice, enabled = !busy, role = Role.RadioButton, onClick = { selected = choice }).heightIn(min = 48.dp)) {
                    RadioButton(selected == choice, onClick = null)
                    Text(stringResource(choiceLabel(choice)), Modifier.padding(start = 8.dp, top = 12.dp))
                }
            }
            Button(onClick = { confirm(selected) }, enabled = !busy) { Text(stringResource(R.string.backup_confirm_conflict)) }
        }
    }
}
