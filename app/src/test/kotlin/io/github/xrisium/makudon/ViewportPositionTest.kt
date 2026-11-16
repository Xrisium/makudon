package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.AtomicDisk
import io.github.xrisium.makudon.data.WorkspaceStore
import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ViewportPositionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store(dir: File, disk: AtomicDisk = AtomicDisk()) = WorkspaceStore(dir, disk).also { it.load() }

    @Test fun positionsSurviveReopenWithoutChangingBodyOrCatalog() {
        val dir = temp.newFolder()
        val store = store(dir)
        val id = store.create("长文", Workspace.ROOT, DocumentKind.Plain, "正文\n第二行😀")
        val before = File(dir, "catalog.properties").readBytes()
        val entry = store.refresh().entry(id)
        val position = ViewportPosition(4, 12, 82.5f, EditorMode.Reading)
        assertTrue(store.writePosition(id, position))
        val reopened = store(dir)
        assertEquals(position, reopened.readPosition(id))
        assertEquals("正文\n第二行😀", reopened.read(id))
        assertEquals(entry, reopened.refresh().entry(id))
        assertArrayEquals(before, File(dir, "catalog.properties").readBytes())
        assertNull(reopened.readPosition(Workspace.ROOT))
        assertFalse(reopened.writePosition("../outside", position))
    }

    @Test fun documentsAndLibraryGenerationsDoNotSharePositions() {
        val first = store(temp.newFolder())
        val a = first.create("甲", Workspace.ROOT, DocumentKind.Plain, "甲")
        val b = first.create("乙", Workspace.ROOT, DocumentKind.Markdown, "# 乙")
        assertTrue(first.writePosition(a, ViewportPosition(8, 16, 70f, EditorMode.Source)))
        assertNull(first.readPosition(b))
        val second = store(temp.newFolder())
        assertNull(second.readPosition(a))
        first.rename(a, "改名")
        assertEquals(8, first.readPosition(a)?.anchor)
    }

    @Test fun malformedAndOversizedMetadataCannotBlockOpeningDocuments() {
        val dir = temp.newFolder()
        val store = store(dir)
        val id = store.create("稿", Workspace.ROOT, DocumentKind.Markdown, "# 完整正文")
        assertNull(store.readPosition(id))
        val file = File(dir, "positions/$id.properties").also { checkNotNull(it.parentFile).mkdirs() }
        listOf("-1,0,33,Reading", "1,-1,33,Reading", "1,0,NaN,Reading", "1,0,0,Source", "1,0,33,unknown", "x".repeat(300)).forEach {
            file.writeText(it)
            assertNull(store.readPosition(id))
            assertEquals("# 完整正文", store.read(id))
        }
    }

    @Test fun failedPositionWriteKeepsPreviousPositionAndDoesNotFailBodySave() {
        val dir = temp.newFolder()
        var failPosition = false
        val disk = object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) {
                if (failPosition && file.parentFile?.name == "positions") throw IOException("Synthetic position failure")
                super.write(file, bytes)
            }
        }
        val store = store(dir, disk)
        val id = store.create("稿", Workspace.ROOT, DocumentKind.Plain, "初稿")
        val old = ViewportPosition(1, 2, 33f, EditorMode.Source)
        assertTrue(store.writePosition(id, old))
        failPosition = true
        assertFalse(store.writePosition(id, old.copy(anchor = 9)))
        store.save(id, "新正文")
        assertEquals(old, store(dir).readPosition(id))
        assertEquals("新正文", store(dir).read(id))
    }

    @Test fun globalSpacingAndSideSurviveOtherSettingsAndLegacyIndexes() {
        val dir = temp.newFolder()
        val store = store(dir)
        val id = store.create("稿", Workspace.ROOT, DocumentKind.Markdown, "# 原文")
        store.settings(ColorTheme.System, SortKey.Name, false, true, editorLineSpacing = EditorLineSpacing.Narrow, navigationSide = NavigationSide.Left)
        val reopened = store(dir)
        reopened.settings(ColorTheme.Ink, SortKey.Size, true, false)
        assertEquals(EditorLineSpacing.Narrow, store(dir).refresh().editorLineSpacing)
        assertEquals(NavigationSide.Left, store(dir).refresh().navigationSide)
        assertEquals("# 原文", store(dir).read(id))
        val catalog = File(dir, "catalog.properties")
        catalog.writeText(catalog.readText().lineSequence().filterNot { it.startsWith("editorLineSpacing=") || it.startsWith("navigationSide=") }.joinToString("\n"))
        assertEquals(EditorLineSpacing.Medium, store(dir).refresh().editorLineSpacing)
        assertEquals(NavigationSide.Right, store(dir).refresh().navigationSide)
    }
    @Test fun navigationInsetSurvivesReopenAndOtherPreferencesWithLegacyFallback() {
        val dir = temp.newFolder()
        val original = store(dir)
        val id = original.create("曲面屏", Workspace.ROOT, DocumentKind.Plain, "完整正文😀")
        original.settings(ColorTheme.System, SortKey.Name, false, true, navigationInset = NavigationInset.Wide)
        val reopened = store(dir)
        assertEquals(NavigationInset.Wide, reopened.refresh().navigationInset)
        reopened.settings(ColorTheme.Ink, SortKey.Size, true, false, navigationSide = NavigationSide.Left)
        assertEquals(NavigationInset.Wide, store(dir).refresh().navigationInset)
        assertEquals("完整正文😀", store(dir).read(id))
        val catalog = File(dir, "catalog.properties")
        val encoded = catalog.readText()
        catalog.writeText(encoded.lineSequence().filterNot { it.startsWith("navigationInset=") }.joinToString("\n"))
        assertEquals(NavigationInset.Standard, store(dir).refresh().navigationInset)
        catalog.appendText("\nnavigationInset=unknown\n")
        assertEquals(NavigationInset.Standard, store(dir).refresh().navigationInset)
    }

}
