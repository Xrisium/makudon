package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.*

@Composable
internal fun MarkdownReader(model: WorkspaceViewModel, document: MarkdownDocument, style: TextStyle, modifier: Modifier, query: String, caseSensitive: Boolean, target: Int, outlineJump: OutlineJump?, onOutlineJumped: () -> Unit) {
    val blocks = document.root.children
    val found = remember(document, query, caseSensitive) {
        blocks.flatMapIndexed { index, node -> LiteralSearch.matches(mdText(node, document, androidx.compose.ui.graphics.Color.Unspecified).text, query, caseSensitive).map { index } }
    }
    LaunchedEffect(target, query, caseSensitive) {
        val index = found.getOrNull(if (found.isEmpty()) 0 else Math.floorMod(target, found.size))
        if (index != null) model.readingList.scrollToItem(index)
    }
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toPx() }
    val restoreAnchor = remember(document, style) { model.readingAnchor to (model.readingAnchorOffset * lineHeight / model.viewportLineHeight).roundToInt() }
    var restoring by remember(document, style) { mutableStateOf(true) }
    LaunchedEffect(document, style) {
        val index = blocks.indexOfLast { it.range.start <= restoreAnchor.first }.coerceAtLeast(0)
        if (blocks.isNotEmpty()) model.readingList.scrollToItem(index, restoreAnchor.second)
        restoring = false
    }
    LaunchedEffect(outlineJump, document) {
        val jump = outlineJump ?: return@LaunchedEffect
        if (jump.source == document.source) {
            val index = blocks.indexOfFirst { it.range.start == jump.offset && it.kind == "Heading" }
            if (index >= 0) model.readingList.scrollToItem(index)
        }
        onOutlineJumped()
    }
    LaunchedEffect(model.readingList, document, restoring) {
        if (restoring) return@LaunchedEffect
        snapshotFlow { model.readingList.firstVisibleItemIndex to model.readingList.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            blocks.getOrNull(index)?.let { model.recordViewport(it.range.start, offset, lineHeight, reading = true) }
        }
    }
    Box(modifier) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(state = model.readingList, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                items(blocks, key = { it.range.start }) { node ->
                    Column {
                        ReadingNode(model, node, document, style, query, caseSensitive)
                        Box(Modifier.fillMaxWidth().height(18.dp).pointerInput(model) { detectTapGestures { model.immersive = !model.immersive } })
                    }
                }
                item {
                    // A deliberate blank target avoids stealing taps from links, selection and copying.
                    Box(Modifier.fillMaxWidth().height(96.dp).clickable(onClickLabel = stringResource(R.string.md_immersive)) { model.immersive = !model.immersive })
                }
            }
        }
        MarkdownNavigation(model, document, style)
    }
}

private data class ReaderNavigationMetrics(
    val range: DocumentScrollRange = DocumentScrollRange(0f, 0f, 0f),
    val starts: List<Float> = emptyList(),
)

@Composable
private fun BoxScope.MarkdownNavigation(model: WorkspaceViewModel, document: MarkdownDocument, style: TextStyle) {
    val blocks = document.root.children
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toPx() }
    val characterWidth = with(LocalDensity.current) { style.fontSize.toPx() }
    val gap = with(LocalDensity.current) { 18.dp.toPx() }
    val footer = with(LocalDensity.current) { 96.dp.toPx() }
    val horizontalPadding = with(LocalDensity.current) { 48.dp.toPx() }
    val metrics by produceState(ReaderNavigationMetrics(), model.readingList, document, style, lineHeight) {
        var measuredWidth = -1
        var heights = FloatArray(0)
        snapshotFlow {
            Triple(model.readingList.layoutInfo, model.readingList.firstVisibleItemIndex, model.readingList.firstVisibleItemScrollOffset)
        }.collect { (info, index, offset) ->
            if (info.viewportSize.width != measuredWidth) {
                measuredWidth = info.viewportSize.width
                val width = (measuredWidth - horizontalPadding).coerceAtLeast(characterWidth)
                heights = FloatArray(blocks.size + 1) { item ->
                    if (item == blocks.size) footer else {
                        // An initial wrapping estimate, replaced by actual measured heights as blocks appear.
                        val lines = document.raw(blocks[item]).lineSequence().sumOf { line ->
                            kotlin.math.ceil(line.length * characterWidth / width).toInt().coerceAtLeast(1)
                        }
                        lines * lineHeight + gap
                    }
                }
            }
            info.visibleItemsInfo.forEach { item -> if (item.index in heights.indices) heights[item.index] = item.size.toFloat() }
            val starts = ArrayList<Float>(heights.size)
            var total = 0f
            heights.forEach { height -> starts += total; total += height }
            total += info.beforeContentPadding + info.afterContentPadding
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val position = if (!model.readingList.canScrollForward) (total - viewport).coerceAtLeast(0f)
                else if (!model.readingList.canScrollBackward) 0f else starts.getOrElse(index) { 0f } + offset
            value = ReaderNavigationMetrics(DocumentScrollRange(viewport, total, position), starts)
        }
    }
    DocumentNavigation(model, metrics.range, model.readingList.isScrollInProgress) { progress ->
        if (progress <= 0) model.readingList.scrollToItem(0)
        else if (progress >= 1) model.readingList.scrollToItem(blocks.size)
        else {
            val distance = metrics.range.maximum * progress
            val index = metrics.starts.indexOfLast { it <= distance }.coerceIn(0, blocks.size)
            val offset = (distance - metrics.starts.getOrElse(index) { 0f }).coerceAtLeast(0f).roundToInt()
            model.readingList.scrollToItem(index, offset)
        }
    }
}

