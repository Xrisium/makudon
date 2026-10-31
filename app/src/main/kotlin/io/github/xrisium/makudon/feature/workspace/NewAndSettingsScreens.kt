package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.themePalette

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
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorTheme.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { theme ->
                            ThemeOption(theme, workspace.theme == theme, enabled, Modifier.weight(1f)) {
                                model.preferences(theme = theme)
                            }
                        }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.font), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    listOf(true, false).forEach { uiFont ->
                        val preference = if (uiFont) workspace.uiFont else workspace.editorFont
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { model.fontSettings(uiFont) }.padding(vertical = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(stringResource(if (uiFont) R.string.ui_font else R.string.editor_font)); Text(stringResource(fontLabel(preference.choice)) + " · " + stringResource(thicknessLabel(preference.thickness)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Glyph(R.drawable.ic_chevron_right)
                        }
                        if (uiFont) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item {
            Surface(onClick = model::backups, enabled = enabled, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.backup_title)); Text(stringResource(R.string.backup_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Glyph(R.drawable.ic_chevron_right)
                }
            }
        }
        item { TextButton(onClick = model::licenses, enabled = enabled) { Text(stringResource(R.string.licenses)); Spacer(Modifier.width(6.dp)); Glyph(R.drawable.ic_chevron_right) } }
        item { Text(stringResource(R.string.local_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.makudon_lockup), stringResource(R.string.brand_name), Modifier.width(126.dp).height(28.dp)) } }
    }
}

@Composable
private fun ThemeOption(theme: ColorTheme, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = themePalette(theme)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.border(if (selected) 2.dp else 1.dp, if (selected) colors.onSurface else colors.outlineVariant, shape)
            .background(colors.surface, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(9.dp)).background(palette[0])) {
            if (theme == ColorTheme.System) {
                SystemHalf(themePalette(ColorTheme.Paper), Modifier.weight(1f))
                SystemHalf(themePalette(ColorTheme.Ink), Modifier.weight(1f))
            } else {
                Box(Modifier.fillMaxSize().padding(8.dp).background(palette[1], RoundedCornerShape(6.dp))) {
                    Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(46.dp).height(4.dp).background(palette[2], RoundedCornerShape(2.dp)))
                        Box(Modifier.fillMaxWidth(0.8f).height(3.dp).background(palette[5], RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(themeLabel(theme)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (selected) Glyph(R.drawable.ic_check, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SystemHalf(palette: List<Color>, modifier: Modifier) {
    Box(modifier.fillMaxHeight().background(palette[0]).padding(8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(palette[1], RoundedCornerShape(6.dp)).padding(6.dp)) {
            Box(Modifier.fillMaxWidth(0.65f).height(4.dp).background(palette[2], RoundedCornerShape(2.dp)))
        }
    }
}
