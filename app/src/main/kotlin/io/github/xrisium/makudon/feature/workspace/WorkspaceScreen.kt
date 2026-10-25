package io.github.xrisium.makudon.feature.workspace

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.EditorStyle
import java.text.DateFormat
import java.util.Date

@Composable
fun Glyph(icon: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = LocalContentColor.current) {
    Icon(painterResource(icon), description, modifier.size(20.dp), tint = tint)
}

@Composable
fun Tool(icon: Int, label: String, enabled: Boolean = true, active: Boolean = false, rotation: Float = 0f, onClick: () -> Unit) {
    val background by animateColorAsState(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, tween(160), label = "tool-selection")
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).background(background, RoundedCornerShape(15.dp)), enabled = enabled) {
        Glyph(icon, Modifier.rotate(rotation), description = label)
    }
}

@Composable
internal fun WorkspaceNotice(model: WorkspaceViewModel) {
    val ui = model.ui
    if (ui.error != null || ui.message != null) {
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val opacity by animateFloatAsState(if (appeared) 1f else 0f, tween(180), label = "notice")
        Surface(color = if (ui.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(12.dp).alpha(opacity)) {
            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.error?.let { stringResource(errorLabel(it)) } ?: stringResource(ui.message ?: R.string.error_storage), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                if (ui.error != null && ui.page != Page.Editor) TextButton(onClick = model::reload, enabled = !ui.busy) { Text(stringResource(R.string.retry)) }
                Tool(R.drawable.ic_x, stringResource(R.string.close), onClick = model::dismissError)
            }
        }
    }
}

