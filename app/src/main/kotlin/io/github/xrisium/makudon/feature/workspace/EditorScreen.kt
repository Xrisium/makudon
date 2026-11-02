package io.github.xrisium.makudon.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.EditorStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(model: WorkspaceViewModel, onExport: () -> Unit, onAction: (String) -> Unit) {
    val ui = model.ui
    val field = model.textState ?: return
    val session = model.save ?: return
    val entry = ui.workspace?.entry(ui.activeId.orEmpty()) ?: return
    val status by session.status.collectAsState()
    val colors = MaterialTheme.colorScheme
    val labelMeasurer = rememberTextMeasurer()
    val textSize = ui.workspace?.editorTextSize ?: EditorTextSize.Medium
    val scale = textSize.sp / 18f
    val bodyStyle = EditorStyle.copy(fontSize = textSize.sp.sp, lineHeight = (33 * scale).sp)
    val markdown = entry.kind == DocumentKind.Markdown
    val reading = markdown && ui.editorMode == EditorMode.Reading
    val editable = !ui.busy && !ui.transitioning && !ui.storageBlocked && ui.page == Page.Editor
    val controlsEnabled = (!ui.busy || ui.savingPreferences) && !ui.transitioning && !ui.storageBlocked && ui.page == Page.Editor
    var more by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var tools by rememberSaveable(entry.id) { mutableStateOf(false) }
    var findOpen by rememberSaveable(entry.id) { mutableStateOf(false) }
    var query by rememberSaveable(entry.id) { mutableStateOf("") }
    var replacement by rememberSaveable(entry.id) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(entry.id) { mutableStateOf(true) }
    var target by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    var linkToken by remember { mutableStateOf<WorkspaceViewModel.EditToken?>(null) }
    val source = field.text.toString()
    val document = model.markdownDocument?.takeIf { it.source == source }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val uriHandler = remember(context) { object : UriHandler {
        override fun openUri(uri: String) {
            if (uri.substringBefore(':').lowercase() !in setOf("http", "https", "mailto")) { model.editorMessageForLink(); return }
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri.toUri())) }
            catch (_: android.content.ActivityNotFoundException) { model.editorMessageForLink() }
            catch (_: SecurityException) { model.editorMessageForLink() }
        }
    } }
    val matches = remember(source, query, caseSensitive, reading, document) {
        if (!reading) LiteralSearch.matches(source, query, caseSensitive)
        else document?.root?.children?.flatMap { LiteralSearch.matches(mdText(it, document, colors.primary).text, query, caseSensitive) }.orEmpty()
    }
    LaunchedEffect(reading) { if (reading) { focusManager.clearFocus(); keyboard?.hide() } }
    LaunchedEffect(query, caseSensitive, target, reading, findOpen) {
        if (!reading && findOpen && matches.isNotEmpty()) {
            val match = matches[Math.floorMod(target, matches.size)]
            field.edit { selection = TextRange(match.start, match.end) }
        }
    }
    BackHandler(model.immersive || findOpen) { if (model.immersive) model.immersive = false else findOpen = false }
    fun changeMode(mode: EditorMode) {
        if (mode == EditorMode.Reading) { model.readingAnchor = model.editorAnchor; model.readingAnchorOffset = model.editorAnchorOffset; focusManager.clearFocus(); keyboard?.hide() }
        else if (reading) { model.editorAnchor = model.readingAnchor; model.editorAnchorOffset = model.readingAnchorOffset }
        model.editorMode(mode)
    }
    fun replace(all: Boolean) {
        if (reading || !editable || query.isEmpty()) return
        try {
            val token = model.editToken()
            val current = field.text.toString()
            val found = LiteralSearch.matches(current, query, caseSensitive)
            val result = if (all) LiteralSearch.replaceAll(current, query, replacement, caseSensitive, model.currentSelection()) else {
                val match = found.getOrNull(if (found.isEmpty()) 0 else Math.floorMod(target, found.size)) ?: return
                MarkdownEdits.applyEdits(current, listOf(SourceEdit(match, replacement)), model.currentSelection())
            }
            model.applyEditorEdit(result, token)
        } catch (e: WorkspaceException) { model.inputRejected(e.reason) }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    Column(Modifier.fillMaxSize()) {
        if (!model.immersive) PageBar(entry.name, model::back, onTitleClick = { if (editable) onAction("rename") }, titleEnabled = controlsEnabled) {
            if (!reading) Tool(R.drawable.ic_undo_2, stringResource(R.string.undo), enabled = controlsEnabled && model.canUndo, onClick = { model.undo() })
            if (!reading) Tool(R.drawable.ic_redo_2, stringResource(R.string.redo), enabled = controlsEnabled && model.canRedo, onClick = { model.undo(true) })
            Box {
                Tool(R.drawable.ic_more_horizontal, stringResource(R.string.item_actions), enabled = controlsEnabled, onClick = { if (editable) more = !more })
                DropdownMenu(more, { more = false }, shape = RoundedCornerShape(18.dp), containerColor = colors.surface, modifier = Modifier.widthIn(min = 260.dp, max = 300.dp)) {
                    DropdownMenuItem(text = { Text(stringResource(if (reading) R.string.md_find_reading else R.string.md_find)) }, leadingIcon = { Glyph(R.drawable.ic_search) }, onClick = { findOpen = true; more = false })
                    if (reading) DropdownMenuItem(text = { Text(stringResource(R.string.md_immersive)) }, onClick = { model.immersive = true; more = false })
                    EditorTextSizePicker(textSize, enabled = controlsEnabled) { if (editable) model.preferences(editorTextSize = it) }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    DropdownMenuItem(text = { Text(stringResource(R.string.detailed_info)) }, leadingIcon = { Glyph(R.drawable.ic_info) }, enabled = controlsEnabled, onClick = { if (editable) { more = false; onAction("info") } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Glyph(R.drawable.ic_pencil_line) }, enabled = controlsEnabled, onClick = { if (editable) { more = false; onAction("rename") } })
                    DropdownMenuItem(text = { Text(stringResource(if (markdown) R.string.md_export_md else R.string.export_copy)) }, leadingIcon = { Glyph(R.drawable.ic_download) }, enabled = controlsEnabled, onClick = { if (editable) { more = false; onExport() } })
                    if (markdown) DropdownMenuItem(text = { Text(stringResource(R.string.md_export_txt)) }, onClick = { model.exportCopy(true); more = false })
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = colors.error) }, leadingIcon = { Glyph(R.drawable.ic_trash_2, tint = colors.error) }, enabled = controlsEnabled, onClick = { if (editable) { more = false; onAction("delete") } })
                }
            }
        }
        if (!model.immersive) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (markdown) {
                    val position by animateFloatAsState(ui.editorMode.ordinal.toFloat(), tween(160), label = "editor-view-selection")
                    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
                        val labelWidth = with(LocalDensity.current) { (maxWidth / 3 - 8.dp).toPx() }
                        Canvas(Modifier.matchParentSize()) {
                            drawRoundRect(colors.surface, topLeft = Offset(size.width / 3 * position, 0f), size = Size(size.width / 3, size.height), cornerRadius = CornerRadius(11.dp.toPx()))
                        }
                        Row {
                            listOf(EditorMode.Source to R.string.source_view, EditorMode.Typeset to R.string.typeset_view, EditorMode.Reading to R.string.reading_view).forEach { (mode, label) ->
                                val fullLabel = stringResource(label)
                                val compact = when (mode) { EditorMode.Source -> R.string.source_view_compact; EditorMode.Typeset -> R.string.typeset_view_compact; EditorMode.Reading -> R.string.reading_view_compact }
                                val display = if (labelMeasurer.measure(fullLabel, style = MaterialTheme.typography.labelMedium, softWrap = false).size.width > labelWidth) stringResource(compact) else fullLabel
                                TextButton(onClick = { if (editable) changeMode(mode) }, enabled = controlsEnabled, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f).semantics { selected = mode == ui.editorMode; contentDescription = fullLabel }) {
                                    Text(display, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                } else Text(stringResource(R.string.plain_text), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (status.phase) { SavePhase.Saved -> R.string.save_saved; SavePhase.Waiting -> R.string.save_waiting; SavePhase.Saving -> R.string.save_saving; SavePhase.Failed -> R.string.save_failed }),
                    Modifier.padding(top = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall,
                    color = if (status.phase == SavePhase.Failed) colors.error else colors.onSurfaceVariant)
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
        if (status.phase == SavePhase.Failed) Surface(color = colors.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.save_failure_help), style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = model::retrySave, enabled = !ui.busy) { Text(stringResource(R.string.retry)) }; TextButton(onClick = onExport) { Text(stringResource(R.string.export_copy)) } }
            }
        }
        model.editorMessage?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        if (findOpen) EditorFindBar(reading, query, replacement, caseSensitive, matches.size, target,
            { query = it; target = 0 }, { replacement = it }, { caseSensitive = it; target = 0 }, { target = it }, ::replace, { findOpen = false })
        if (reading) {
            if (document != null) MarkdownReader(model, document, bodyStyle, Modifier.weight(1f).fillMaxWidth().testTag("reading-view"), if (findOpen) query else "", caseSensitive, target)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.md_preparing)) }
        } else MarkdownEditingField(model, markdown, bodyStyle, editable, findOpen, Modifier.weight(1f).fillMaxWidth()) { focused = it }
        if (!model.immersive) {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            if (markdown && !reading && !findOpen && (tools || (focused && imeVisible))) MarkdownTools(model, controlsEnabled) { if (editable) linkToken = model.editToken() }
            if (!(markdown && !reading && focused && imeVisible && !findOpen)) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.document_count, source.codePointCount(0, source.length), source.codePointCount(0, source.length)), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(displayPath(checkNotNull(ui.workspace), entry.parent ?: Workspace.ROOT), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                if (markdown && !reading && !imeVisible) TextButton(onClick = { if (editable) tools = !tools }, enabled = controlsEnabled) { Text(stringResource(R.string.md_format)) }
            }
        }
    }
    }
    linkToken?.let { token -> MarkdownLinkDialog(model, token, { linkToken = null }, uriHandler::openUri) }
}
