package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R

@Composable
internal fun LanguageSetting(model: WorkspaceViewModel, enabled: Boolean) {
    val languages = model.languages
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().testTag("language-setting").clickable(enabled = enabled) { open = true }.padding(18.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.language_title))
                    Text(if (languages.selected.isEmpty()) stringResource(R.string.language_system) else languages.nativeName(languages.selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph(R.drawable.ic_chevron_right)
            }
            languages.error?.let { Text(stringResource(it), Modifier.padding(18.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.language_title)) }, text = {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            (listOf("") + languages.supported).forEach { tag ->
                Row(Modifier.fillMaxWidth().selectable(languages.selected == tag, enabled = enabled, role = Role.RadioButton) {
                    open = false
                    model.changeLanguage(tag)
                }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = languages.selected == tag, onClick = null)
                    Text(if (tag.isEmpty()) stringResource(R.string.language_system) else languages.nativeName(tag), Modifier.weight(1f).padding(8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } })
    if (languages.migration != null) AlertDialog(onDismissRequest = { model.changeLanguage("") }, title = { Text(stringResource(R.string.language_title)) }, text = {
        Text(stringResource(R.string.language_migration))
    }, confirmButton = { TextButton(onClick = { model.changeLanguage(checkNotNull(languages.migration)) }, enabled = enabled) { Text(stringResource(R.string.language_keep_previous)) } }, dismissButton = {
        TextButton(onClick = { model.changeLanguage("") }, enabled = enabled) { Text(stringResource(R.string.language_system)) }
    })
}
