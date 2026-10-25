package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Test

class DirectorySelectionTest {
    private val w = Workspace(listOf(
        Entry(Workspace.ROOT, "库", null, DocumentKind.Folder, 1),
        Entry("a", "旅行", Workspace.ROOT, DocumentKind.Folder, 1),
        Entry("b", "京都", "a", DocumentKind.Folder, 1),
        Entry("c", "小说", Workspace.ROOT, DocumentKind.Folder, 1),
        Entry("one", "稿", "a", DocumentKind.Plain, 1),
        Entry("two", "夜", "b", DocumentKind.Plain, 1),
        Entry("three", "稿", "c", DocumentKind.Plain, 1),
        Entry("four", "章", "c", DocumentKind.Markdown, 1),
    ))

    @Test fun ancestorSelectionDeduplicatesChildrenAndCannotExcludeInheritedItems() {
        var ids = w.toggleSelection(emptySet(), "two")
        assertEquals(ItemSelection.Partial, w.selectionOf(ids, "a"))
        ids = w.toggleSelection(ids, "a")
        assertEquals(setOf("a"), ids)
        assertEquals(ItemSelection.Inherited, w.selectionOf(ids, "two"))
        assertEquals(ids, w.toggleSelection(ids, "two"))
        assertEquals(emptySet<String>(), w.toggleSelection(ids, "a"))
        assertEquals(listOf("a", "three"), w.selectionRoots(listOf("a", "b", "two", "three", "a", Workspace.ROOT, "missing")).map { it.id })
    }
    @Test fun rootAndMissingIdsCannotEnterSelection() {
        assertEquals(setOf("one"), w.toggleSelection(setOf("one"), Workspace.ROOT))
        assertEquals(setOf("one"), w.toggleSelection(setOf("one"), "missing"))
        assertEquals(ItemSelection.None, w.selectionOf(setOf("a"), Workspace.ROOT))
    }
    @Test fun sameFolderTargetsResolveOnceWhileNestedAndDispersedTargetsRequireChoice() {
        assertEquals(PasteTargets(listOf("c"), null), w.pasteTargets(listOf("three", "four"), Workspace.ROOT))
        val nested = w.pasteTargets(listOf("one", "two"), Workspace.ROOT)
        assertEquals(listOf("a", "b"), nested.related); assertNull(nested.suggested)
        val separate = w.pasteTargets(listOf("one", "two", "three"), Workspace.ROOT)
        assertEquals(listOf("a", "b", "c"), separate.related)
        assertEquals(Workspace.ROOT, separate.common); assertNull(separate.suggested)
        assertEquals("b", w.pasteTargets(emptyList(), "b").suggested)
        assertEquals(Workspace.ROOT, w.pasteTargets(emptyList(), "missing").suggested)
        assertEquals(Workspace.ROOT, w.pasteTargets(listOf(Workspace.ROOT), "b").suggested)
    }
    @Test fun targetValidationRejectsCyclesNoOpsAndWholeBatchNameConflicts() {
        assertEquals(Failure.Cycle, w.pasteFailure(listOf("a"), true, "b"))
        assertEquals(Failure.Cycle, w.pasteFailure(listOf("a"), false, "b"))
        assertEquals(Failure.NoChange, w.pasteFailure(listOf("one"), true, "a"))
        assertEquals(Failure.Conflict, w.pasteFailure(listOf("one", "three"), true, Workspace.ROOT))
        assertEquals(Failure.Conflict, w.pasteFailure(listOf("one"), true, "c"))
        assertEquals(Failure.Missing, w.pasteFailure(listOf("missing"), true, "a"))
        assertNull(w.pasteFailure(listOf("missing"), false, "a")) // Independent copied snapshots outlive their sources.
        assertNull(w.pasteFailure(listOf("two", "one"), true, "a")) // One source already here, another moves.
    }
}
