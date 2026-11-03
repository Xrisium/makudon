package io.github.xrisium.makudon.domain

import org.commonmark.internal.util.Escaping

/** Visible cell text with a source range for each UTF-16 unit; syntax is never editable UI. */
data class MarkdownCellText(val text: String, val range: SourceRange, val units: List<SourceRange>,
    val paddingBefore: String = "", val paddingAfter: String = "", val codes: List<MarkdownCellSpan> = emptyList(),
    val containers: List<MarkdownCellSpan> = emptyList()) {
    fun replace(source: String, next: String): MarkdownCellEdit {
        if (next.isEmpty()) {
            val result = MarkdownEdits.applyEdits(source, listOf(SourceEdit(range, "")), TextSelection(range.start))
            return MarkdownCellEdit(result, MarkdownCellText("", SourceRange(range.start, range.start), emptyList()))
        }
        var prefix = 0
        while (prefix < minOf(text.length, next.length) && text[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length, next.length) - prefix && text[text.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        while (prefix > 0 && prefix < units.size && units[prefix] == units[prefix - 1]) prefix--
        while (suffix > 0 && text.length - suffix > 0 && units[text.length - suffix] == units[text.length - suffix - 1]) suffix--
        val end = text.length - suffix
        val code = codes.firstOrNull { prefix >= it.visible.start && end <= it.visible.end &&
            !(prefix == it.visible.start && end == it.visible.end && next.length - suffix == prefix) }
        if (code != null) {
            val payload = text.substring(code.visible.start, prefix) + next.substring(prefix, next.length - suffix) + text.substring(end, code.visible.end)
            val fence = "`".repeat(maxOf(1, (Regex("`+").findAll(payload).maxOfOrNull { it.value.length } ?: 0) + 1))
            val pad = if (payload.startsWith('`') || payload.endsWith('`') || (payload.startsWith(' ') && payload.endsWith(' ') && payload.any { it != ' ' })) " " else ""
            val replacement = fence + pad + payload.replace("|", "\\|") + pad + fence
            val result = MarkdownEdits.applyEdits(source, listOf(SourceEdit(code.source, replacement)), TextSelection(code.source.start))
            val shift = replacement.length - (code.source.end - code.source.start)
            val visibleShift = payload.length - (code.visible.end - code.visible.start)
            var at = code.source.start + fence.length + pad.length
            val codeUnits = payload.map { char -> val to = at + if (char == '|') 2 else 1; SourceRange(at, to).also { at = to } }
            val nextUnits = units.take(code.visible.start) + codeUnits + units.drop(code.visible.end).map { SourceRange(it.start + shift, it.end + shift) }
            val nextCodes = codes.map {
                when { it == code -> MarkdownCellSpan(SourceRange(it.source.start, it.source.start + replacement.length), SourceRange(it.visible.start, it.visible.start + payload.length))
                    it.source.start > code.source.start -> MarkdownCellSpan(SourceRange(it.source.start + shift, it.source.end + shift), SourceRange(it.visible.start + visibleShift, it.visible.end + visibleShift))
                    else -> it }
            }
            val caret = codeUnits.getOrNull(next.length - suffix - code.visible.start - 1)?.end ?: code.source.start + fence.length + pad.length
            return preserveBoundarySpaces(result.copy(selection = TextSelection(caret)),
                MarkdownCellText(next, SourceRange(range.start, range.end + shift), nextUnits, codes = nextCodes,
                    containers = containers.map {
                        MarkdownCellSpan(SourceRange(it.source.start + if (it.source.start >= code.source.end) shift else 0, it.source.end + if (it.source.end >= code.source.end) shift else 0),
                            SourceRange(it.visible.start + if (it.visible.start >= code.visible.end) visibleShift else 0, it.visible.end + if (it.visible.end >= code.visible.end) visibleShift else 0))
                    }))
        }
        val start = units.getOrNull(prefix)?.start ?: units.lastOrNull()?.end ?: range.start
        val inserted = next.substring(prefix, next.length - suffix)
        val leadingSpaces = next.takeWhile { it == ' ' }.length
        val trailingSpaces = next.length - next.takeLastWhile { it == ' ' }.length
        fun encode(char: Char, index: Int) = if (char == ' ' && (index < leadingSpaces || index >= trailingSpaces)) "&#32;" else escape(char.toString())
        val encoded = inserted.mapIndexed { index, char -> encode(char, prefix + index) }
        val replacement = paddingBefore + encoded.joinToString("") + paddingAfter
        // Remove visible units only, so edits spanning emphasis/link boundaries keep balanced syntax.
        val removed = mutableListOf<SourceRange>()
        units.subList(prefix, end).distinct().forEach { unit ->
            val last = removed.lastOrNull()
            if (last != null && last.end == unit.start) removed[removed.lastIndex] = SourceRange(last.start, unit.end)
            else removed += unit
        }
        val erased = containers.filter { it.visible.start >= prefix && it.visible.end <= end &&
            (inserted.isEmpty() || prefix !in it.visible.start until it.visible.end) }
        val removals = (removed + erased.map { it.source }).sortedWith(compareBy<SourceRange> { it.start }.thenByDescending { it.end })
        val merged = mutableListOf<SourceRange>()
        removals.forEach { part ->
            val last = merged.lastOrNull()
            if (last != null && part.start <= last.end) merged[merged.lastIndex] = SourceRange(last.start, maxOf(last.end, part.end))
            else merged += part
        }
        val edits = if (merged.isEmpty()) listOf(SourceEdit(SourceRange(start, start), replacement)) else
            merged.mapIndexed { index, part -> SourceEdit(part, if (index == 0) replacement else "") }
        val result = MarkdownEdits.applyEdits(source, edits, TextSelection(start))
        val shifts = IntArray(edits.size + 1)
        edits.forEachIndexed { index, edit -> shifts[index + 1] = shifts[index] + edit.replacement.length - (edit.range.end - edit.range.start) }
        fun map(at: Int): Int {
            var low = 0; var high = edits.size
            while (low < high) { val middle = (low + high) ushr 1; if (edits[middle].range.end <= at) low = middle + 1 else high = middle }
            return at + shifts[low]
        }
        val insertedUnits = mutableListOf<SourceRange>()
        var at = (merged.firstOrNull()?.start ?: start) + paddingBefore.length
        encoded.forEach { token -> val to = at + token.length; insertedUnits += SourceRange(at, to); at = to }
        val nextUnits = units.take(prefix) + insertedUnits + units.drop(end).map { SourceRange(map(it.start), map(it.end)) }
        val nextRange = SourceRange(range.start + paddingBefore.length, range.end + result.text.length - source.length - paddingAfter.length)
        val visibleShift = next.length - text.length
        fun display(at: Int) = when { at <= prefix -> at; at >= end -> at + visibleShift; else -> prefix + inserted.length }
        fun remap(codeRange: MarkdownCellSpan) = MarkdownCellSpan(SourceRange(map(codeRange.source.start), map(codeRange.source.end)),
            SourceRange(display(codeRange.visible.start), display(codeRange.visible.end)))
        val nextCodes = codes.filter { it !in erased }.map { codeRange ->
            remap(codeRange)
        }
        return preserveBoundarySpaces(result.copy(selection = TextSelection(at)), MarkdownCellText(next, nextRange, nextUnits, codes = nextCodes,
            containers = containers.filter { it !in erased }.map(::remap)))
    }

    private fun preserveBoundarySpaces(result: EditResult, cell: MarkdownCellText): MarkdownCellEdit {
        val leading = cell.text.takeWhile { it == ' ' }.length
        val trailing = cell.text.length - cell.text.takeLastWhile { it == ' ' }.length
        val edits = cell.units.mapIndexedNotNull { index, unit ->
            if ((index < leading || index >= trailing) && cell.codes.none { index in it.visible.start until it.visible.end } &&
                result.text.substring(unit.start, unit.end) == " ") SourceEdit(unit, "&#32;") else null
        }.distinct()
        if (edits.isEmpty()) return MarkdownCellEdit(result, cell)
        val changed = MarkdownEdits.applyEdits(result.text, edits, result.selection)
        fun at(offset: Int): Int {
            var low = 0; var high = edits.size
            while (low < high) { val middle = (low + high) ushr 1; if (edits[middle].range.end <= offset) low = middle + 1 else high = middle }
            return offset + 4 * low
        }
        fun range(value: SourceRange) = SourceRange(at(value.start), at(value.end))
        return MarkdownCellEdit(changed, cell.copy(range = range(cell.range), units = cell.units.map(::range),
            codes = cell.codes.map { it.copy(source = range(it.source)) }, containers = cell.containers.map { it.copy(source = range(it.source)) }))
    }

    companion object {
        private fun escape(text: String): String = buildString {
            text.forEach { char ->
                if (char in "\\`*_{}[]()<>#+-.!|&~") append('\\')
                append(char)
            }
        }
    }
}

data class MarkdownCellEdit(val result: EditResult, val cell: MarkdownCellText)
data class MarkdownCellSpan(val source: SourceRange, val visible: SourceRange)

object MarkdownTableEdits {
    /** Typeset's collapsed table ends before its trailing newline. Keep input just
     * after that slot outside GFM's permissive continuation rows, including IME text. */
    fun followingBlock(document: MarkdownDocument, originalSelection: TextSelection, after: String): SourceEdit? {
        if (originalSelection.min != originalSelection.max || after.length <= document.source.length) return null
        val before = document.source
        val at = originalSelection.start
        val table = document.elements.firstOrNull { it.kind == "TableBlock" &&
            (at == it.range.end || (at == it.range.end + 1 && before.getOrNull(it.range.end) == '\n')) } ?: return null
        val inserted = after.length - before.length
        if (!after.regionMatches(0, before, 0, at) ||
            !after.regionMatches(at + inserted, before, at, before.length - at)) return null
        val end = table.range.end
        var newlines = 0
        while (after.getOrNull(end + newlines) == '\n') newlines++
        return if (newlines < 2) SourceEdit(SourceRange(end, end), "\n".repeat(2 - newlines)) else null
    }

    fun cells(document: MarkdownDocument, row: MdElement): List<MarkdownCellText> {
        val raw = document.raw(row)
        val pipes = raw.indices.filter { raw[it] == '|' && (it == 0 || raw[it - 1] != '\\') }
        val boundaries = listOf(-1) + pipes + raw.length
        val ranges = boundaries.zipWithNext().map { (a, b) ->
            var start = a + 1; var end = b
            while (start < end && raw[start].isWhitespace()) start++
            while (end > start && raw[end - 1].isWhitespace()) end--
            if (start == end && b - a > 2) { start = a + 2; end = start }
            SourceRange(row.range.start + start, row.range.start + end)
        }.toMutableList()
        if (raw.trimStart().startsWith('|')) ranges.removeAt(0)
        if (raw.trimEnd().endsWith('|') && pipes.lastOrNull() == raw.indexOfLast { !it.isWhitespace() }) ranges.removeAt(ranges.lastIndex)
        return row.children.mapIndexed { index, cell ->
            val range = ranges.getOrNull(index)
            if (range == null) MarkdownCellText("", SourceRange(row.range.end, row.range.end), emptyList(),
                (if (raw.trimEnd().endsWith('|')) " " else " | ") + " | ".repeat(index - ranges.size), " |")
            else content(document, cell, range)
        }
    }

    private fun content(document: MarkdownDocument, cell: MdElement, range: SourceRange): MarkdownCellText {
        val source = document.source
        val text = StringBuilder()
        val units = mutableListOf<SourceRange>()
        val codes = mutableListOf<MarkdownCellSpan>()
        val containers = mutableListOf<MarkdownCellSpan>()
        // GFM removes the slash in \| before inline parsing, while its child spans
        // still use that shortened input. Translate those offsets back to the real file.
        val boundaries = mutableListOf(cell.range.start)
        val inline = StringBuilder()
        var cursor = cell.range.start
        while (cursor < cell.range.end) {
            if (source[cursor] == '\\' && source.getOrNull(cursor + 1) == '|') { inline.append('|'); cursor += 2 }
            else { inline.append(source[cursor]); cursor++ }
            boundaries += cursor
        }
        fun actual(at: Int) = boundaries.getOrElse((at - cell.range.start).coerceAtLeast(0)) { cell.range.end }
        fun append(rawRange: SourceRange, literal: Boolean = false) {
            var at = (rawRange.start - cell.range.start).coerceIn(0, inline.length)
            val end = (rawRange.end - cell.range.start).coerceIn(at, inline.length)
            while (at < end) {
                var to = at + 1
                if (!literal && inline[at] == '\\' && to < end && inline[to] in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~") to++
                else if (!literal && inline[at] == '&') {
                    val semicolon = inline.indexOf(";", at + 1)
                    if (semicolon in (at + 1) until minOf(end, at + 34)) to = semicolon + 1
                }
                val raw = inline.substring(at, to)
                val decoded = if (literal) raw else Escaping.unescapeString(raw)
                decoded.forEach { text.append(it); units += SourceRange(boundaries[at], boundaries[to]) }
                at = to
            }
        }
        fun visit(node: MdElement) {
            val actualRange = SourceRange(actual(node.range.start), actual(node.range.end))
            val visibleStart = text.length
            when (node.kind) {
                "Text" -> append(node.range)
                "Code" -> {
                    val raw = inline.substring((node.range.start - cell.range.start).coerceIn(0, inline.length), (node.range.end - cell.range.start).coerceIn(0, inline.length))
                    val ticks = raw.takeWhile { it == '`' }.length
                    var start = node.range.start + ticks; var end = node.range.end - ticks
                    if (start < end && inline[start - cell.range.start] == ' ' && inline[end - cell.range.start - 1] == ' ' && raw.trim('`').any { it != ' ' }) { start++; end-- }
                    val visibleStart = text.length
                    append(SourceRange(start, end), literal = true)
                    codes += MarkdownCellSpan(actualRange, SourceRange(visibleStart, text.length))
                }
                "Image", "HtmlInline" -> append(node.range, literal = true)
                else -> node.children.forEach(::visit)
            }
            if (node.kind in setOf("StrongEmphasis", "Emphasis", "Strikethrough", "Link", "Code"))
                containers += MarkdownCellSpan(actualRange, SourceRange(visibleStart, text.length))
        }
        visit(cell)
        return MarkdownCellText(text.toString(), range, units, codes = codes, containers = containers)
    }
}
