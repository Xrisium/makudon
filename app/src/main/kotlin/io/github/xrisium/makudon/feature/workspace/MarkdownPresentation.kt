package io.github.xrisium.makudon.feature.workspace

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.domain.*

internal data class DisplayChange(val range: SourceRange, val text: String)
internal data class MarkdownProjection(val changes: List<DisplayChange>) {
    private val deltas = IntArray(changes.size + 1).also { values ->
        changes.forEachIndexed { i, change -> values[i + 1] = values[i] + change.text.length - (change.range.end - change.range.start) }
    }
    private val sourceEnds = IntArray(changes.size) { changes[it].range.end }
    private val displayEnds = IntArray(changes.size) { changes[it].range.start + deltas[it] + changes[it].text.length }
    private fun firstEnd(ends: IntArray, offset: Int): Int {
        var low = 0; var high = ends.size
        while (low < high) { val middle = (low + high) ushr 1; if (ends[middle] < offset) low = middle + 1 else high = middle }
        return low
    }
    fun sourceOffset(displayOffset: Int): Int {
        val index = firstEnd(displayEnds, displayOffset)
        val change = changes.getOrNull(index)
        return if (change != null && displayOffset >= change.range.start + deltas[index]) change.range.start else displayOffset - deltas[index]
    }
    fun offset(sourceOffset: Int): Int {
        val index = firstEnd(sourceEnds, sourceOffset)
        val change = changes.getOrNull(index)
        return if (change != null && sourceOffset >= change.range.start) change.range.start + deltas[index] + minOf(sourceOffset - change.range.start, change.text.length) else sourceOffset + deltas[index]
    }
    /** Off-screen runs share one display-only replacement; visible/caret windows keep exact wedges. */
    fun outputChanges(source: String, windows: List<SourceRange>): List<DisplayChange> {
        if (source.length <= 8192 || changes.size < 128) return changes
        val ordered = windows.sortedBy { it.start }
        val out = mutableListOf<DisplayChange>()
        val pending = mutableListOf<DisplayChange>()
        var region = -1
        fun flush() {
            if (pending.isEmpty()) return
            if (pending.size == 1) out += pending.first()
            else {
                val range = SourceRange(pending.first().range.start, pending.last().range.end)
                val text = StringBuilder(); var position = range.start
                for (change in pending) { text.append(source, position, change.range.start); text.append(change.text); position = change.range.end }
                out += DisplayChange(range, text.toString())
            }
            pending.clear()
        }
        for (change in changes) {
            if (ordered.any { it.intersects(change.range.start, change.range.end) }) { flush(); out += change; region = -1 }
            else {
                val nextRegion = ordered.count { it.end < change.range.start }
                if (nextRegion != region) flush()
                region = nextRegion; pending += change
            }
        }
        flush()
        return out
    }
}

