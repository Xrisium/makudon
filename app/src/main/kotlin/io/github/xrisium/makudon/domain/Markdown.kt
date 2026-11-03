package io.github.xrisium.makudon.domain

import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker

enum class EditorMode { Source, Typeset, Reading }
data class SourceRange(val start: Int, val end: Int) {
    fun intersects(start: Int, end: Int) = if (start == end) start in this.start..this.end else start < this.end && end > this.start
}
data class MdElement(
    val kind: String, val range: SourceRange, val text: String = "", val level: Int = 0,
    val destination: String = "", val children: List<MdElement> = emptyList(),
) {
    fun descendants(): Sequence<MdElement> = sequence { yield(this@MdElement); children.forEach { yieldAll(it.descendants()) } }
}
data class MarkdownDocument(val source: String, val root: MdElement) {
    val elements: List<MdElement> = root.descendants().toList()
    private val protectedRanges = elements.filter { it.kind in setOf("FencedCodeBlock", "IndentedCodeBlock", "Code", "TableBlock", "HtmlBlock", "HtmlInline", "Image", "Raw") }.map { it.range }
    fun raw(node: MdElement) = source.substring(node.range.start, node.range.end)
    fun protected(start: Int, end: Int) = protectedRanges.any { it.intersects(start, end) }
}

/** The AST is for display and targeting only. It is never serialized back over the source. */
object MarkdownParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    fun parse(source: String): MarkdownDocument {
        fun convert(node: Node): MdElement {
            val spans = node.sourceSpans
            val start = spans.minOfOrNull { it.inputIndex } ?: 0
            val end = spans.maxOfOrNull { it.inputIndex + it.length } ?: start
            val children = mutableListOf<MdElement>()
            var child = node.firstChild
            while (child != null) { children += convert(child); child = child.next }
            val text = when (node) {
                is TaskListItemMarker -> if (node.isChecked) "☑ " else "☐ "
                is Text -> node.literal
                is Code -> node.literal
                is FencedCodeBlock -> node.literal
                is IndentedCodeBlock -> node.literal
                is HtmlBlock -> node.literal
                is HtmlInline -> node.literal
                is SoftLineBreak, is HardLineBreak -> "\n"
                else -> ""
            }
            return MdElement(node.javaClass.simpleName, if (node is Document) SourceRange(0, source.length) else SourceRange(start, end), text,
                when (node) { is Heading -> node.level; is OrderedList -> node.markerStartNumber ?: 1; else -> 0 },
                (node as? Link)?.destination.orEmpty(), children)
        }
        return MarkdownDocument(source, convert(parser.parse(source)))
    }
}

data class TextSelection(val start: Int, val end: Int = start) {
    val min get() = minOf(start, end)
    val max get() = maxOf(start, end)
}
data class SourceEdit(val range: SourceRange, val replacement: String)
data class EditResult(val text: String, val selection: TextSelection)
enum class MarkdownAction { Heading, Bold, Italic, Strike, Bullet, Ordered, Task, Unlist, Quote, InlineCode, CodeBlock, Rule, Table, Indent, Outdent }

