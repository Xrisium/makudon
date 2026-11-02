package io.github.xrisium.makudon.feature.workspace

import io.github.xrisium.makudon.domain.TextSelection

/** Bounded text deltas shared by source and paragraph editing; no full-document stack per keystroke. */
class EditHistory {
    private data class Edit(val start: Int, val removed: String, val inserted: String, val before: TextSelection, val after: TextSelection)
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    var restoredSelection = TextSelection(0)
        private set
    fun record(before: String, after: String, beforeSelection: TextSelection = TextSelection(0), afterSelection: TextSelection = TextSelection(0)) {
        if (before == after) return
        var start = 0
        while (start < minOf(before.length, after.length) && before[start] == after[start]) start++
        var end = 0
        while (end < minOf(before.length, after.length) - start && before[before.length - end - 1] == after[after.length - end - 1]) end++
        undo.addLast(Edit(start, before.substring(start, before.length - end), after.substring(start, after.length - end), beforeSelection, afterSelection))
        redo.clear()
        while (undo.size > 100 || undo.sumOf { it.removed.length + it.inserted.length } > 2_097_152) undo.removeFirst()
    }
    fun undo(text: String): String {
        val edit = undo.removeLastOrNull() ?: return text
        check(text.regionMatches(edit.start, edit.inserted, 0, edit.inserted.length))
        redo.addLast(edit)
        restoredSelection = edit.before
        return text.replaceRange(edit.start, edit.start + edit.inserted.length, edit.removed)
    }
    fun redo(text: String): String {
        val edit = redo.removeLastOrNull() ?: return text
        check(text.regionMatches(edit.start, edit.removed, 0, edit.removed.length))
        undo.addLast(edit)
        restoredSelection = edit.after
        return text.replaceRange(edit.start, edit.start + edit.removed.length, edit.inserted)
    }
}
