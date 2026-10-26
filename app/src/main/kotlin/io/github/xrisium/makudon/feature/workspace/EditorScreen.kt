package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.EditorStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(model: WorkspaceViewModel, onExport: () -> Unit, onRename: () -> Unit) {
    val ui = model.ui
    val field = model.textState ?: return
    val session = model.save ?: return
    val entry = ui.workspace?.entry(ui.activeId.orEmpty()) ?: return
    val status by session.status.collectAsState()
    val colors = MaterialTheme.colorScheme
    val editable = !ui.busy && !ui.transitioning && ui.page == Page.Editor
    var more by remember { mutableStateOf(false) }
    val source = field.text.toString()
    val input = remember(model) { InputTransformation {
        if (model.ui.busy || model.ui.transitioning || model.ui.page != Page.Editor) {
            revertAllChanges()
            return@InputTransformation
        }
        val raw = asCharSequence().toString()
        val normalized = TextRules.normalize(raw)
        try {
            TextRules.bytes(normalized)
            if (normalized != raw) for (index in raw.lastIndex downTo 0) {
                if (raw[index] == '\r') replace(index, index + 1, if (index < raw.lastIndex && raw[index + 1] == '\n') "" else "\n")
            }
        } catch (e: WorkspaceException) { revertAllChanges(); model.inputRejected(e.reason) }
    } }
    Column(Modifier.fillMaxSize()) {
        PageBar(entry.name, model::back, onTitleClick = onRename, titleEnabled = editable) {
            Tool(R.drawable.ic_undo_2, stringResource(R.string.undo), enabled = editable && model.canUndo, onClick = { model.undo() })
            Tool(R.drawable.ic_redo_2, stringResource(R.string.redo), enabled = editable && model.canRedo, onClick = { model.undo(true) })
            Box {
                Tool(R.drawable.ic_more_horizontal, stringResource(R.string.item_actions), onClick = { more = !more })
                DropdownMenu(more, { more = false }, shape = RoundedCornerShape(18.dp), containerColor = colors.surface) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_copy)) }, leadingIcon = { Glyph(R.drawable.ic_download) }, onClick = { more = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.previous_version)) }, leadingIcon = { Glyph(R.drawable.ic_clock_3) }, onClick = { more = false; model.previous() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (entry.kind == DocumentKind.Markdown) {
                val position by animateFloatAsState(if (ui.typeset) 1f else 0f, tween(160, easing = FastOutSlowInEasing), label = "editor-view-selection")
                Box(Modifier.background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(colors.surface, topLeft = Offset(size.width / 2 * position, 0f),
                            size = Size(size.width / 2, size.height), cornerRadius = CornerRadius(11.dp.toPx()))
                    }
                    Row {
                        listOf(false to R.string.source_view, true to R.string.typeset_view).forEach { (typeset, label) ->
                            TextButton(onClick = { model.typeset(typeset) }, enabled = editable, shape = RoundedCornerShape(11.dp)) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            } else Text(stringResource(R.string.plain_text), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(when (status.phase) {
                SavePhase.Saved -> R.string.save_saved; SavePhase.Waiting -> R.string.save_waiting; SavePhase.Saving -> R.string.save_saving; SavePhase.Failed -> R.string.save_failed
            }), Modifier.padding(start = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall, color = if (status.phase == SavePhase.Failed) colors.error else colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
        if (status.phase == SavePhase.Failed) {
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appeared = true }
            val opacity by animateFloatAsState(if (appeared) 1f else 0f, tween(180), label = "save-error")
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(16.dp).alpha(opacity)) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.save_failure_help), color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = model::retrySave, enabled = !ui.busy) { Text(stringResource(R.string.retry)) }; TextButton(onClick = onExport) { Text(stringResource(R.string.export_copy)) } }
                }
            }
        }
        val description = stringResource(R.string.edit_body)
        if (entry.kind != DocumentKind.Markdown || !ui.typeset) {
            BasicTextField(state = field, modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp).testTag("source-editor").semantics { contentDescription = description },
                readOnly = !editable, textStyle = EditorStyle.copy(color = colors.onBackground), cursorBrush = SolidColor(colors.primary), inputTransformation = input,
                decorator = TextFieldDecorator { inner -> Box { if (source.isEmpty()) Text(stringResource(R.string.editor_placeholder), style = EditorStyle, color = colors.onSurfaceVariant); inner() } },
            )
        } else {
            val blocks = model.blocks
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("typeset-editor"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                itemsIndexed(blocks, key = { index, _ -> "${entry.id}-${model.blockEpoch}-$index" }) { index, block ->
                    val label = stringResource(R.string.edit_block, index + 1)
                    if (!block.editable) {
                        Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).clickable { model.typeset(false) }.padding(12.dp)) {
                            Text(block.text, style = EditorStyle.copy(color = colors.onBackground))
                            Text(stringResource(R.string.unsupported_markdown), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    } else {
                        val style = when (block.heading) { 1 -> EditorStyle.copy(fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium); 2 -> EditorStyle.copy(fontSize = 21.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium); else -> EditorStyle }
                        val local = remember(entry.id, model.blockEpoch, index) { TextFieldState(block.text) }
                        DisposableEffect(local) {
                            model.registerParagraph(index, local)
                            onDispose { model.unregisterParagraph(index, local) }
                        }
                        LaunchedEffect(local) {
                            var applied = block.text
                            snapshotFlow { local.text.toString() }.collect { text ->
                                if (text != applied) {
                                    if (model.editBlock(index, applied, text)) applied = text
                                    else local.edit { replace(0, length, applied) }
                                }
                            }
                        }
                        // Each paragraph owns its IME buffer. Exact source ranges shift after edits; separators stay untouched.
                        BasicTextField(state = local, modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp).semantics { contentDescription = label },
                            readOnly = !editable, textStyle = style.copy(color = colors.onBackground), cursorBrush = SolidColor(colors.primary), inputTransformation = input,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.document_count, source.codePointCount(0, source.length)), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(ui.workspace.path(entry.parent ?: Workspace.ROOT), Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