/** Explicit edits share a small source-range engine with find/replace and structural typing. */
object MarkdownEdits {
    private val container = Regex("^(\\s*(?:> ?)*)(?:(?:[-+*]|\\d+[.)]) (?:\\[[ xX]] )?)?")
    private val list = Regex("^(\\s*(?:> ?)*)([-+*]|\\d+[.)]) (\\[[ xX]] )?(.*)$")
    fun applyEdits(source: String, edits: List<SourceEdit>, selection: TextSelection): EditResult {
        val ordered = edits.sortedBy { it.range.start }
        require(ordered.zipWithNext().all { (a, b) -> a.range.end <= b.range.start })
        fun map(offset: Int): Int {
            var shift = 0
            for (edit in ordered) {
                if (offset < edit.range.start) break
                if (offset == edit.range.end) return edit.range.start + shift + edit.replacement.length
                if (offset < edit.range.end) return edit.range.start + shift + minOf(offset - edit.range.start, edit.replacement.length)
                shift += edit.replacement.length - (edit.range.end - edit.range.start)
            }
            return offset + shift
        }
        val out = StringBuilder(source)
        ordered.asReversed().forEach { out.replace(it.range.start, it.range.end, it.replacement) }
        val text = out.toString()
        TextRules.bytes(text)
        return EditResult(text, TextSelection(map(selection.start), map(selection.end)))
    }
    fun lines(source: String, selection: TextSelection): List<SourceRange> {
        val begin = source.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)) + 1
        val last = if (selection.max > selection.min && source.getOrNull(selection.max - 1) == '\n') selection.max - 1 else selection.max
        val result = mutableListOf<SourceRange>()
        var start = begin
        while (start <= last) {
            val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            result += SourceRange(start, end)
            if (end >= last || end == source.length) break
            start = end + 1
        }
        return result
    }
    fun command(source: String, selection: TextSelection, action: MarkdownAction, level: Int = 0, document: MarkdownDocument = MarkdownParser.parse(source)): EditResult? {
        if (action == MarkdownAction.Heading && level !in 0..6) return null
        if (action !in setOf(MarkdownAction.CodeBlock, MarkdownAction.InlineCode) && document.protected(selection.min, selection.max)) return null
        val marker = when (action) { MarkdownAction.Bold -> "**"; MarkdownAction.Italic -> "*"; MarkdownAction.Strike -> "~~"; MarkdownAction.InlineCode -> "`"; else -> null }
        if (marker != null) return inline(source, selection, action, marker, document)
        if (action in setOf(MarkdownAction.CodeBlock, MarkdownAction.Rule, MarkdownAction.Table)) {
            val content = source.substring(selection.min, selection.max)
            val insert = when (action) {
                MarkdownAction.Rule -> "---"
                MarkdownAction.Table -> "| A | B |\n| --- | --- |\n|  |  |\n|  |  |"
                else -> {
                    val longest = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
                    val fence = "`".repeat(maxOf(3, longest + 1))
                    "$fence\n$content\n$fence"
                }
            }
            val prefix = if (selection.min > 0 && source[selection.min - 1] != '\n') "\n\n" else ""
            val suffix = if (selection.max < source.length && source[selection.max] != '\n') "\n\n" else ""
            val result = applyEdits(source, listOf(SourceEdit(SourceRange(selection.min, selection.max), prefix + insert + suffix)), selection)
            val caret = selection.min + prefix.length + if (action == MarkdownAction.CodeBlock) insert.indexOf('\n') + 1 else insert.length
            return result.copy(selection = TextSelection(caret))
        }
        val ranges = lines(source, selection)
        val quoteOff = action == MarkdownAction.Quote && ranges.all { source.substring(it.start, it.end).trimStart().startsWith('>') }
        val edits = ranges.map { range ->
            val raw = source.substring(range.start, range.end)
            val outer = container.find(raw)!!
            val listMatch = list.matchEntire(raw)
            val prefix = outer.value
            val content = raw.removePrefix(prefix)
            val next = when (action) {
                MarkdownAction.Heading -> prefix + content.replace(Regex("^#{1,6}(?: +|$)"), "").let { if (level == 0) it else "${"#".repeat(level)} $it" }
                MarkdownAction.Quote -> if (quoteOff) raw.replaceFirst(Regex("^(\\s*)> ?"), "$1") else "> $raw"
                MarkdownAction.Indent -> "  $raw"
                MarkdownAction.Outdent -> raw.replaceFirst(Regex("^(?: {1,2}|\\t)"), "")
                MarkdownAction.Unlist -> if (listMatch == null) raw else listMatch.groupValues[1] + listMatch.groupValues[4]
                MarkdownAction.Bullet, MarkdownAction.Ordered, MarkdownAction.Task -> {
                    val base = listMatch?.groupValues?.get(1) ?: outer.groupValues[1]
                    val body = listMatch?.groupValues?.get(4) ?: content
                    val lead = when (action) { MarkdownAction.Bullet -> "- "; MarkdownAction.Ordered -> "${ranges.indexOf(range) + 1}. "; else -> "- [ ] " }
                    base + lead + body
                }
                else -> raw
            }
            // Retain selection relative to the content when only a line prefix changes.
            var common = 0
            while (common < minOf(raw.length, next.length) && raw[raw.lastIndex - common] == next[next.lastIndex - common]) common++
            SourceEdit(SourceRange(range.start, range.end - common), next.substring(0, next.length - common))
        }
        val result = applyEdits(source, edits, selection)
        if (selection.min == selection.max) {
            val shift = edits.filter { it.range.start <= selection.min }.sumOf { it.replacement.length - (it.range.end - it.range.start) }
            return result.copy(selection = TextSelection((selection.start + shift).coerceIn(0, result.text.length)))
        }
        return result
    }
    private fun inline(source: String, selection: TextSelection, action: MarkdownAction, marker: String, document: MarkdownDocument): EditResult? {
        val kind = when (action) { MarkdownAction.Bold -> "StrongEmphasis"; MarkdownAction.Italic -> "Emphasis"; MarkdownAction.Strike -> "Strikethrough"; else -> "Code" }
        val enclosing = document.elements.filter { it.kind == kind && it.range.start <= selection.min && it.range.end >= selection.max }.minByOrNull { it.range.end - it.range.start }
        if (enclosing != null) {
            val raw = document.raw(enclosing)
            val width = if (kind == "Code") raw.takeWhile { it == '`' }.length else marker.length
            if (width == 0) return null
            val contentStart = enclosing.range.start + width
            val contentEnd = enclosing.range.end - width
            if (selection.min != selection.max && (selection.min > contentStart || selection.max < contentEnd) && kind != "Code") {
                val delimiter = raw.take(width)
                val from = selection.min.coerceIn(contentStart, contentEnd)
                val to = selection.max.coerceIn(from, contentEnd)
                val left = source.substring(contentStart, from)
                val center = source.substring(from, to)
                val right = source.substring(to, contentEnd)
                val before = if (left.isEmpty()) "" else delimiter + left + delimiter
                val after = if (right.isEmpty()) "" else delimiter + right + delimiter
                val replacement = before + center + after
                val result = applyEdits(source, listOf(SourceEdit(enclosing.range, replacement)), selection)
                val start = enclosing.range.start + before.length
                return result.copy(selection = TextSelection(start, start + center.length))
            }
            return applyEdits(source, listOf(SourceEdit(SourceRange(enclosing.range.start, enclosing.range.start + width), ""), SourceEdit(SourceRange(enclosing.range.end - width, enclosing.range.end), "")), selection)
        }
        if (document.protected(selection.min, selection.max)) return null
        val edits = mutableListOf<SourceEdit>()
        for (range in lines(source, selection)) {
            val line = source.substring(range.start, range.end)
            val prefix = container.find(line)!!.value.length
            val heading = Regex("^#{1,6} +").find(line.substring(prefix))?.value?.length ?: 0
            val start = if (selection.min == selection.max) selection.min else maxOf(selection.min, range.start + prefix + heading).coerceAtMost(range.end)
            val end = minOf(selection.max, range.end)
            if (end < start) continue
            if (start == end && selection.min != selection.max) continue
            var content = source.substring(start, end)
            val width = if (kind == "Code") "`".repeat((Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1) else marker
            if (kind != "Code") {
                // Remove existing same-kind delimiters fully contained by the selection before applying once.
                val nested = document.elements.filter { it.kind == kind && it.range.start >= start && it.range.end <= end }
                val remove = nested.flatMap { listOf(SourceRange(it.range.start - start, it.range.start - start + marker.length), SourceRange(it.range.end - start - marker.length, it.range.end - start)) }.distinct()
                val b = StringBuilder(content)
                remove.sortedByDescending { it.start }.forEach { b.delete(it.start, it.end) }; content = b.toString()
            }
            val padding = if (kind == "Code" && (content.startsWith('`') || content.endsWith('`'))) " " else ""
            edits += SourceEdit(SourceRange(start, end), width + padding + content + padding + width)
        }
        if (edits.isEmpty()) return null
        val result = applyEdits(source, edits, selection)
        if (selection.min == selection.max) return result.copy(selection = TextSelection(selection.min + marker.length))
        val first = edits.firstOrNull() ?: return result
        val last = edits.last()
        return result.copy(selection = TextSelection(first.range.start + marker.length, last.range.end + edits.sumOf { it.replacement.length - (it.range.end - it.range.start) } - marker.length))
    }
    fun link(source: String, selection: TextSelection, label: String, url: String, remove: Boolean = false, document: MarkdownDocument = MarkdownParser.parse(source)): EditResult? {
        val existing = document.elements.firstOrNull { it.kind == "Link" && it.range.start <= selection.min && it.range.end >= selection.max }
        val range = existing?.range ?: SourceRange(selection.min, selection.max)
        if ('\n' in source.substring(range.start, range.end) || document.protected(range.start, range.end)) return null
        fun escape(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        fun visible(node: MdElement): String = if (node.children.isEmpty()) node.text else node.children.joinToString("") { visible(it) }
        val raw = existing?.let { document.raw(it) }
        val labelEnd = raw?.lastIndexOf("](") ?: -1
        val markdownLabel = if (existing != null && label == visible(existing) && raw?.startsWith('[') == true && labelEnd > 0) raw.substring(1, labelEnd) else escape(label)
        val next = if (remove) label else "[$markdownLabel](<${url.replace("\\", "\\\\").replace("<", "%3C").replace(">", "%3E").replace("\n", "")}>)"
        val result = applyEdits(source, listOf(SourceEdit(range, next)), selection)
        return result.copy(selection = TextSelection(range.start + next.length))
    }
    /** Only single-character Enter/Backspace events are structural; bulk paste remains literal. */
    fun structural(before: String, after: String, selection: TextSelection): EditResult? {
        if (selection.start != selection.end) return null
        val caret = selection.start
        if (after.length == before.length + 1 && caret > 0 && after[caret - 1] == '\n' && after.removeRange(caret - 1, caret) == before) {
            val start = before.lastIndexOf('\n', caret - 2) + 1
            val line = before.substring(start, caret - 1)
            val match = list.matchEntire(line)
            if (match != null) {
                val base = match.groupValues[1]
                if (match.groupValues[4].isEmpty()) {
                    val replacement = if (base.endsWith("  ")) base.dropLast(2) + match.groupValues[2] + " " + if (match.groupValues[3].isNotEmpty()) "[ ] " else "" else base
                    val text = after.replaceRange(start, caret, replacement)
                    return EditResult(text, TextSelection(start + replacement.length))
                }
                val mark = match.groupValues[2]
                val number = mark.dropLast(1).toIntOrNull()
                val lead = base + (if (number != null) "${number + 1}${mark.last()}" else mark) + " " + if (match.groupValues[3].isNotEmpty()) "[ ] " else ""
                return EditResult(after.substring(0, caret) + lead + after.substring(caret), TextSelection(caret + lead.length))
            }
            if (line.matches(Regex("\\s*(?:> ?)+"))) {
                val shorter = line.replaceFirst(Regex("> ?$"), "")
                return EditResult(after.replaceRange(start, caret, shorter), TextSelection(start + shorter.length))
            }
            val quote = Regex("^\\s*(?:> ?)+").find(line)?.value
            if (quote != null) return EditResult(after.substring(0, caret) + quote + after.substring(caret), TextSelection(caret + quote.length))
        }
        if (after.length == before.length - 1 && before.removeRange(caret, caret + 1) == after) {
            val start = before.lastIndexOf('\n', caret - 1) + 1
            val end = before.indexOf('\n', start).let { if (it < 0) before.length else it }
            val line = before.substring(start, end)
            val prefix = Regex("^(?:\\s*(?:> ?)*)(?:(?:#{1,6}|[-+*]|\\d+[.)]) (?:\\[[ xX]] )?)").find(line)?.value
                ?: Regex("^\\s*> ?").find(line)?.value
            if (prefix != null && caret + 1 == start + prefix.length) {
                val outer = list.matchEntire(line)?.groupValues?.get(1)
                    ?: Regex("^(\\s*(?:> ?)*)(?:#{1,6}) ").find(line)?.groupValues?.get(1)
                    ?: prefix.replaceFirst(Regex("> ?$"), "")
                val text = before.replaceRange(start, start + prefix.length, outer)
                return EditResult(text, TextSelection(start + outer.length))
            }
        }
        return null
    }
}

object LiteralSearch {
    fun matches(text: String, query: String, caseSensitive: Boolean): List<SourceRange> {
        if (query.isEmpty()) return emptyList()
        val result = mutableListOf<SourceRange>(); var start = 0
        while (start <= text.length - query.length) {
            val at = text.indexOf(query, start, !caseSensitive); if (at < 0) break
            result += SourceRange(at, at + query.length); start = at + query.length
        }
        return result
    }
    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean, selection: TextSelection) =
        MarkdownEdits.applyEdits(text, matches(text, query, caseSensitive).map { SourceEdit(it, replacement) }, selection)
}