/** Until the background parse arrives, only changed top-level containers become literal. */
internal fun provisionalDocument(previous: MarkdownDocument?, source: String): MarkdownDocument {
    if (previous == null) return MarkdownDocument(source, MdElement("Document", SourceRange(0, source.length), children = listOf(MdElement("Raw", SourceRange(0, source.length), source))))
    if (previous.source == source) return previous
    val old = previous.source
    var prefix = 0
    while (prefix < minOf(old.length, source.length) && old[prefix] == source[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(old.length, source.length) - prefix && old[old.lastIndex - suffix] == source[source.lastIndex - suffix]) suffix++
    val oldEnd = old.length - suffix; val delta = source.length - old.length
    fun shifted(n: MdElement): MdElement = n.copy(range = if (n.range == SourceRange(0, 0)) n.range else SourceRange(n.range.start + delta, n.range.end + delta), children = n.children.map(::shifted))
    val before = previous.root.children.takeWhile { it.range.end < prefix || (it.kind == "TableBlock" && it.range.end == prefix) }
    val after = previous.root.children.takeLastWhile { it.range.start > oldEnd }
    val affected = previous.root.children.subList(before.size, previous.root.children.size - after.size)
    val start = minOf(prefix, affected.firstOrNull()?.range?.start ?: prefix).coerceAtMost(source.length)
    val end = ((maxOf(oldEnd, affected.lastOrNull()?.range?.end ?: oldEnd)) + delta).coerceIn(start, source.length)
    // Cell input must keep its native field mounted while the background AST catches up.
    val table = affected.singleOrNull()?.takeIf { container ->
        container.descendants().any { it.kind == "TableBlock" && prefix >= it.range.start && oldEnd <= it.range.end } &&
        old.substring(prefix, oldEnd).none { c -> c == '\n' } && source.substring(prefix, source.length - suffix).none { c -> c == '\n' } }
    fun rebased(n: MdElement): MdElement {
        fun offset(at: Int) = when { at <= prefix -> at; at >= oldEnd -> at + delta; else -> prefix }
        return n.copy(range = SourceRange(offset(n.range.start), offset(n.range.end)), children = n.children.map(::rebased))
    }
    // One replacement may cover thousands of old blocks. Copy its source only once.
    val children = before + (table?.let(::rebased) ?: MdElement("Raw", SourceRange(start, end), source.substring(start, end))) + after.map(::shifted)
    return MarkdownDocument(source, MdElement("Document", SourceRange(0, source.length), children = children))
}

internal fun projection(document: MarkdownDocument, selection: TextSelection?, composition: SourceRange?, tables: List<MdElement> = emptyList()): MarkdownProjection {
    val source = document.source
    val changes = mutableListOf<DisplayChange>()
    fun active(range: SourceRange) = selection?.let { range.intersects(it.min, it.max) } == true || composition?.let { range.intersects(it.start, it.end) } == true
    fun change(start: Int, end: Int, replacement: String = "") {
        if (start < end && start >= 0 && end <= source.length && tables.none { start >= it.range.start && end <= it.range.end }) changes += DisplayChange(SourceRange(start, end), replacement)
    }
    for (node in document.elements) {
        val range = node.range; val raw = document.raw(node)
        when (node.kind) {
            "StrongEmphasis", "Emphasis", "Strikethrough" -> if (!active(range)) {
                val width = if (node.kind == "Emphasis") 1 else 2
                change(range.start, range.start + width); change(range.end - width, range.end)
            }
            "Code" -> if (!active(range)) {
                val width = raw.takeWhile { it == '`' }.length
                change(range.start, range.start + width); change(range.end - width, range.end)
            }
            "Link" -> if (!active(range) && raw.startsWith('[')) {
                val labelEnd = raw.lastIndexOf("](")
                if (labelEnd > 0) { change(range.start, range.start + 1); change(range.start + labelEnd, range.end) }
            }
            "Heading" -> {
                val prefix = Regex("^#{1,6} +").find(raw)
                if (prefix != null && !active(SourceRange(range.start, range.start + raw.substringBefore('\n').length))) change(range.start, range.start + prefix.value.length)
            }
            "FencedCodeBlock" -> if (!active(range)) {
                val first = raw.indexOf('\n')
                val last = raw.lastIndexOf('\n')
                if (first >= 0) change(range.start, range.start + first + 1)
                if (last > first && raw.substring(last + 1).trim().matches(Regex("(?:`{3,}|~{3,})"))) change(range.start + last, range.end)
            }
            "ThematicBreak" -> if (!active(range)) change(range.start, range.end, "────────")
        }
    }
    val literalBlocks = document.elements.filter { it.kind in setOf("FencedCodeBlock", "IndentedCodeBlock", "HtmlBlock", "Raw") }.sortedBy { it.range.start }
    var literalIndex = 0
    var start = 0
    for (line in source.split('\n')) {
        val range = SourceRange(start, start + line.length)
        while (literalIndex < literalBlocks.size && literalBlocks[literalIndex].range.end < range.start) literalIndex++
        val literal = literalBlocks.getOrNull(literalIndex)?.range?.intersects(range.start, range.end) == true
        if (!active(range) && !literal) {
            val quote = Regex("^( *> ?)+").find(line)
            if (quote != null) change(start, start + quote.value.length, "│ ".repeat(quote.value.count { it == '>' }))
            val marker = Regex("^( *)([-+*]) (\\[[ xX]] )?").find(line)
            if (marker != null) {
                val task = marker.groupValues[3]
                val replacement = marker.groupValues[1] + if (task.isNotEmpty()) { if (task.contains('x', true)) "☑ " else "☐ " } else "• "
                change(start, start + marker.value.length, replacement)
            }
        }
        start += line.length + 1
    }
    tables.forEach {
        changes += DisplayChange(it.range, "\uFFFC")
        if (source.getOrNull(it.range.end)?.let { char -> char != '\n' } == true)
            changes += DisplayChange(SourceRange(it.range.end, it.range.end), "\n")
    }
    // Outer replacements (for example link destinations or table slots) own nested tokens.
    val sorted = changes.distinct().sortedWith(compareBy<DisplayChange> { it.range.start }.thenByDescending { it.range.end })
    val nonOverlapping = mutableListOf<DisplayChange>()
    for (item in sorted) if (nonOverlapping.lastOrNull()?.range?.end?.let { it > item.range.start } != true) nonOverlapping += item
    return MarkdownProjection(nonOverlapping)
}

internal fun liveTransformation(document: MarkdownDocument, projection: MarkdownProjection, body: TextStyle, link: Color, surface: Color, tableHeightSp: Float, windows: List<SourceRange>): OutputTransformation = OutputTransformation {
    if (asCharSequence().toString() != document.source) return@OutputTransformation
    projection.outputChanges(document.source, windows).asReversed().forEach { replace(it.range.start, it.range.end, it.text) }
    for (node in document.elements) {
        if (document.source.length > 8192 && windows.none { it.intersects(node.range.start, node.range.end) }) continue
        val style = when (node.kind) {
            "StrongEmphasis" -> SpanStyle(fontWeight = FontWeight.Bold)
            "Emphasis" -> SpanStyle(fontStyle = FontStyle.Italic)
            "Strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "Link" -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
            "Heading" -> SpanStyle(fontSize = (body.fontSize.value * when (node.level) { 1 -> 1.45f; 2 -> 1.25f; 3 -> 1.15f; else -> 1.05f }).sp, fontWeight = FontWeight.Bold)
            "Code", "FencedCodeBlock", "IndentedCodeBlock" -> SpanStyle(fontFamily = FontFamily.Monospace, background = surface)
            else -> null
        }
        if (style != null) {
            val start = projection.offset(node.range.start).coerceIn(0, length)
            val end = projection.offset(node.range.end).coerceIn(start, length)
            if (start < end) addStyle(style, start, end)
        }
    }
    projection.changes.filter { it.text == "\uFFFC" && (document.source.length <= 8192 || windows.any { window -> window.intersects(it.range.start, it.range.end) }) }.forEach {
        val at = projection.offset(it.range.start)
        addStyle(SpanStyle(color = Color.Transparent), at, at + 1)
        addStyle(ParagraphStyle(lineHeight = tableHeightSp.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)), at, at + 1)
    }
}

