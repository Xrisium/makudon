package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.SourceRange
import io.github.xrisium.makudon.domain.MarkdownParser
import io.github.xrisium.makudon.domain.TextSelection
import io.github.xrisium.makudon.feature.workspace.DisplayChange
import io.github.xrisium.makudon.feature.workspace.MarkdownProjection
import io.github.xrisium.makudon.feature.workspace.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownProjectionTest {
    @Test fun readingAndTypesetResolveEscapesReferencesAndHeadingMarkers() {
        val source = "# 标题 #\n\n副标题\n---\n\n\\*字面\\* &amp; &#x1F600; [引用][id] <https://example.com> ` code `\n\n[id]: https://example.com\n"
        val document = MarkdownParser.parse(source)
        val display = projection(document, null, null).displayText(source)
        org.junit.Assert.assertTrue(display.contains("标题\n\n副标题\n\n*字面* & 😀 引用 https://example.com code"))
        org.junit.Assert.assertFalse(display.contains("[id]"))
        assertEquals(display, readingCopy(document, TextSelection(0,source.length)))
        assertEquals(source.indexOf("&#x1F600;"), readingMatches(document,"😀",true).single().start)
    }

    @Test fun readingCopyExpandsTablesWithoutObjectReplacementOrSourceSyntax() {
        val source = "**before**\n\n| A | B |\n| --- | --- |\n| **bold** | 😀 |\n\nafter"
        val document = MarkdownParser.parse(source)
        val copied = readingCopy(document,TextSelection(0,source.length))
        org.junit.Assert.assertTrue(copied.contains("A\tB") && copied.contains("bold\t😀"))
        org.junit.Assert.assertFalse(copied.contains('\uFFFC') || copied.contains("**") || copied.contains("---"))
    }

    @Test fun caretDisplayWedgeKeepsSupplementaryCodepointWhole() {
        val source = "a😀b"
        val display = caretProjection(MarkdownProjection(emptyList()),source,1)
        assertEquals("a\u200B😀b",display.displayText(source))
        assertEquals(SourceRange(1,3),display.changes.single().range)
        assertEquals(1,display.offset(1))
        assertEquals(1,display.sourceCaretOffset(2))
        assertEquals(3,display.sourceCaretOffset(4))
    }
    @Test fun lineEndContinuesAfterHiddenClosingMarkup() {
        // **a**b displays as ab; the soft-wrap boundary before b is source offset 5.
        val projection = MarkdownProjection(listOf(DisplayChange(SourceRange(0, 2), ""), DisplayChange(SourceRange(3, 5), "")))
        assertEquals(5, projection.sourceCaretOffset(1))
        assertEquals(1, projection.offset(projection.sourceCaretOffset(1)))
    }

    @Test fun lineEndSkipsAdjacentCollapsedMarkers() {
        val projection = MarkdownProjection(listOf(DisplayChange(SourceRange(0, 2), ""), DisplayChange(SourceRange(3, 5), ""), DisplayChange(SourceRange(5, 6), "")))
        assertEquals(6, projection.sourceCaretOffset(1))
    }

    @Test fun lineEndAfterListMarkerDoesNotJumpBackBeforeTheMarker() {
        val projection = MarkdownProjection(listOf(DisplayChange(SourceRange(0, 6), "☐ ")))
        assertEquals(6, projection.sourceCaretOffset(2))
        assertEquals(9, projection.sourceCaretOffset(5))
    }
}
