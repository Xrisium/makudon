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

class BatchWorkspaceStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun expect(reason: Failure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: WorkspaceException) { assertEquals(reason, e.reason) }
    }
    @Test fun batchCopiesDeduplicateParentsResolveMutualNamesAndPublishExactlyOnce() {
        var publications = 0
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) { if (file.name == "catalog.properties") publications++; super.write(file, bytes) } }
        val dir = temp.newFolder(); val store = WorkspaceStore(dir, disk).also { it.load() }
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Folder)
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Folder)
        val one = store.create("稿", a, DocumentKind.Plain, "第一篇 👩🏽‍💻")
        val two = store.create("稿", b, DocumentKind.Plain, "第二篇")
        val snapshot = store.captureAll(listOf(a, one, a, two))
        assertEquals(2, snapshot.size)
        val before = publications
        store.pasteAll(snapshot, Workspace.ROOT)
        assertEquals(before + 1, publications)
        val copies = store.refresh().entries.filter { it.parent == Workspace.ROOT && it.id !in setOf(a, b) }
        assertEquals(setOf("甲（副本）", "稿"), copies.map { it.name }.toSet())
        val flat = store.captureAll(listOf(one, two))
        store.pasteAll(flat, Workspace.ROOT)
        val docs = store.refresh().entries.filter { !it.folder && it.parent == Workspace.ROOT }
        assertEquals(setOf("稿", "稿（副本）", "稿（副本 2）"), docs.map { it.name }.toSet())
        assertEquals("第一篇 👩🏽‍💻", store.read(one))
        store.deleteAll(listOf(a, b))
        store.pasteAll(flat, Workspace.ROOT)
        assertEquals(5, WorkspaceStore(dir).load().entries.count { !it.folder && it.parent == Workspace.ROOT })
    }
    @Test fun secondBodyFailurePublishesNothingAndPreservesEverySource() {
        val dir = temp.newFolder(); var enabled = false; var writes = 0
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) {
            if (enabled && file.name.startsWith("body.") && ++writes == 2) throw IOException("injected body failure")
            super.write(file, bytes)
        } }
        val store = WorkspaceStore(dir, disk).also { it.load() }
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Plain, "源甲")
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Plain, "源乙")
        val snapshot = store.captureAll(listOf(a, b)); val baseline = File(dir, "catalog.properties").readBytes()
        enabled = true
        try { store.pasteAll(snapshot, Workspace.ROOT); fail() } catch (_: IOException) { /* expected */ }
        assertArrayEquals(baseline, File(dir, "catalog.properties").readBytes())
        val reopened = WorkspaceStore(dir).also { it.load() }
        assertEquals(2, reopened.refresh().entries.count { !it.folder })
        assertEquals("源甲", reopened.read(a)); assertEquals("源乙", reopened.read(b))
    }
    @Test fun moveConflictAndPublicationFailureNeverPartiallyMoveBatch() {
        val dir = temp.newFolder(); var fail = false
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) {
            if (fail && file.name == "catalog.properties") throw IOException("injected catalog failure")
            super.write(file, bytes)
        } }
        val store = WorkspaceStore(dir, disk).also { it.load() }
        val target = store.create("目标", Workspace.ROOT, DocumentKind.Folder)
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Plain, "甲")
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Plain, "乙")
        store.create("乙", target, DocumentKind.Plain, "不能覆盖")
        expect(Failure.Conflict) { store.moveAll(listOf(a, b), target) }
        assertEquals(Workspace.ROOT, store.refresh().entry(a)?.parent)
        store.rename(b, "丙"); fail = true
        try { store.moveAll(listOf(a, b), target); fail() } catch (_: IOException) { /* expected */ }
        expect(Failure.Corrupt) { store.refresh() }
        val next = WorkspaceStore(dir).load()
        assertEquals(Workspace.ROOT, next.entry(a)?.parent); assertEquals(Workspace.ROOT, next.entry(b)?.parent)
        fail = false; store.load(); store.moveAll(listOf(a, b), target)
        assertEquals(target, WorkspaceStore(dir).load().entry(a)?.parent)
        assertEquals("甲", store.read(a)); assertEquals("乙", store.read(b))
    }
    @Test fun moveRejectsMissingRootAndCycleBeforeAnyPublication() {
        val dir = temp.newFolder(); val store = WorkspaceStore(dir).also { it.load() }
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Folder)
        val b = store.create("乙", a, DocumentKind.Folder)
        val doc = store.create("稿", a, DocumentKind.Plain, "完整正文")
        val baseline = File(dir, "catalog.properties").readBytes()
        expect(Failure.Cycle) { store.moveAll(listOf(a, doc), b) }
        expect(Failure.Cycle) { store.moveAll(listOf(Workspace.ROOT), b) }
        expect(Failure.Missing) { store.deleteAll(listOf(doc, "missing")) }
        expect(Failure.NoChange) { store.moveAll(listOf(doc), a) }
        assertArrayEquals(baseline, File(dir, "catalog.properties").readBytes())
        assertEquals("完整正文", store.read(doc))
    }
    @Test fun deletePublishesOnceAndAttemptsAllCleanupEvenAfterFailure() {
        val dir = temp.newFolder(); val attempted = mutableListOf<String>(); var publications = 0
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) { if (file.name == "catalog.properties") publications++; super.write(file, bytes) } }
        val store = WorkspaceStore(dir, disk, cleanup = { attempted += it.name; if (attempted.size == 1) false else it.deleteRecursively() }).also { it.load() }
        val folder = store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        val a = store.create("甲", folder, DocumentKind.Plain, "甲")
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Plain, "乙")
        val before = publications
        expect(Failure.Cleanup) { store.deleteAll(listOf(folder, a, b)) }
        assertEquals(before + 1, publications); assertEquals(setOf(a, b), attempted.toSet())
        val next = WorkspaceStore(dir).load()
        assertEquals(listOf(Workspace.ROOT), next.entries.map { it.id })
        assertTrue(File(dir, "documents/$a").exists()); assertFalse(File(dir, "documents/$b").exists())
    }
    @Test fun failedDeletePublicationRetainsEveryBodyAndSkipsCleanup() {
        val dir = temp.newFolder(); var fail = false; var cleanups = 0
        val disk = object : AtomicDisk() { override fun write(file: File, bytes: ByteArray) { if (fail && file.name == "catalog.properties") throw IOException("injected"); super.write(file, bytes) } }
        val store = WorkspaceStore(dir, disk, cleanup = { cleanups++; it.deleteRecursively() }).also { it.load() }
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Plain, "甲")
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Plain, "乙")
        fail = true
        try { store.deleteAll(listOf(a, b)); fail() } catch (_: IOException) { /* expected */ }
        assertEquals(0, cleanups)
        val next = WorkspaceStore(dir).also { it.load() }
        assertEquals("甲", next.read(a)); assertEquals("乙", next.read(b))
    }
    @Test fun aggregateCaptureBudgetRejectsWholeCaptureWithoutChangingData() {
        val store = WorkspaceStore(temp.newFolder()).also { it.load() }
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Plain, "中文甲")
        val b = store.create("乙", Workspace.ROOT, DocumentKind.Plain, "中文乙")
        expect(Failure.ClipboardTooLarge) { store.captureAll(listOf(a, b), maxBytes = 12) }
        assertEquals("中文甲", store.read(a)); assertEquals("中文乙", store.read(b))
    }
}
