package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownTableEditsTest {
    private fun cells(source: String, row: Int = 1): List<MarkdownCellText> {
        val doc = MarkdownParser.parse(source)
        return MarkdownTableEdits.cells(doc, doc.elements.filter { it.kind == "TableRow" }[row])
    }

    @Test fun cellInputKeepsDelimitersAndOtherSourceExactly() {
        val source = "前文 😀\n\n| 标题 | B |\n| :--- | ---: |\n| 中文 |  |\n\n尾部"
        val cell = cells(source)[0]
        assertEquals("中文", cell.text)
        val edit = cell.replace(source, "修改 😀")
        assertEquals(source.replace("中文", "修改 😀"), edit.result.text)
        val empty = cells(edit.result.text)[1]
        assertEquals("", empty.text)
        assertEquals(edit.result.text.replace("|  |", "| 空格 |"), empty.replace(edit.result.text, "空格").result.text)
    }

    @Test fun pipeBackslashAndMarkdownPunctuationStayLiteral() {
        val source = "A | B\n---|---\n中 | 后"
        val edit = cells(source)[0].replace(source, "中 | **字** \\ 😀")
        assertEquals("中 | **字** \\ 😀", cells(edit.result.text)[0].text)
        assertEquals(2, cells(edit.result.text).size)
        val next = edit.cell.replace(edit.result.text, "中 | **字** \\ 😀续")
        assertEquals("中 | **字** \\ 😀续", cells(next.result.text)[0].text)
    }

    @Test fun visibleMappingKeepsExistingInlineFormattingAndLinkDestination() {
        val source = "| A | B |\n|---|---|\n| **粗体** &amp; [链接](https://example.com) | 后 |"
        val cell = cells(source)[0]
        assertEquals("粗体 & 链接", cell.text)
        val changed = cell.replace(source, "粗字 & 链接")
        assertEquals(source.replace("**粗体**", "**粗字**"), changed.result.text)
        assertEquals("粗字 & 链接", cells(changed.result.text)[0].text)
    }

    @Test fun escapedPipesAndEmptyHeaderUseActualCellBoundaries() {
        val source = "|  | B |\n|---|---|\n| a\\|b | |"
        assertEquals("a|b", cells(source)[0].text)
        assertEquals("", cells(source, 0)[0].text)
        val edit = cells(source, 0)[0].replace(source, "标题")
        assertEquals(source.replace("|  | B", "| 标题 | B"), edit.result.text)
    }

    @Test fun missingCellsCanBeFilledWithoutChangingPreviousColumns() {
        val source = "| A | B | C |\n|---|---|---|\n| 有值 |"
        val edit = cells(source)[2].replace(source, "补齐")
        assertEquals(listOf("有值", "", "补齐"), cells(edit.result.text).map { it.text })
    }

    @Test fun clearingFormattedCellRemovesInvisibleSyntaxAndKeepsOtherCells() {
        val source = "| A | B |\n|---|---|\n| **字**与[链接](https://example.com) | 保留 |"
        val edit = cells(source)[0].replace(source, "")
        assertEquals(source.replace("**字**与[链接](https://example.com)", ""), edit.result.text)
        assertEquals(listOf("", "保留"), cells(edit.result.text).map { it.text })
    }

    @Test fun editingCodeKeepsItsVisibleCharacters() {
        val source = "| A | B |\n|---|---|\n| `a` | 保留 |"
        val edit = cells(source)[0].replace(source, "a*b")
        assertEquals("a*b", cells(edit.result.text)[0].text)
    }

    @Test fun typedBoundarySpacesSurviveAParseBetweenKeystrokes() {
        val source = "| A | B |\n|---|---|\n| word | 后 |"
        val first = cells(source)[0].replace(source, "word ")
        assertEquals("word ", cells(first.result.text)[0].text)
        val second = cells(first.result.text)[0].replace(first.result.text, "word next")
        assertEquals("word next", cells(second.result.text)[0].text)
    }

    @Test fun visibleEditsAcrossInlineBoundariesKeepExpectedText() {
        val header = "| A | B |\n|---|---|\n| "
        val tail = " | 后 |"
        listOf("**a** [bc](https://example.com) d", "a &amp; b", "`ab` c", "a **bc** d", "a `bc` d").forEach { content ->
            val source = header + content + tail
            val cell = cells(source)[0]
            for (start in cell.text.indices) {
                for (end in start + 1..cell.text.length) {
                    listOf("字", "").forEach { replacement ->
                        val next = cell.text.replaceRange(start, end, replacement)
                        val edit = cell.replace(source, next)
                        assertEquals("$content $start..$end", next, cells(edit.result.text)[0].text)
                    }
                }
            }
        }
    }

    @Test fun quotedTablesAndRepeatedEscapesUseParserCellBoundaries() {
        val quote = "> | A | B |\n> |---|---|\n> | 原文 | 后 |"
        assertEquals(quote.replace("原文", "新字"), cells(quote)[0].replace(quote, "新字").result.text)
        for (slashes in 1..4) {
            val source = "| A | B |\n|---|---|\n| a" + "\\".repeat(slashes) + "|b | 后 |"
            val doc = MarkdownParser.parse(source)
            val row = doc.elements.filter { it.kind == "TableRow" }[1]
            val expected = row.children.map { cell -> cell.descendants().filter { it.kind == "Text" }.joinToString("") { it.text } }
            assertEquals("slashes=$slashes", expected, MarkdownTableEdits.cells(doc, row).map { it.text })
        }
    }
}