@Composable
private fun ReadingNode(model: WorkspaceViewModel, node: MdElement, document: MarkdownDocument, style: TextStyle, query: String, caseSensitive: Boolean) {
    val colors = MaterialTheme.colorScheme
    when (node.kind) {
        "BulletList", "OrderedList" -> Column {
            node.children.forEachIndexed { index, child ->
                val task = child.children.firstOrNull { it.kind == "TaskListItemMarker" }
                Row(Modifier.fillMaxWidth().then(if (task == null) Modifier else Modifier.semantics(mergeDescendants = true) {
                    role = Role.Checkbox; toggleableState = if (task.text.startsWith("☑")) ToggleableState.On else ToggleableState.Off
                })) {
                    Text(task?.text?.trim() ?: if (node.kind == "OrderedList") "${node.level + index}." else "•", Modifier.widthIn(min = if (task == null) 24.dp else 32.dp).clearAndSetSemantics { }, style = style)
                    Column(Modifier.weight(1f)) { child.children.filter { it.kind != "TaskListItemMarker" }.forEach { ReadingNode(model, it, document, style, query, caseSensitive) } }
                }
            }
        }
        "BlockQuote" -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(colors.outlineVariant))
            Column(Modifier.padding(start = 12.dp).weight(1f)) { node.children.forEach { ReadingNode(model, it, document, style, query, caseSensitive) } }
        }
        "TableBlock" -> MarkdownTable(node, document, style, query = query, caseSensitive = caseSensitive)
        "ThematicBreak" -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
        "FencedCodeBlock", "IndentedCodeBlock" -> Text(mdText(node, document, colors.primary, query, caseSensitive, colors.tertiaryContainer),
            Modifier.fillMaxWidth().background(colors.surfaceVariant).horizontalScroll(rememberScrollState()).padding(12.dp), style = style.copy(fontFamily = FontFamily.Monospace))
        else -> {
            if (node.kind == "Paragraph" && node.children.size == 1 && node.children.single().kind == "Image" && ImageReferences.key(node.children.single().destination) != null) {
                MarkdownImage(model, node.children.single())
                return
            }
            val text = if (node.kind in setOf("HtmlBlock", "HtmlInline") || (node.children.isEmpty() && node.text.isEmpty())) androidx.compose.ui.text.AnnotatedString(document.raw(node))
                else mdText(node, document, colors.primary, query, caseSensitive, colors.tertiaryContainer)
            val headingStyle = if (node.kind == "Heading") style.copy(fontSize = (style.fontSize.value * when (node.level) { 1 -> 1.45f; 2 -> 1.25f; 3 -> 1.15f; else -> 1.05f }).sp, fontWeight = FontWeight.Bold) else style
            Text(text, Modifier.fillMaxWidth().semantics { if (node.kind == "Heading") heading() }, style = headingStyle, color = colors.onBackground)
        }
    }
}

@Composable
internal fun MarkdownTable(node: MdElement, document: MarkdownDocument, style: TextStyle, modifier: Modifier = Modifier, query: String = "", caseSensitive: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val rows = remember(node) { node.descendants().filter { it.kind == "TableRow" }.toList() }
    Column(modifier.horizontalScroll(rememberScrollState()).background(colors.background)) {
        rows.forEachIndexed { row, item ->
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                item.children.forEach { cell ->
                    Box(Modifier.width(160.dp).fillMaxHeight().border(1.dp, colors.outlineVariant).padding(10.dp)) {
                        Text(mdText(cell, document, colors.primary, query, caseSensitive, colors.tertiaryContainer), style = if (row == 0) style.copy(fontWeight = FontWeight.Bold) else style, color = colors.onBackground)
                    }
                }
            }
        }
    }
}
