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

class WorkspaceStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store(dir: File = temp.newFolder(), disk: AtomicDisk = AtomicDisk()) = WorkspaceStore(dir, disk).also { it.load() }
    private fun expect(reason: Failure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: WorkspaceException) { assertEquals(reason, e.reason) }
    }
    @Test fun independentDocumentsSurviveRestartAndEmptySave() {
        val dir = temp.newFolder(); val store = store(dir)
        val a = store.create("记事", Workspace.INBOX, DocumentKind.Plain, "中文 👩🏽‍💻\n\n无末尾换行")
        val b = store.create("记事", Workspace.INBOX, DocumentKind.Plain, "独立内容")
        assertNotEquals(a, b)
        assertEquals("记事（副本）", store.refresh().entry(b)?.name)
        store.save(a, "")
        val reopened = store(dir)
        assertEquals("", reopened.read(a)); assertEquals("独立内容", reopened.read(b))
        assertEquals("中文 👩🏽‍💻\n\n无末尾换行", reopened.previous(a))
    }
    @Test fun renameAndMovePreserveSourceBodyAndStableIds() {
        val store = store()
        val folder = store.create("写作", Workspace.ROOT, DocumentKind.Folder)
        val child = store.create("随笔", folder, DocumentKind.Folder)
        val doc = store.create("名字", child, DocumentKind.Markdown, "# 标题\n\n原样  \n")
        store.rename(folder, "手记"); store.rename(doc, "新名字")
        store.move(child, Workspace.ROOT)
        assertEquals("# 标题\n\n原样  \n", store.read(doc))
        assertEquals(child, store.refresh().entry(doc)?.parent)
        assertEquals("新名字.md", store.refresh().entry(doc)?.filename)
        assertEquals("随笔", store.refresh().path(child))
    }
    @Test fun conflictsAndCyclesLeaveOriginalTreeUntouched() {
        val store = store()
        val a = store.create("甲", Workspace.ROOT, DocumentKind.Folder)
        val b = store.create("乙", a, DocumentKind.Folder)
        val doc = store.create("文稿", a, DocumentKind.Plain, "正文")
        store.create("文稿", b, DocumentKind.Plain, "另一篇")
        expect(Failure.Conflict) { store.move(doc, b) }
        expect(Failure.Cycle) { store.move(a, b) }
        expect(Failure.Cycle) { store.paste(store.capture(a), b) }
        expect(Failure.InvalidName) { store.rename(doc, "../逃逸") }
        assertEquals(a, store.refresh().entry(doc)?.parent)
        assertEquals("正文", store.read(doc))
    }
    @Test fun recursiveCopiesHaveIndependentBodiesAndCapturedSnapshot() {
        val store = store()
        val folder = store.create("原目录", Workspace.ROOT, DocumentKind.Folder)
        val child = store.create("子目录", folder, DocumentKind.Folder)
        val doc = store.create("文稿", child, DocumentKind.Markdown, "原始正文")
        val snapshot = store.capture(folder)
        store.save(doc, "复制之后的修改")
        store.paste(snapshot, Workspace.ROOT)
        val copiedFolder = store.refresh().entries.single { it.name == "原目录（副本）" }
        val copiedChild = store.refresh().entries.single { it.parent == copiedFolder.id }
        val copiedDoc = store.refresh().entries.single { it.parent == copiedChild.id }
        assertNotEquals(child, copiedChild.id); assertNotEquals(doc, copiedDoc.id)
        assertEquals("原始正文", store.read(copiedDoc.id))
        store.save(copiedDoc.id, "修改副本")
        assertEquals("复制之后的修改", store.read(doc))
        store.delete(folder)
        assertEquals("修改副本", store.read(copiedDoc.id))
    }
    @Test fun deletingSubtreeDoesNotResurrectAfterRestartOrTouchPeer() {
        val dir = temp.newFolder(); val store = store(dir)
        val folder = store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        val doc = store.create("稿", folder, DocumentKind.Plain, "待删除")
        val peer = store.create("稿", Workspace.INBOX, DocumentKind.Plain, "应保留")
        store.delete(folder)
        val next = store(dir)
        assertNull(next.refresh().entry(folder)); assertNull(next.refresh().entry(doc))
        assertEquals("应保留", next.read(peer))
        assertFalse(File(dir, "documents/$doc").exists())
    }
    @Test fun previousWriteFailureCannotChangePrimaryAndRetryUsesLatest() {
        var fail = false
        val disk = object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) {
                if (fail && file.name == "previous.txt") throw IOException("injected")
                super.write(file, bytes)
            }
        }
        val store = store(disk = disk)
        val id = store.create("测试", Workspace.INBOX, DocumentKind.Plain, "完整基线")
        fail = true
        try { store.save(id, "失败的新稿"); fail() } catch (_: IOException) { /* injected */ }
        assertEquals("完整基线", store.read(id))
        fail = false; store.save(id, "最终新稿")
        assertEquals("最终新稿", store.read(id)); assertEquals("完整基线", store.previous(id))
    }
    @Test fun partialPrimaryWritePreservesOldCompleteVersion() {
        val dir = temp.newFolder(); var fail = false
        val disk = object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) {
                if (fail && file.name.startsWith("body.")) { File(file.parentFile, file.name + ".new").writeBytes(bytes.take(3).toByteArray()); throw IOException("injected") }
                super.write(file, bytes)
            }
        }
        val store = store(dir, disk)
        val id = store.create("测试", Workspace.INBOX, DocumentKind.Plain, "旧的完整正文")
        fail = true
        try { store.save(id, "新的完整正文"); fail() } catch (_: IOException) { /* injected */ }
        assertEquals("旧的完整正文", store(dir).read(id))
    }
    @Test fun failedCatalogPublicationDoesNotPublishCopyOrDeleteSource() {
        val dir = temp.newFolder(); var fail = false
        val disk = object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) {
                if (fail && file.name == "catalog.properties") throw IOException("injected")
                super.write(file, bytes)
            }
        }
        val store = store(dir, disk)
        val id = store.create("文稿", Workspace.INBOX, DocumentKind.Plain, "原稿")
        val snapshot = store.capture(id); fail = true
        try { store.paste(snapshot, Workspace.ROOT); fail() } catch (_: IOException) { /* injected */ }
        expect(Failure.Corrupt) { store.read(id) }
        store.load()
        try { store.delete(id); fail() } catch (_: IOException) { /* injected */ }
        val next = store(dir)
        assertEquals(1, next.refresh().entries.count { !it.folder }); assertEquals("原稿", next.read(id))
    }
    @Test fun corruptBodyCannotBeReplacedWithEmptyAndPreviousCanBeRecovered() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("文稿", Workspace.INBOX, DocumentKind.Plain, "初始正文")
        store.save(id, "第二版本")
        File(dir, "documents/$id/body.txt").writeBytes(byteArrayOf(0xFF.toByte()))
        val reopened = store(dir)
        assertTrue(reopened.refresh().entry(id)?.unreadable == true)
        expect(Failure.InvalidText) { reopened.save(id, "") }
        assertEquals("初始正文", reopened.restore(id))
        assertEquals("初始正文", reopened.read(id))
        val material = File(dir, "recovery").listFiles().orEmpty().single()
        assertArrayEquals(byteArrayOf(0xFF.toByte()), File(material, "body.txt").readBytes())
    }
    @Test fun corruptCatalogStopsInitializationWithoutOverwritingEvidence() {
        val dir = temp.newFolder(); val store = store(dir)
        store.create("文稿", Workspace.INBOX, DocumentKind.Plain, "应保留")
        val file = File(dir, "catalog.properties")
        file.writeText("schema=99\n")
        expect(Failure.Corrupt) { store(dir) }
        assertEquals("schema=99\n", file.readText())
    }
    @Test fun boundedUnicodeAndStrictUtf8Import() {
        val text = "中😀".repeat(50_000)
        assertEquals(text, TextRules.decode(TextRules.bytes(text)))
        expect(Failure.TooLarge) { TextRules.bytes(text + "a") }
        expect(Failure.InvalidText) { TextRules.bytes("\uD83D") }
        expect(Failure.InvalidText) { TextRules.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        expect(Failure.InvalidText) { TextRules.decode(byteArrayOf(0, 1, 2)) }
        expect(Failure.InvalidText) { TextRules.decode("%PDF-1.7\n".toByteArray(), imported = true) }
        expect(Failure.TooLarge) { TextRules.decode(ByteArray(TextRules.MAX_BYTES + 1)) }
        assertEquals("中文\n\n  保留空格\n", TextRules.decode("\uFEFF中文\r\n\r\n  保留空格\r".toByteArray(), imported = true))
    }
    @Test fun markdownRangesPreserveUntouchedSyntaxAndSeparators() {
        val source = "# 标题\n\n\n普通段落  \n换行\n\n- [ ] 未支持的任务\n\n```kotlin\nval a = 1\n```\n\n"
        val blocks = markdownBlocks(source)
        assertEquals(1, blocks.first().heading)
        assertTrue(blocks.any { !it.editable && "未支持" in it.text })
        val first = blocks.first()
        val edited = source.replaceRange(first.start + first.prefix.length, first.end, "改后的标题")
        assertEquals(source.substring(source.indexOf("\n\n")), edited.substring(edited.indexOf("\n\n")))
    }
    @Test fun naturalSiblingSortDoesNotFlattenOrMutateSource() {
        val store = store()
        val folder = store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        store.create("稿10", folder, DocumentKind.Plain); store.create("稿2", folder, DocumentKind.Plain)
        store.create("稿1", Workspace.INBOX, DocumentKind.Plain)
        val w = store.refresh()
        assertEquals(listOf("稿2", "稿10"), w.sorted(w.entries.filter { it.parent == folder }).map { it.name })
        assertEquals(listOf("稿10", "稿2"), w.copy(descending = true).sorted(w.entries.filter { it.parent == folder }).map { it.name })
        assertEquals(1, w.entries.count { it.parent == Workspace.INBOX })
    }
    @Test fun replayedImportRequestSurvivesRestartButNewRequestCreatesIndependentCopy() {
        val dir = temp.newFolder(); val original = store(dir)
        val id = original.create("导入", Workspace.INBOX, DocumentKind.Markdown, "正文", "request-a")
        val next = store(dir)
        assertEquals(id, next.create("导入", Workspace.INBOX, DocumentKind.Markdown, "不能覆盖", "request-a"))
        val second = next.create("导入", Workspace.INBOX, DocumentKind.Markdown, "另一次", "request-b")
        assertNotEquals(id, second); assertEquals("正文", next.read(id)); assertEquals("另一次", next.read(second))
    }
    @Test fun mixedSiblingSortIsTransitiveWithFoldersFirstDisabled() {
        val entries = listOf(
            Entry("a", "乙", Workspace.ROOT, DocumentKind.Folder, 1),
            Entry("b", "甲", Workspace.ROOT, DocumentKind.Plain, 9, modified = 9, bytes = 50),
            Entry("c", "丙", Workspace.ROOT, DocumentKind.Markdown, 3, modified = 3, bytes = 10),
        )
        SortKey.entries.forEach { key ->
            val workspace = Workspace(entries, sort = key, foldersFirst = false)
            val expected = workspace.sorted(entries).map { it.id }
            val permutations = listOf(entries, entries.reversed(), listOf(entries[1], entries[2], entries[0]))
            permutations.forEach { assertEquals(expected, workspace.sorted(it).map { e -> e.id }) }
        }
    }
    @Test fun deletedLastExternalImportCannotBeResurrectedByActivityTaskReplay() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("导入", Workspace.INBOX, DocumentKind.Plain, "正文", "last-request")
        store.delete(id)
        val next = store(dir)
        expect(Failure.Missing) { next.create("导入", Workspace.INBOX, DocumentKind.Plain, "旧请求", "last-request") }
        val fresh = next.create("导入", Workspace.INBOX, DocumentKind.Plain, "主动再次打开", "fresh-request")
        assertEquals("主动再次打开", next.read(fresh))
    }
    @Test fun failedReloadInvalidatesStaleCatalogAndCannotAuthorizeFurtherWrites() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("原稿", Workspace.INBOX, DocumentKind.Plain, "应保留")
        File(dir, "catalog.properties").writeText("schema=99\n")
        expect(Failure.Corrupt) { store.load() }
        expect(Failure.Corrupt) { store.save(id, "不能写入") }
        assertEquals("应保留", File(dir, "documents/$id/body.txt").readText())
    }
}
