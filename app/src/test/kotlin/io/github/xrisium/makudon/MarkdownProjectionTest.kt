package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.SourceRange
import io.github.xrisium.makudon.feature.workspace.DisplayChange
import io.github.xrisium.makudon.feature.workspace.MarkdownProjection
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownProjectionTest {
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
