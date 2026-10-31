package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.ui.theme.fontFamily
import io.github.xrisium.makudon.ui.theme.fontWeight
import java.util.Calendar
import java.util.Locale

internal fun fontLabel(font: FontChoice) = when (font) {
    FontChoice.Sans -> R.string.font_sans; FontChoice.Serif -> R.string.font_serif
    FontChoice.WenKai -> R.string.font_wenkai; FontChoice.System -> R.string.font_system
}
internal fun thicknessLabel(value: FontThickness) = when (value) {
    FontThickness.Light -> R.string.font_light; FontThickness.Normal -> R.string.font_normal; FontThickness.Bold -> R.string.font_bold
}

@Composable
internal fun FontSettingsPage(model: WorkspaceViewModel, isUi: Boolean) {
    val workspace = model.ui.workspace ?: return
    val selected = if (isUi) workspace.uiFont else workspace.editorFont
    val date = remember { Calendar.getInstance().let { String.format(Locale.ROOT, "%04d.%02d.%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) } }
    val enabled = !model.ui.busy && !model.ui.transitioning
    fun change(next: FontPreference) { if (isUi) model.preferences(uiFont = next) else model.preferences(editorFont = next) }
    val sampleStyle = TextStyle(fontFamily = remember(selected, isUi) { fontFamily(selected, isUi) }, fontWeight = fontWeight(selected, isUi), fontSize = 16.sp, lineHeight = 27.sp)
    Column(Modifier.fillMaxSize()) {
        PageBar(stringResource(if (isUi) R.string.ui_font else R.string.editor_font), model::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.font), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoice.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { choice ->
                            val active = selected.choice == choice
                            val optionPreference = selected.copy(choice = choice)
                            val optionFamily = remember(optionPreference, isUi) { fontFamily(optionPreference, isUi) }
                            Surface(Modifier.weight(1f).selectable(selected = active, enabled = enabled, role = Role.RadioButton) { change(selected.copy(choice = choice)) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(fontLabel(choice)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = optionFamily, fontWeight = fontWeight(optionPreference, isUi)))
                                    if (active) Glyph(R.drawable.ic_check)
                                }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.font_thickness), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FontThickness.entries.forEach { thickness ->
                    val active = selected.thickness == thickness
                    Surface(Modifier.weight(1f).selectable(active, enabled = enabled, role = Role.RadioButton) { change(selected.copy(thickness = thickness)) }, shape = RoundedCornerShape(12.dp), color = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                        Box(Modifier.heightIn(min = 48.dp).padding(8.dp), contentAlignment = Alignment.Center) { Text(stringResource(thicknessLabel(thickness)), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            Text(stringResource(R.string.font_preview), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.font_preview_title), style = sampleStyle.copy(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold))
                    Text(stringResource(R.string.font_preview_chinese), style = sampleStyle)
                    Text(stringResource(R.string.font_preview_chinese), style = sampleStyle.copy(fontWeight = FontWeight.Bold))
                    Text(stringResource(R.string.font_preview_english), style = sampleStyle)
                    Text(stringResource(R.string.font_preview_date, date), style = sampleStyle)
                }
            }
        }
    }
}