@Composable
fun WorkspaceScreen(model: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val ui = model.ui
    val workspace = ui.workspace
    val screens = rememberSaveableStateHolder()
    var dialog by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<Entry?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> model.import(result.data?.data) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { model.export(it) }
    fun export() { if (model.prepareExport()) exporter.launch(model.pendingExportName) }
    fun importDocument() {
        importer.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"))
            putExtra(Intent.EXTRA_LOCAL_ONLY, true); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
    BackHandler(ui.page !in MainPages || ui.transitioning) { model.back() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (ui.page != Page.Directory || workspace == null) WorkspaceNotice(model)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (workspace == null) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (ui.busy) CircularProgressIndicator(Modifier.size(28.dp))
                        Text(stringResource(if (ui.busy) R.string.loading else R.string.error_corrupt), Modifier.padding(16.dp))
                        if (!ui.busy) TextButton(onClick = model::reload) { Text(stringResource(R.string.retry)) }
                    }
                } else WorkspacePages(model, screens,
                    onAction = { item, action ->
                        target = item
                        when (action) {
                            "select" -> model.startSelection(item.id)
                            "info", "rename" -> dialog = action
                            "delete" -> if (model.selection != null) model.requestDelete() else dialog = action
                            "copy" -> if (model.selection != null) model.copySelection(false) else model.copy(item.id, false)
                            "cut" -> if (model.selection != null) model.copySelection(true) else model.copy(item.id, true)
                            "paste" -> model.requestPaste(if (model.selection != null) null else item.id)
                            "folder" -> dialog = "folder"
                            "document" -> model.newDocument(Page.Directory, item.id)
                        }
                    },
                    onNewFolder = { target = workspace.entry(ui.selected); dialog = "folder" },
                ) { page ->
                    when (page) {
                        Page.New -> NewPage(model, ::importDocument)
                        Page.Editor -> EditorScreen(model, onExport = ::export)
                        Page.Licenses -> Column {
                            PageBar(stringResource(R.string.licenses), model::back)
                            Text(model.licenseText, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Page.Previous -> Column {
                            PageBar(stringResource(R.string.previous_version), model::back)
                            TextButton(onClick = { dialog = "restore" }, enabled = !ui.busy, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.restore_previous)) }
                            Text(ui.previous.orEmpty(), Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), style = EditorStyle)
                        }
                        else -> Unit
                    }
                }
                if (ui.busy && !ui.savingPreferences && workspace != null) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
    ClipboardDialogs(model)
    val entry = target?.id?.let { workspace?.entry(it) }
    when (dialog) {
        "folder", "rename" -> if (entry != null && workspace != null) NameDialog(
            entry, workspace, dialog == "folder", ui.busy, ui.error,
            onDismiss = { if (!ui.busy) dialog = null },
            onConfirm = { name -> if (dialog == "folder") model.createFolder(name, entry.id) { dialog = null } else model.rename(entry.id, name) { dialog = null } },
        )
        "delete" -> if (entry != null && workspace != null) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(entry.id) { cancelFocus.requestFocus() }
            val descendants = workspace.descendants(entry.id)
            val folders = workspace.entries.count { it.folder && it.id != entry.id && it.id in descendants }
            val documents = workspace.entries.count { !it.folder && it.id in descendants }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(if (entry.folder) R.string.delete_folder_title else R.string.delete_file_title)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.location, workspace.path(entry.parent ?: Workspace.ROOT)), style = MaterialTheme.typography.bodySmall)
                    Text(if (entry.folder) stringResource(R.string.delete_folder_warning, entry.name, folders, documents) else stringResource(R.string.delete_file_warning, entry.filename))
                }
            }, confirmButton = { TextButton(onClick = { model.delete(entry.id); dialog = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { dialog = null }, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) } })
        }
        "info" -> if (entry != null && workspace != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.more_info)) }, text = {
            Text(if (entry.folder) stringResource(R.string.folder_details, entry.name, workspace.path(entry.id), workspace.entries.count { it.parent == entry.id }) else stringResource(R.string.info_details, entry.filename, stringResource(kindLabel(entry.kind)), workspace.path(entry.parent ?: Workspace.ROOT), entry.bytes))
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } }, dismissButton = { if (!entry.folder) TextButton(onClick = { model.previous(entry.id); dialog = null }) { Text(stringResource(R.string.previous_version)) } })
        "restore" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.restore_previous)) }, text = { Text(stringResource(R.string.restore_warning)) }, confirmButton = { TextButton(onClick = { model.restorePrevious(); dialog = null }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun RecentPage(workspace: Workspace, busy: Boolean, onOpen: (String) -> Unit, onNew: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(end = 8.dp), contentAlignment = Alignment.CenterStart) {
                Icon(painterResource(R.drawable.makudon_lockup), stringResource(R.string.brand_name), Modifier.widthIn(max = 158.dp).fillMaxWidth().aspectRatio(340.524f / 50f), tint = MaterialTheme.colorScheme.onBackground)
            }
            Tool(R.drawable.ic_plus, stringResource(R.string.new_document), enabled = !busy, onClick = onNew)
        }
        val recent = workspace.entries.filter { !it.folder && it.opened > 0 }.sortedByDescending { it.opened }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recent.isEmpty()) item { Text(stringResource(R.string.empty_recent), Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(recent, key = { it.id }) { item ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(180), fadeOutSpec = tween(180)).fillMaxWidth().clickable(enabled = !busy) { onOpen(item.id) }) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (item.unreadable) stringResource(R.string.open_failed) else item.excerpt.ifBlank { stringResource(R.string.empty_excerpt) }, style = EditorStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(workspace.path(item.parent ?: Workspace.ROOT) + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modified)) + "  ·  " + stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PageBar(title: String, back: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool(R.drawable.ic_arrow_left, stringResource(R.string.back), onClick = back)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

fun kindLabel(kind: DocumentKind) = when (kind) { DocumentKind.Folder -> R.string.folder; DocumentKind.Plain -> R.string.plain_text; DocumentKind.Markdown -> R.string.markdown }
fun errorLabel(failure: Failure) = when (failure) {
    Failure.NoChange -> R.string.paste_already_here
    Failure.ClipboardTooLarge -> R.string.clipboard_too_large
    Failure.CatalogFull -> R.string.catalog_full
    Failure.InvalidName -> R.string.error_invalid_name; Failure.Conflict -> R.string.error_conflict; Failure.Cycle -> R.string.error_cycle
    Failure.Missing -> R.string.error_missing; Failure.InvalidText -> R.string.error_invalid_text; Failure.TooLarge -> R.string.error_too_large
    Failure.Storage -> R.string.error_storage; Failure.Corrupt -> R.string.error_corrupt; Failure.NoPrevious -> R.string.error_no_previous; Failure.Cleanup -> R.string.error_cleanup
}

@Composable
private fun NameDialog(entry: Entry, workspace: Workspace, create: Boolean, busy: Boolean, failure: Failure?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(entry.id, create) { mutableStateOf(if (create) "" else entry.name) }
    var localError by remember { mutableStateOf<Failure?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (create) R.string.new_folder else R.string.rename)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.location, workspace.path(if (create) entry.id else entry.parent ?: Workspace.ROOT)), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it; localError = null }, label = { Text(stringResource(R.string.name)) }, singleLine = true, enabled = !busy, shape = RoundedCornerShape(14.dp), suffix = { if (!create && !entry.folder) Text("." + entry.kind.extension) }, modifier = Modifier.fillMaxWidth().testTag("entry-name"))
            if (!create && !entry.folder) Text(stringResource(R.string.type_unchanged), style = MaterialTheme.typography.bodySmall)
            (localError ?: failure)?.let { Text(stringResource(errorLabel(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            TextRules.checkName(name)
            if (TextRules.conflicts(workspace.entries, name, if (create) entry.id else entry.parent ?: Workspace.ROOT, if (create) DocumentKind.Folder else entry.kind, if (create) null else entry.id)) throw WorkspaceException(Failure.Conflict)
            onConfirm(name)
        } catch (e: WorkspaceException) { localError = e.reason }
    }) { Text(stringResource(if (create) R.string.create else R.string.rename)) } }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
