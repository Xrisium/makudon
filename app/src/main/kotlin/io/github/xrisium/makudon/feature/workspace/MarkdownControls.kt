package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.EditorMode

@Composable
internal fun MarkdownModePicker(mode: EditorMode, enabled: Boolean, onMode: (EditorMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val position by animateFloatAsState(mode.ordinal.toFloat(), tween(160), label = "editor-view-selection")
    // Padding belongs outside the shared indicator / selectable coordinate space.
    Box(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val segment = maxWidth / 3
            Canvas(Modifier.matchParentSize().testTag("editor-mode-indicator")) {
                drawRoundRect(colors.surface, topLeft = Offset(size.width / 3 * position, 0f), size = Size(size.width / 3, size.height), cornerRadius = CornerRadius(11.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                EditorMode.entries.forEach { item ->
                    val label = stringResource(when (item) { EditorMode.Source -> R.string.source_view; EditorMode.Typeset -> R.string.typeset_view; EditorMode.Reading -> R.string.reading_view })
                    val compact = stringResource(when (item) { EditorMode.Source -> R.string.source_view_compact; EditorMode.Typeset -> R.string.typeset_view_compact; EditorMode.Reading -> R.string.reading_view_compact })
                    val icon = when (item) { EditorMode.Source -> R.drawable.ic_code_xml; EditorMode.Typeset -> R.drawable.ic_text_cursor_input; EditorMode.Reading -> R.drawable.ic_book_open }
                    val width = with(LocalDensity.current) { (segment - 12.dp).toPx() }
                    val display = if (measurer.measure(label, style, softWrap = false).size.width > width) compact else label
                    val stacked = measurer.measure(display, style, softWrap = false).size.width > with(LocalDensity.current) { (segment - 38.dp).toPx() }
                    val selected = item == mode
                    Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = if (stacked) 72.dp else 48.dp).clip(RoundedCornerShape(11.dp))
                        .selectable(selected, enabled, role = Role.Tab, onClick = { onMode(item) })
                        .semantics { contentDescription = label }.testTag("editor-mode-${item.name}"), contentAlignment = Alignment.Center) {
                        if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Glyph(icon)
                            Text(display, style = style, maxLines = 1, softWrap = false)
                        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Glyph(icon)
                            Text(display, style = style, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownGroupButtons(selected: MarkdownToolGroup?, enabled: Boolean, onGroup: (MarkdownToolGroup) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    Row {
        MarkdownToolGroup.entries.forEach { group ->
            val name = stringResource(group.label)
            val icon = when (group) { MarkdownToolGroup.Text -> R.drawable.ic_type; MarkdownToolGroup.Paragraph -> R.drawable.ic_pilcrow; MarkdownToolGroup.Insert -> R.drawable.ic_plus }
            val fits = measurer.measure(name, style, softWrap = false).size.width <= with(LocalDensity.current) { 42.dp.toPx() }
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(), tooltip = { PlainTooltip { Text(name) } }) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected == group) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected == group, enabled, role = Role.Button, onClick = { onGroup(group) })
                    .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                    if (fits) Text(name, style = style, maxLines = 1, softWrap = false) else Glyph(icon)
                }
            }
        }
    }
}
