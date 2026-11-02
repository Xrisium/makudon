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

@Composable
internal fun EditorTextSizePicker(selected: EditorTextSize, enabled: Boolean, onSelect: (EditorTextSize) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val position by animateFloatAsState(selected.ordinal.toFloat(), tween(240, easing = FastOutSlowInEasing), label = "editor-text-size")
    val title = stringResource(R.string.editor_text_size)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
            // The canvas shares the exact row bounds: every final highlight is one third wide.
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(colors.surface, topLeft = Offset(size.width / 3 * position, 0f),
                    size = Size(size.width / 3, size.height), cornerRadius = CornerRadius(11.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                EditorTextSize.entries.forEach { option ->
                    val (short, full) = when (option) {
                        EditorTextSize.Small -> R.string.editor_size_small_short to R.string.editor_size_small
                        EditorTextSize.Medium -> R.string.editor_size_medium_short to R.string.editor_size_medium
                        EditorTextSize.Large -> R.string.editor_size_large_short to R.string.editor_size_large
                    }
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
