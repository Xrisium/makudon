package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.EditorStyle

@Composable
fun NewPage(model: WorkspaceViewModel, import: () -> Unit) {
    val ui = model.ui
    val workspace = ui.workspace ?: return
    var locations by remember { mutableStateOf(false) }
    Column {
        PageBar(stringResource(R.string.new_document), model::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedCard(onClick = { locations = true }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.background), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.ic_folder); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.new_location), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(workspace.path(ui.newParent)) }
                    Glyph(R.drawable.ic_chevron_right)
                }
            }
            listOf(Triple(R.string.plain_text, R.drawable.ic_file_text, DocumentKind.Plain), Triple(R.string.markdown, R.drawable.ic_markdown, DocumentKind.Markdown)).forEach { (title, icon, kind) ->
                Surface(Modifier.fillMaxWidth().clickable(enabled = !(ui.busy || ui.transitioning)) { model.create(kind) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(24.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Glyph(icon, Modifier.size(26.dp)); Spacer(Modifier.width(18.dp)); Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Glyph(R.drawable.ic_chevron_right) }
                }
            }
            Surface(Modifier.fillMaxWidth().clickable(enabled = !(ui.busy || ui.transitioning), onClick = import), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(24.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Glyph(R.drawable.ic_import, Modifier.size(26.dp)); Spacer(Modifier.width(18.dp)); Text(stringResource(R.string.import_document), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Glyph(R.drawable.ic_chevron_right) }
            }
            Text(stringResource(R.string.import_hint), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (locations) AlertDialog(onDismissRequest = { locations = false }, title = { Text(stringResource(R.string.new_location)) }, text = {
        LazyColumn { items(workspace.entries.filter { it.folder }, key = { it.id }) { item ->
            TextButton(onClick = { model.chooseNewParent(item.id); locations = false }, modifier = Modifier.fillMaxWidth()) { Text(workspace.path(item.id), Modifier.weight(1f)); if (item.id == ui.newParent) Glyph(R.drawable.ic_check) }
        } }
    }, confirmButton = { TextButton(onClick = { locations = false }) { Text(stringResource(R.string.cancel)) } })
}

fun themeLabel(theme: ColorTheme) = when (theme) {
    ColorTheme.System -> R.string.theme_system; ColorTheme.Paper -> R.string.theme_paper; ColorTheme.Cream -> R.string.theme_cream
    ColorTheme.Mist -> R.string.theme_mist; ColorTheme.Sage -> R.string.theme_sage; ColorTheme.Ink -> R.string.theme_ink
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPage(model: WorkspaceViewModel) {
    val ui = model.ui
    val workspace = ui.workspace ?: return
    // The model still gates actions while persisting preferences; keep text colors stable.
    val enabled = !ui.busy || ui.savingPreferences
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(R.string.settings), Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.headlineMedium) }
        item { Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorTheme.entries.forEach { theme -> FilterChip(selected = workspace.theme == theme, enabled = enabled, onClick = { model.preferences(theme = theme) }, label = { Text(stringResource(themeLabel(theme))) }, shape = RoundedCornerShape(14.dp)) }
            }
        }
        item { Text(stringResource(R.string.font), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    listOf(R.string.ui_font to R.string.ui_font_detail, R.string.editor_font to R.string.editor_font_detail).forEach { (title, detail) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(stringResource(title)); Text(stringResource(detail), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(stringResource(R.string.font_reserved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Text(stringResource(R.string.font_sample), Modifier.padding(vertical = 20.dp), style = EditorStyle)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.auto_save)); Text(stringResource(R.string.auto_save_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Glyph(R.drawable.ic_check)
                }
            }
        }
        item { TextButton(onClick = model::licenses, enabled = enabled) { Text(stringResource(R.string.licenses)); Spacer(Modifier.width(6.dp)); Glyph(R.drawable.ic_chevron_right) } }
        item { Text(stringResource(R.string.local_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.makudon_lockup), stringResource(R.string.brand_name), Modifier.width(126.dp).height(28.dp)) } }
    }
}