internal fun mdText(node: MdElement, document: MarkdownDocument, linkColor: Color, query: String = "", caseSensitive: Boolean = true, highlight: Color = Color.Yellow): AnnotatedString {
    val out = AnnotatedString.Builder()
    fun append(n: MdElement) {
        val start = out.length
        when (n.kind) {
            "Text", "Code", "FencedCodeBlock", "IndentedCodeBlock", "HtmlBlock", "HtmlInline", "SoftLineBreak", "HardLineBreak" -> out.append(n.text)
            "Image" -> out.append(document.raw(n))
            "ThematicBreak" -> out.append("────────")
            "TaskListItemMarker" -> out.append(n.text)
            else -> n.children.forEachIndexed { index, child ->
                if (index > 0 && n.children[index - 1].kind != "TaskListItemMarker" && n.kind in setOf("BulletList", "OrderedList", "BlockQuote", "ListItem", "TableHead", "TableBody", "TableBlock")) out.append("\n")
                if (index > 0 && n.kind == "TableRow") out.append("\t")
                append(child)
            }
        }
        val end = out.length
        if (start == end) return
        val style = when (n.kind) {
            "StrongEmphasis" -> SpanStyle(fontWeight = FontWeight.Bold)
            "Emphasis" -> SpanStyle(fontStyle = FontStyle.Italic)
            "Strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            "Link" -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            "Code", "FencedCodeBlock", "IndentedCodeBlock" -> SpanStyle(fontFamily = FontFamily.Monospace)
            else -> null
        }
        if (style != null) out.addStyle(style, start, end)
        if (n.kind == "Link" && n.destination.substringBefore(':').lowercase() in setOf("http", "https", "mailto")) out.addLink(LinkAnnotation.Url(n.destination), start, end)
    }
    append(node)
    LiteralSearch.matches(out.toString(), query, caseSensitive).forEach { out.addStyle(SpanStyle(background = highlight), it.start, it.end) }
    return out.toAnnotatedString()
}
