package io.github.xrisium.makudon.feature.workspace

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.EditorTextSize
import io.github.xrisium.makudon.domain.EditorLineSpacing
import io.github.xrisium.makudon.domain.Workspace
import io.github.xrisium.makudon.ui.theme.EditorStyle
import androidx.compose.ui.unit.sp

@Composable
internal fun editorBodyStyle(workspace: Workspace?): androidx.compose.ui.text.TextStyle {
    val size = workspace?.editorTextSize ?: EditorTextSize.Medium
    val spacing = workspace?.editorLineSpacing ?: EditorLineSpacing.Medium
    return EditorStyle.copy(fontSize = size.sp.sp, lineHeight = (spacing.height * size.sp / 18f).sp)
}

@Composable
internal fun EditorTextSizePicker(selected: EditorTextSize, enabled: Boolean, onSelect: (EditorTextSize) -> Unit) {
    EditorOptionPicker(stringResource(R.string.editor_text_size), selected.ordinal, enabled,
        listOf(R.string.editor_size_small_short, R.string.editor_size_medium_short, R.string.editor_size_large_short),
        listOf(R.string.editor_size_small, R.string.editor_size_medium, R.string.editor_size_large)) { onSelect(EditorTextSize.entries[it]) }
}

@Composable
internal fun EditorLineSpacingPicker(selected: EditorLineSpacing, enabled: Boolean, onSelect: (EditorLineSpacing) -> Unit) {
    EditorOptionPicker(stringResource(R.string.editor_line_spacing), selected.ordinal, enabled,
        listOf(R.string.spacing_wide_short, R.string.spacing_medium_short, R.string.spacing_narrow_short),
        listOf(R.string.spacing_wide, R.string.spacing_medium, R.string.spacing_narrow)) { onSelect(EditorLineSpacing.entries[it]) }
}

@Composable
private fun EditorOptionPicker(title: String, selected: Int, enabled: Boolean, labels: List<Int>, descriptions: List<Int>, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val position by animateFloatAsState(selected.toFloat(), tween(240, easing = FastOutSlowInEasing), label = "editor-text-size")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
            // The canvas shares the exact row bounds: every final highlight is one third wide.
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(colors.surface, topLeft = Offset(size.width / 3 * position, 0f),
                    size = Size(size.width / 3, size.height), cornerRadius = CornerRadius(11.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { option, short ->
                    val full = descriptions[option]
                    val description = "$title: ${stringResource(full)}"
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(11.dp))
                        .selectable(selected = selected == option, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                        .semantics { contentDescription = description }
                        .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(short), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected == option) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
