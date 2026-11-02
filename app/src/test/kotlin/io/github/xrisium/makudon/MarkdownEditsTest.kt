package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.*
import io.github.xrisium.makudon.feature.workspace.EditHistory
import io.github.xrisium.makudon.feature.workspace.projection
import io.github.xrisium.makudon.feature.workspace.provisionalDocument
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditsTest {
    private fun edit(source: String, selection: TextSelection, action: MarkdownAction, level: Int = 0) = checkNotNull(MarkdownEdits.command(source, selection, action, level))
    @Test fun parserKeepsUtf16SourceOffsetsAcrossEmojiAndNestedSyntax() {
        val source = "# 标题 😀\n\n> **加粗**和[链接](https://example.com)\n\n- [x] 完成\n\n| A | B |\n|---|---|\n| 中 | 😀 |\n\n~~删除~~"
        val doc = MarkdownParser.parse(source)
        assertEquals("**加粗**", doc.raw(doc.elements.single { it.kind == "StrongEmphasis" }))
        assertEquals("[链接](https://example.com)", doc.raw(doc.elements.single { it.kind == "Link" }))
        assertTrue(doc.elements.any { it.kind == "TableBlock" })
        assertTrue(doc.elements.any { it.kind == "Strikethrough" })
        assertTrue(doc.elements.any { it.kind == "TaskListItemMarker" })
    }
    @Test fun headingChangesOnlyCurrentPrefixAndKeepsUnknownSuffix() {
        val original = "> - 中文 😀\n\n<div>unknown</div>\n\n```x\nkeep\n```\n"
        val result = edit(original, TextSelection(6), MarkdownAction.Heading, 4)
        assertEquals("> - #### 中文 😀" + original.substring(original.indexOf('\n')), result.text)
    }
    @Test fun inlineCommandAndUndoRestoreSelectionAndUntouchedSeparators() {
        val text = "正文 😀\n\n  未知 {{keep}}\n"
        val selected = TextSelection(0, 2)
        val result = edit(text, selected, MarkdownAction.Bold)
        assertEquals("**正文**" + text.substring(2), result.text)
        val history = EditHistory(); history.record(text, result.text, selected, result.selection)
        assertEquals(text, history.undo(result.text)); assertEquals(selected, history.restoredSelection)
        assertEquals(result.text, history.redo(text)); assertEquals(result.selection, history.restoredSelection)
    }
    @Test fun toggleExistingStrongInsideContentRemovesOnlyThatPair() {
        val text = "a **中文 😀** z\n\n未知"
        assertEquals("a 中文 😀 z\n\n未知", edit(text, TextSelection(6), MarkdownAction.Bold).text)
    }
    @Test fun mixedStrongSelectionIsAppliedOnce() {
        val text = "**一**和二"
        assertEquals("**一和二**", edit(text, TextSelection(0, text.length), MarkdownAction.Bold).text)
    }
    @Test fun multilineInlineFormattingDoesNotCrossBlankLines() {
        val text = "第一段\n\n第二段"
        assertEquals("~~第一段~~\n\n~~第二段~~", edit(text, TextSelection(0, text.length), MarkdownAction.Strike).text)
    }
    @Test fun codeAndUnknownStructuresRejectFormatWithoutPartialChange() {
        val text = "正文\n\n```\n代码\n```"
        assertNull(MarkdownEdits.command(text, TextSelection(0, text.length), MarkdownAction.Bold))
        val html = "<div>保留 **原文**</div>"
        assertNull(MarkdownEdits.command(html, TextSelection(8), MarkdownAction.Heading, 1))
    }
    @Test fun inlineCodeChoosesDelimiterLongerThanContentRuns() {
        val text = "a`b"
        assertEquals("``a`b``", edit(text, TextSelection(0, text.length), MarkdownAction.InlineCode).text)
        val ticks = "`a`"
        val result = edit("x $ticks y", TextSelection(2, 5), MarkdownAction.CodeBlock)
        assertTrue(result.text.contains("```\n`a`\n```"))
    }
    @Test fun listConversionPreservesChildrenAndQuoteContainers() {
        val text = "> - 项\n>   - 子项\n\n尾部"
        assertEquals("> - [ ] 项\n>   - 子项\n\n尾部", edit(text, TextSelection(5), MarkdownAction.Task).text)
    }
    @Test fun listContinuationAndExitAreAtomicEdits() {
        val before = "- [x] 完成"
        val after = "$before\n"
        assertEquals("$before\n- [ ] ", MarkdownEdits.structural(before, after, TextSelection(after.length))?.text)
        assertEquals("", MarkdownEdits.structural("- ", "- \n", TextSelection(3))?.text)
        assertEquals("3. 项\n4. ", MarkdownEdits.structural("3. 项", "3. 项\n", TextSelection(5))?.text)
    }
    @Test fun nestedEmptyItemAndQuoteExitOneLevel() {
        assertEquals("- ", MarkdownEdits.structural("  - ", "  - \n", TextSelection(5))?.text)
        assertEquals("> ", MarkdownEdits.structural("> > ", "> > \n", TextSelection(5))?.text)
    }
    @Test fun backspaceAtContentStartRemovesStructureBeforePreviousText() {
        val before = "上一段\n\n## 标题"
        val caret = before.indexOf("标题")
        val after = before.removeRange(caret - 1, caret)
        assertEquals("上一段\n\n标题", MarkdownEdits.structural(before, after, TextSelection(caret - 1))?.text)
    }
    @Test fun pasteWithMultipleCharactersIsNotContinuedOrRewritten() {
        assertNull(MarkdownEdits.structural("- 项", "- 项\n粘贴", TextSelection(6)))
    }
    @Test fun structuralBackspacePreservesOuterQuoteAndNestedIndent() {
        assertEquals("> 项", MarkdownEdits.structural("> - 项", "> -项", TextSelection(3))?.text)
        assertEquals("> 文", MarkdownEdits.structural("> ## 文", "> ##文", TextSelection(4))?.text)
    }
    @Test fun linkEditsUseRangesAndEscapeLabelAndDestination() {
        val source = "前 [旧](https://old.example) 后"
        val doc = MarkdownParser.parse(source)
        val result = MarkdownEdits.link(source, TextSelection(4), "名[称]", "https://example.com/a(b)", document = doc)!!
        assertEquals("前 [名\\[称\\]](<https://example.com/a(b)>) 后", result.text)
        assertEquals("前 旧 后", MarkdownEdits.link(source, TextSelection(4), "旧", "", true, doc)?.text)
    }
    @Test fun literalReplaceAllSupportsDollarBackslashAndEmojiWithoutRegex() {
        val result = LiteralSearch.replaceAll("A.a 😀 A.a", "A.a", "$\\😀", true, TextSelection(0))
        assertEquals("$\\😀 😀 $\\😀", result.text)
        assertEquals(2, LiteralSearch.matches("a A", "a", false).size)
    }
    @Test fun overLimitReplaceIsRejectedAsWholeOperation() {
        try { LiteralSearch.replaceAll("aaa", "a", "字".repeat(40_000), true, TextSelection(0)); fail() }
        catch (e: WorkspaceException) { assertEquals(Failure.TooLarge, e.reason) }
    }
    @Test fun projectionNeverMutatesSourceAndRevealsSelectedDelimiters() {
        val source = "# 标题\n\n**粗体** [链接](https://example.com)"
        val parsed = MarkdownParser.parse(source)
        val p = projection(parsed, null, null)
        var display = source
        p.changes.asReversed().forEach { display = display.replaceRange(it.range.start, it.range.end, it.text) }
        assertEquals("标题\n\n粗体 链接", display)
        val active = projection(parsed, TextSelection(source.indexOf("粗体")), null)
        assertFalse(active.changes.any { it.range.start == source.indexOf("**") })
        assertEquals(source, parsed.source)
    }
    @Test fun compositionRevealsInlineSourceWithoutLosingOtherStyles() {
        val source = "**组合** 和 *斜体*"
        val p = projection(MarkdownParser.parse(source), null, SourceRange(2, 4))
        assertFalse(p.changes.any { it.range.start == 0 })
        assertTrue(p.changes.any { it.range.end == source.length })
    }
    @Test fun staleParseRebasesUntouchedContainersAndExposesChangedParagraph() {
        val source = "# 标题\n\n段落\n\n**尾部**"
        val next = source.replace("段落", "更长的段落 😀")
        val doc = provisionalDocument(MarkdownParser.parse(source), next)
        assertEquals("**尾部**", doc.raw(doc.elements.single { it.kind == "StrongEmphasis" }))
        assertTrue(doc.root.children.any { it.kind == "Raw" && it.text == "更长的段落 😀" })
    }
    @Test fun shrinkingPrefixKeepsUnspannedTaskNodesInsideValidSourceBounds() {
        val before = "**正文**\n\n- [ ] 任务\n  - 子项\n"
        val after = before.replace("**正文**", "正文")
        val doc = provisionalDocument(MarkdownParser.parse(before), after)
        doc.elements.forEach { assertTrue(it.range.start >= 0); assertTrue(it.range.end <= after.length); doc.raw(it) }
        projection(doc, null, null)
    }
    @Test fun inlineFormattingAcrossStructuresKeepsHeadingAndListPrefixes() {
        val source = "# 标题\n\n- 列表"
        assertEquals("# **标题**\n\n- **列表**", edit(source, TextSelection(0, source.length), MarkdownAction.Bold).text)
    }
    @Test fun removingBoldFromMiddleKeepsNeighboringContentBold() {
        assertEquals("**a**b**c**", edit("**abc**", TextSelection(3, 4), MarkdownAction.Bold).text)
        assertEquals("a**bc**", edit("**abc**", TextSelection(2, 3), MarkdownAction.Bold).text)
        assertEquals("**ab**c", edit("**abc**", TextSelection(4, 5), MarkdownAction.Bold).text)
    }
    @Test fun changingOnlyLinkAddressPreservesFormattingInsideLabel() {
        val source = "前 [**粗体**](https://old.example) 后"
        assertEquals("前 [**粗体**](<https://new.example>) 后", MarkdownEdits.link(source, TextSelection(6), "粗体", "https://new.example")?.text)
    }
    @Test fun displayAnchorsMapAcrossMultipleHiddenDelimiterPairsAndEmoji() {
        val source = "a **中文** 😀 `code` z"
        val p = projection(MarkdownParser.parse(source), null, null)
        assertEquals(2, p.offset(source.indexOf("中文")))
        assertEquals(5, p.offset(source.indexOf("😀")))
        assertEquals(8, p.offset(source.indexOf("code")))
        assertEquals(source.indexOf("😀"), p.sourceOffset(5))
        assertEquals(source.indexOf("code") + 2, p.sourceOffset(10))
        assertEquals(source.length, p.sourceOffset(14))
    }
    @Test fun allValidSelectionsAcrossMarkupBoundariesProduceValidEditsOrRefuse() {
        val source = "## 标题\n\n- [ ] 项\n\n**abc**\n\n```x\nx\n```"
        val doc = MarkdownParser.parse(source)
        for (start in 0..source.length) for (end in start..source.length) for (action in MarkdownAction.entries) {
            val result = MarkdownEdits.command(source, TextSelection(start, end), action, 3, doc) ?: continue
            assertTrue("$action $start..$end", result.selection.min >= 0 && result.selection.max <= result.text.length)
            TextRules.bytes(result.text)
        }
    }
    @Test fun offscreenBatchingKeepsIdenticalDisplayAndFineVisibleMappings() {
        val source = ("前 **粗体** 与 *斜体*\n\n").repeat(1000)
        val p = projection(MarkdownParser.parse(source), null, null)
        val window = SourceRange(4000, 5000)
        val batched = p.outputChanges(source, listOf(window))
        fun render(changes: List<io.github.xrisium.makudon.feature.workspace.DisplayChange>): String {
            val out = StringBuilder(source); changes.asReversed().forEach { out.replace(it.range.start, it.range.end, it.text) }; return out.toString()
        }
        assertEquals(render(p.changes), render(batched))
        assertTrue(batched.size < p.changes.size / 4)
        p.changes.filter { window.intersects(it.range.start, it.range.end) }.forEach { assertTrue(it in batched) }
        assertFalse(batched.any { it.range.start < window.start && it.range.end > window.end })
    }
    @Test fun replacingThousandsOfBlocksMakesOneBoundedProvisionalBlock() {
        val before = ("# 标题\n\n**段落**\n\n").repeat(2000)
        val replacement = "😀".repeat(100_000)
        val doc = provisionalDocument(MarkdownParser.parse(before), replacement)
        assertEquals(1, doc.root.children.size)
        assertEquals(SourceRange(0, replacement.length), doc.root.children.single().range)
        assertEquals(replacement, doc.root.children.single().text)
    }
}
