package io.github.xrisium.makudon

import io.github.xrisium.makudon.feature.workspace.EditHistory
import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    @Test fun sourceAndParagraphDeltasUndoAcrossModesWithoutRewritingOtherSyntax() {
        val history = EditHistory()
        val a = "# 标题\n\n段落 😀\n\n```保留```\n"
        val b = a.replace("标题", "新标题")
        val c = b.replace("段落 😀", "段落\n\n追加 👩🏽‍💻")
        history.record(a, b); history.record(b, c)
        assertEquals(b, history.undo(c)); assertEquals(a, history.undo(b))
        assertEquals(b, history.redo(a)); assertEquals(c, history.redo(b))
        val d = c + "新输入"; history.record(c, d)
        assertFalse(history.canRedo)
        assertEquals(c, history.undo(d))
    }
    @Test fun longTypingHistoryIsBoundedButLatestEditsRemainUndoable() {
        val history = EditHistory(); var current = ""
        repeat(200) { val next = current + "😀"; history.record(current, next); current = next }
        var count = 0
        while (history.canUndo) { current = history.undo(current); count++ }
        assertEquals(100, count); assertEquals("😀".repeat(100), current)
    }
}
