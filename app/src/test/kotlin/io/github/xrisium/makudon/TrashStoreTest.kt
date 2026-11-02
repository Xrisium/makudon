package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrashStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun suffix(n: Int) = if (n == 1) "（恢复）" else "（恢复 $n）"
    private fun library(dir: File, disk: AtomicDisk = AtomicDisk()) = LibraryController(dir, atomic = disk).also { it.load() }
    private fun bin(dir: File, lib: LibraryController, disk: AtomicDisk = AtomicDisk()) = TrashStore(dir, lib, disk).also { it.load() }
    private fun expect(reason: Failure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: WorkspaceException) { assertEquals(reason, e.reason) }
    }
    @Test fun deleteRestartPreviewRestorePreservesBodyHistoryTimesAndEmptyFolders() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val folder = lib.store.create("旅行笔记", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("空目录", folder, DocumentKind.Folder)
        val doc = lib.store.create("中文 😀", folder, DocumentKind.Markdown, "上一版")
        lib.store.save(doc, "# 新正文 👩🏽‍💻\n\n  保留空白\n")
        val before = lib.store.refresh().entry(doc) ?: error("missing")
        val deleted = trash.delete(listOf(folder, doc, folder))
        assertEquals(1, deleted.roots.size); assertEquals(3, deleted.nodes.size)
        assertEquals(1, lib.store.refresh().entries.size)
        assertFalse(File(dir, "workspace/documents/$doc").exists())
        val reopenedLib = library(dir); val reopened = bin(dir, reopenedLib)
        val stored = reopened.inventory().nodes.single { !it.entry.folder }
        val index = File(dir, "trash/index.properties").readBytes()
        assertEquals("# 新正文 👩🏽‍💻\n\n  保留空白\n", reopened.read(stored.id))
        assertArrayEquals(index, File(dir, "trash/index.properties").readBytes())
        reopened.restore(reopened.inventory().roots.map { it.id }, false, ::suffix)
        val after = reopenedLib.store.refresh().entry(doc) ?: error("missing")
        assertEquals(before.created, after.created); assertEquals(before.modified, after.modified); assertEquals(before.opened, after.opened)
        assertEquals("上一版", reopenedLib.store.previous(doc))
        assertTrue(reopened.inventory().nodes.isEmpty())
        assertTrue(reopenedLib.store.refresh().entries.any { it.name == "空目录" && it.parent == folder })
    }
    @Test fun nestedDocumentRestoreRequiresRootConsentAndLeavesRemainingTreeIntact() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val folder = lib.store.create("父目录", Workspace.ROOT, DocumentKind.Folder)
        val nested = lib.store.create("子目录", folder, DocumentKind.Folder)
        val one = lib.store.create("一", nested, DocumentKind.Plain, "一")
        lib.store.create("二", nested, DocumentKind.Plain, "二")
        trash.delete(listOf(folder))
        val selected = trash.inventory().nodes.single { it.entry.id == one }.id
        assertTrue(trash.needsRoot(listOf(selected)))
        expect(Failure.Missing) { trash.restore(listOf(selected), false, ::suffix) }
        trash.restore(listOf(selected), true, ::suffix)
        assertEquals(Workspace.ROOT, lib.store.refresh().entry(one)?.parent)
        assertEquals(3, trash.inventory().nodes.size)
        trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals("一", lib.store.read(one)); assertEquals(1, lib.store.refresh().entries.count { it.name == "一" })
        assertEquals(nested, lib.store.refresh().entries.single { it.name == "二" }.parent)
    }
    @Test fun validOriginalParentUsesStableIdAfterRenameAndMove() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val folder = lib.store.create("旧名称", Workspace.ROOT, DocumentKind.Folder)
        val target = lib.store.create("目标", Workspace.ROOT, DocumentKind.Folder)
        val doc = lib.store.create("稿", folder, DocumentKind.Plain, "正文")
        trash.delete(listOf(doc)); lib.store.rename(folder, "新名称"); lib.store.move(folder, target)
        assertFalse(trash.needsRoot(trash.inventory().roots.map { it.id }))
        val result = trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals(folder, result.entries.single().parent)
        assertEquals("目标 / 新名称", result.locations.single())
    }
    @Test fun earlierDeletedChildRemainsSeparateAndFindsRestoredParent() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val folder = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        val doc = lib.store.create("先删", folder, DocumentKind.Plain, "正文")
        trash.delete(listOf(doc)); trash.delete(listOf(folder))
        assertEquals(2, trash.inventory().roots.size)
        trash.restore(listOf(trash.inventory().roots.single { it.entry.folder }.id), false, ::suffix)
        assertFalse(trash.needsRoot(trash.inventory().roots.map { it.id }))
        trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals(folder, lib.store.refresh().entry(doc)?.parent)
    }
    @Test fun documentAndFolderNameConflictsNeverOverwriteOrMerge() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val folder = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("旧稿", folder, DocumentKind.Plain, "需要恢复")
        trash.delete(listOf(folder))
        val existing = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("新稿", existing, DocumentKind.Plain, "不能覆盖")
        trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals(2, lib.store.refresh().entries.count { it.folder && it.parent == Workspace.ROOT })
        assertEquals(1, lib.store.refresh().entries.count { it.parent == existing })
        val doc = lib.store.create("同名", Workspace.ROOT, DocumentKind.Plain, "旧文")
        trash.delete(listOf(doc))
        val live = lib.store.create("同名", Workspace.ROOT, DocumentKind.Plain, "新文")
        lib.store.create("同名（恢复）", Workspace.ROOT, DocumentKind.Plain, "已占名")
        val result = trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals("同名（恢复 2）", result.entries.single().name)
        assertEquals("新文", lib.store.read(live)); assertEquals("旧文", lib.store.read(result.entries.single().id))
    }
    @Test fun batchRestorePrevalidatesNamesAndDeduplicatesParentChildSelection() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val a = lib.store.create("甲", Workspace.ROOT, DocumentKind.Folder)
        val b = lib.store.create("乙", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("同名", a, DocumentKind.Plain, "甲")
        lib.store.create("同名", b, DocumentKind.Plain, "乙")
        trash.delete(listOf(a, b))
        val docs = trash.inventory().nodes.filter { !it.entry.folder }.map { it.id }
        trash.restore(docs, true, ::suffix)
        assertEquals(setOf("同名", "同名（恢复）"), lib.store.refresh().entries.filter { !it.folder }.map { it.name }.toSet())
        trash.restore(trash.inventory().nodes.map { it.id }, false, ::suffix)
        assertTrue(trash.inventory().nodes.isEmpty())
        assertEquals(5, lib.store.refresh().entries.size)
    }
    @Test fun purgeIsRecursiveAndFailedCleanupIsVisibleDurableAndRetryable() {
        val dir = temp.newFolder(); val lib = library(dir); var failing = true
        val trash = TrashStore(dir, lib, cleanup = { if (failing) false else it.deleteRecursively() }).also { it.load() }
        val folder = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("稿", folder, DocumentKind.Plain, "正文")
        trash.delete(listOf(folder))
        val before = trash.inventory().bytes
        val purged = trash.purge(trash.inventory().roots.map { it.id })
        assertTrue(purged.nodes.isEmpty()); assertTrue(purged.cleanupPending); assertTrue(purged.bytes > 0)
        assertTrue(bin(dir, library(dir)).inventory().cleanupPending)
        failing = false
        val cleaned = trash.retryCleanup()
        assertFalse(cleaned.cleanupPending); assertTrue(cleaned.bytes < before)
        assertTrue(File(dir, "trash/materials").listFiles().orEmpty().isEmpty())
    }
    @Test fun unreadableBodyBlocksWholeDeletionWithoutHidingAnySource() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val a = lib.store.create("甲", Workspace.ROOT, DocumentKind.Plain, "甲")
        val b = lib.store.create("乙", Workspace.ROOT, DocumentKind.Plain, "乙")
        File(dir, "workspace/documents/$b/body.txt").writeBytes(byteArrayOf(0xFF.toByte()))
        expect(Failure.InvalidText) { trash.delete(listOf(a, b)) }
        assertNotNull(lib.store.refresh().entry(a)); assertNotNull(lib.store.refresh().entry(b))
        val next = bin(dir, library(dir)); assertTrue(next.inventory().nodes.isEmpty())
        assertFalse(next.retryCleanup().cleanupPending)
    }
    @Test fun deletionInterruptionAtEveryPublicationConvergesToExactlyOneVisibleOwner() {
        listOf("index:1", "index:2", "catalog:1", "index:3", "index:4").forEach { stage ->
            listOf(false, true).forEach { after ->
                val dir = temp.newFolder(); val fault = FaultDisk(); val lib = library(dir, fault); val trash = bin(dir, lib, fault)
                val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Plain, "完整正文 😀")
                fault.arm(stage, after)
                try { trash.delete(listOf(doc)); fail("Missed $stage $after") } catch (_: IOException) { /* simulated interrupted commit */ }
                val reopenedLib = library(dir); val reopened = bin(dir, reopenedLib)
                val live = reopenedLib.store.refresh().entries.filter { !it.folder }
                val deleted = reopened.inventory().nodes.filter { !it.entry.folder }
                assertEquals("$stage $after", 1, live.size + deleted.size)
                assertEquals("完整正文 😀", if (live.isNotEmpty()) reopenedLib.store.read(live.single().id) else reopened.read(deleted.single().id))
                reopened.retryCleanup()
            }
        }
    }
    @Test fun restorationInterruptionAtEveryPublicationNeverLosesOrDuplicatesDocument() {
        listOf("index:1", "catalog:1", "index:2", "index:3").forEach { stage ->
            listOf(false, true).forEach { after ->
                val dir = temp.newFolder(); val fault = FaultDisk(); val lib = library(dir, fault); val trash = bin(dir, lib, fault)
                val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Plain, "完整正文")
                trash.delete(listOf(doc)); val id = trash.inventory().roots.single().id
                fault.arm(stage, after)
                try { trash.restore(listOf(id), false, ::suffix); fail("Missed $stage $after") } catch (_: IOException) { /* simulated interrupted commit */ }
                val reopenedLib = library(dir); val reopened = bin(dir, reopenedLib)
                val live = reopenedLib.store.refresh().entries.filter { !it.folder }
                val deleted = reopened.inventory().nodes.filter { !it.entry.folder }
                assertEquals("$stage $after", 1, live.size + deleted.size)
                assertEquals("完整正文", if (live.isNotEmpty()) reopenedLib.store.read(live.single().id) else reopened.read(deleted.single().id))
                reopened.retryCleanup()
                if (deleted.isNotEmpty()) reopened.restore(deleted.map { it.id }, false, ::suffix)
                assertEquals(1, reopenedLib.store.refresh().entries.count { !it.folder })
            }
        }
    }
    @Test fun failedSecondRestoredBodyLeavesEntireBatchRecoverable() {
        val dir = temp.newFolder(); var writes = 0; var failing = false
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) {
            if (failing && file.name.startsWith("body.") && ++writes == 2) throw IOException("second body")
            super.write(file, bytes)
        } }
        val lib = library(dir, disk); val trash = bin(dir, lib, disk)
        val a = lib.store.create("甲", Workspace.ROOT, DocumentKind.Plain, "甲")
        val b = lib.store.create("乙", Workspace.ROOT, DocumentKind.Plain, "乙")
        trash.delete(listOf(a, b)); failing = true
        try { trash.restore(trash.inventory().roots.map { it.id }, false, ::suffix); fail() } catch (_: IOException) { /* expected */ }
        val nextLib = library(dir); val next = bin(dir, nextLib)
        assertEquals(1, nextLib.store.refresh().entries.size); assertEquals(2, next.inventory().roots.size)
        next.retryCleanup(); next.restore(next.inventory().roots.map { it.id }, false, ::suffix)
        assertEquals(setOf("甲", "乙"), nextLib.store.refresh().entries.filter { !it.folder }.map { nextLib.store.read(it.id) }.toSet())
    }
    @Test fun corruptTrashIndexNeverInitializesEmptyOrChangesWorkspace() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Plain, "正文")
        trash.delete(listOf(doc))
        File(dir, "trash/index.properties").writeText("schema=bad\ncount=0\n")
        expect(Failure.Corrupt) { trash.load() }
        assertFalse(trash.ready); assertEquals(1, lib.store.refresh().entries.size)
        assertTrue(File(dir, "trash/materials").walkTopDown().any { it.name == "body" })
    }
    @Test fun zipExportReplaceUndoAndSnapshotCleanupNeverSwitchOrIncludeTrash() {
        val dir = temp.newFolder(); val lib = library(dir); val trash = bin(dir, lib)
        val deleted = lib.store.create("删除稿", Workspace.ROOT, DocumentKind.Plain, "回收站内容")
        lib.store.create("正常稿", Workspace.ROOT, DocumentKind.Plain, "正常内容")
        trash.delete(listOf(deleted))
        val original = File(dir, "trash/index.properties").readBytes()
        val output = temp.newFile()
        LibraryArchive.write(output, LibraryArchive.exportPlan(lib.store.refresh()), lib.store::read)
        java.util.zip.ZipFile(output).use { assertEquals(listOf("正常稿.txt"), it.entries().asSequence().map { e -> e.name }.toList()) }
        val zip = temp.newFile()
        ZipOutputStream(zip.outputStream()).use { out -> out.putNextEntry(ZipEntry("备份稿.md")); out.write("备份内容".toByteArray()); out.closeEntry() }
        val archive = LibraryArchive.read(zip, temp.newFolder())
        val plan = RestorePlanner.plan(lib.store.refresh(), archive.inventory, RestoreMode.Replace, folderName = "恢复", now = 1_800_000_000_000) { _, _ -> false }
        lib.commit(lib.prepare(plan, archive, lib.fingerprint()))
        lib.undo(); lib.undo(); lib.clearSnapshot()
        assertArrayEquals(original, File(dir, "trash/index.properties").readBytes())
        val reopened = bin(dir, library(dir)); assertEquals("回收站内容", reopened.read(reopened.inventory().roots.single().id))
        reopened.restore(reopened.inventory().roots.map { it.id }, false, ::suffix)
    }
    @Test fun failedSourceCleanupCountsRealBytesAndNeverOverwritesResidualOnRestore() {
        val dir = temp.newFolder(); var failCleanup = true
        val lib = LibraryController(dir, cleanup = { if (failCleanup) false else it.deleteRecursively() }).also { it.load() }
        val trash = bin(dir, lib)
        val id = lib.store.create("稿", Workspace.ROOT, DocumentKind.Plain, "原文稿")
        val deleted = trash.delete(listOf(id))
        assertTrue(deleted.cleanupPending)
        val binBytes = File(dir, "trash").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(binBytes + File(dir, "workspace/documents/$id").walkTopDown().filter { it.isFile }.sumOf { it.length() }, deleted.bytes)
        val restored = trash.restore(deleted.roots.map { it.id }, false, ::suffix).entries.single()
        assertNotEquals(id, restored.id); assertEquals("原文稿", lib.store.read(restored.id))
        failCleanup = false
        assertFalse(trash.retryCleanup().cleanupPending)
        assertFalse(File(dir, "workspace/documents/$id").exists())
        assertEquals("原文稿", lib.store.read(restored.id))
    }
    @Test fun purgeInterruptionNeverResurrectsRemovedNodesAndAlwaysTracksCleanup() {
        listOf("index:1", "index:2").forEach { stage -> listOf(false, true).forEach { after ->
            val dir = temp.newFolder(); val fault = FaultDisk(); val lib = library(dir, fault); val trash = bin(dir, lib, fault)
            val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Plain, "正文")
            trash.delete(listOf(doc)); fault.arm(stage, after)
            try { trash.purge(trash.inventory().roots.map { it.id }); fail() } catch (_: IOException) { /* expected */ }
            val reopened = bin(dir, library(dir))
            if (stage == "index:1" && !after) assertEquals("正文", reopened.read(reopened.inventory().roots.single().id))
            else assertTrue(reopened.inventory().nodes.isEmpty())
            assertFalse(reopened.retryCleanup().cleanupPending)
        } }
    }
    private class FaultDisk : AtomicDisk() {
        private var target = ""; private var after = false; private val counts = mutableMapOf<String, Int>()
        fun arm(stage: String, after: Boolean) { target = stage; this.after = after; counts.clear() }
        override fun write(file: File, bytes: ByteArray) {
            val key = when (file.name) { "index.properties" -> "index"; "catalog.properties" -> "catalog"; else -> "other" }
            val n = (counts[key] ?: 0) + 1; counts[key] = n
            val hit = "$key:$n" == target
            if (hit && !after) throw IOException("before publication")
            super.write(file, bytes)
            if (hit && after) throw IOException("after publication")
        }
    }
}
