package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.domain.Entry

@Composable
internal fun DirectoryFilename(entry: Entry) {
    val style = MaterialTheme.typography.bodyMedium
    if (entry.folder) {
        Text(entry.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var truncated by remember(entry.name) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = entry.filename }, verticalAlignment = Alignment.CenterVertically) {
        Text(entry.name, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.hasVisualOverflow })
        // A visual gap keeps the single-character ellipsis distinct from the extension's dot.
        if (truncated) Spacer(Modifier.width(2.dp))
        Text(".${entry.extension}", style = style, maxLines = 1, softWrap = false)
    }
}
