package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

@Composable
internal fun ClipboardDialogs(model: WorkspaceViewModel) {
    val ui = model.ui
    val workspace = ui.workspace ?: return
    model.pasteRequest?.let { request ->
        val clip = request.clipboard
        var destination by rememberSaveable(clip.token, request.targets) {
            mutableStateOf(request.targets.suggested?.takeIf { workspace.pasteFailure(clip.ids, clip.cut, it) == null })
        }
        var others by rememberSaveable(clip.token, request.targets) { mutableStateOf(false) }
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(clip.token, request.targets) { cancelFocus.requestFocus() }
        val stale = model.clipboard?.token != clip.token
        val failure = if (stale) Failure.Missing else destination?.let { workspace.pasteFailure(clip.ids, clip.cut, it) }
        AlertDialog(onDismissRequest = model::dismissPaste,
            title = { Text(stringResource(if (request.targets.related.size > 1) R.string.paste_choose_one else R.string.paste_confirm_location)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (clip.cut) R.string.clipboard_cut_source else R.string.clipboard_copy_source, clip.items.size), style = MaterialTheme.typography.titleSmall)
                    clip.items.forEach { source ->
                        val current = if (clip.cut) workspace.entry(source.entry.id) else null
                        Column {
                            Text((current ?: source.entry).filename, style = MaterialTheme.typography.bodyMedium)
                            Text(current?.parent?.let(workspace::path) ?: source.location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (request.targets.related.size > 1) Text(stringResource(R.string.paste_multiple_locations), style = MaterialTheme.typography.bodySmall)
                    val candidates = request.targets.related + listOfNotNull(request.targets.common)
                    candidates.forEach { id ->
                        FolderChoice(workspace, clip, id, destination, if (id == request.targets.common) R.string.paste_common_parent else R.string.paste_related_folder, !ui.busy && !stale) { destination = id }
                    }
                    TextButton(onClick = { others = !others }, enabled = !ui.busy) { Text(stringResource(if (request.targets.related.size > 1) R.string.paste_other_folders else R.string.paste_change_location)) }
                    if (others) workspace.entries.filter { it.folder && it.id !in candidates }.sortedBy { workspace.path(it.id) }.forEach { entry ->
                        FolderChoice(workspace, clip, entry.id, destination, null, !ui.busy && !stale) { destination = entry.id }
                    }
                    Text(stringResource(if (clip.cut) R.string.paste_cut_note else R.string.paste_copy_note), style = MaterialTheme.typography.labelSmall)
                    if (failure != null) Text(stringResource(errorLabel(failure)), color = MaterialTheme.colorScheme.error)
                    OperationFailure(model)
                }
            },
            confirmButton = { TextButton(enabled = !ui.busy && destination != null && failure == null, onClick = { destination?.let(model::confirmPaste) }) { Text(stringResource(R.string.paste_here)) } },
            dismissButton = { TextButton(onClick = model::dismissPaste, enabled = !ui.busy, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) } },
        )
    }
    model.deleteRequest?.let { ids ->
        val roots = workspace.selectionRoots(ids)
        val removed = roots.flatMap { workspace.descendants(it.id) }.toSet()
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(ids) { cancelFocus.requestFocus() }
        AlertDialog(onDismissRequest = model::dismissDelete,
            title = { Text(stringResource(R.string.delete_many_title, roots.size)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    roots.forEach { entry -> Column {
                        Text(entry.filename)
                        Text(workspace.path(entry.parent ?: Workspace.ROOT), style = MaterialTheme.typography.labelSmall)
                        if (entry.folder) Text(stringResource(R.string.includes_children), style = MaterialTheme.typography.labelSmall)
                    } }
                    Text(stringResource(R.string.delete_many_warning, workspace.entries.count { it.folder && it.id in removed }, workspace.entries.count { !it.folder && it.id in removed }))
                    OperationFailure(model)
                }
            },
            confirmButton = { TextButton(enabled = !ui.busy && ids.all { workspace.entry(it) != null }, onClick = model::confirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = model::dismissDelete, enabled = !ui.busy, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FolderChoice(workspace: Workspace, clip: WorkspaceViewModel.Clipboard, id: String, selected: String?, reason: Int?, enabled: Boolean, choose: () -> Unit) {
    val failure = workspace.pasteFailure(clip.ids, clip.cut, id)
    Row(Modifier.fillMaxWidth().selectable(selected == id, enabled = enabled && failure == null, role = Role.RadioButton, onClick = choose).padding(vertical = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == id, onClick = null, enabled = enabled && failure == null)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(workspace.path(id), style = MaterialTheme.typography.bodyMedium)
            if (failure != null || reason != null) Text(stringResource(failure?.let(::errorLabel) ?: reason ?: R.string.folder), style = MaterialTheme.typography.labelSmall, color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OperationFailure(model: WorkspaceViewModel) {
    model.ui.error?.let { error ->
        Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = model::reload, enabled = !model.ui.busy) { Text(stringResource(R.string.reload_directory)) }
    }
}
