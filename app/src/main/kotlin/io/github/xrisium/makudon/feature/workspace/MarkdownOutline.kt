package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.MarkdownDocument
import io.github.xrisium.makudon.domain.MdElement

internal class OutlineJump(val source: String, val offset: Int)

private fun MdElement.outlineTitle(): String = buildString {
    fun appendInline(node: MdElement) {
        when (node.kind) {
            "Text", "Code" -> append(node.text)
            "SoftLineBreak", "HardLineBreak" -> append(' ')
            else -> node.children.forEach(::appendInline)
        }
    }
    appendInline(this@outlineTitle)
}.trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownOutline(document: MarkdownDocument?, anchor: Int, onDismiss: () -> Unit, onJump: (OutlineJump) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val headings = remember(document) {
        document?.root?.children?.filter { it.kind == "Heading" }?.map { it to it.outlineTitle() }.orEmpty()
    }
    val current = headings.lastOrNull { (node, _) -> node.range.start <= anchor }?.first
    val list = rememberLazyListState()
    LaunchedEffect(headings, current) {
        val index = headings.indexOfFirst { it.first == current }
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    }
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.62f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("markdown-outline")) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.md_outline), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Tool(R.drawable.ic_x, stringResource(R.string.md_outline_close), onClick = onDismiss)
            }
            HorizontalDivider(color = colors.outlineVariant)
            if (document == null || headings.isEmpty()) {
                Text(
                    stringResource(if (document == null) R.string.md_outline_loading else R.string.md_outline_empty),
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    color = colors.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth().testTag("outline-headings"),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(headings, key = { it.first.range.start }) { (node, title) ->
                        val active = node == current
                        val currentSection = stringResource(R.string.md_outline_current)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(11.dp))
                                .then(if (active) Modifier.background(colors.secondaryContainer)
                                    .semantics { stateDescription = currentSection } else Modifier)
                                .clickable(role = Role.Button) { onJump(OutlineJump(document.source, node.range.start)) }
                                .padding(start = 12.dp + 10.dp * (node.level - 1), end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(title.ifEmpty { stringResource(R.string.md_outline_untitled) }, Modifier.weight(1f),
                                color = if (active) colors.onSecondaryContainer else colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.md_outline_level, node.level),
                                color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
