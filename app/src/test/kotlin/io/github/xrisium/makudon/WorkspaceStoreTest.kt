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
    @Test fun fontPreferencesRemainIndependentAcrossRestartAndPreserveDocuments() {
        val dir = temp.newFolder()
        val original = store(dir)
        val id = original.create("稿", Workspace.ROOT, DocumentKind.Plain, "正文")
        val uiFont = FontPreference(FontChoice.WenKai, FontThickness.Light)
        val editorFont = FontPreference(FontChoice.Serif, FontThickness.Bold)
        original.settings(ColorTheme.System, SortKey.Name, false, true, uiFont, editorFont)
        val reopened = store(dir)
        assertEquals(uiFont, reopened.refresh().uiFont)
        assertEquals(editorFont, reopened.refresh().editorFont)
        assertEquals("正文", reopened.read(id))
        reopened.settings(ColorTheme.Paper, SortKey.Name, false, true)
        assertEquals(uiFont, store(dir).refresh().uiFont)
        assertEquals(editorFont, store(dir).refresh().editorFont)
    }
    @Test fun freshWorkspaceCreatesDocumentsAtRootWithoutAddingAnInboxAfterRestart() {
        val dir = temp.newFolder()
        val store = store(dir)
        val id = store.create("新稿", Workspace.ROOT, DocumentKind.Markdown, "# 根目录正文")
        val next = store(dir)
        assertEquals(listOf(Workspace.ROOT), next.refresh().entries.filter { it.folder }.map { it.id })
        assertEquals(Workspace.ROOT, next.refresh().entry(id)?.parent)
        assertEquals("# 根目录正文", next.read(id))
    }
    @Test fun editorTextSizeSurvivesRestartAndOtherSettingsWithoutChangingBody() {
        val dir = temp.newFolder()
        val original = store(dir)
        val id = original.create("字号", Workspace.ROOT, DocumentKind.Markdown, "# 标题\n\n原始正文")
        original.settings(ColorTheme.System, SortKey.Name, false, true, editorTextSize = EditorTextSize.Large)
        val reopened = store(dir)
        assertEquals(EditorTextSize.Large, reopened.refresh().editorTextSize)
        reopened.settings(ColorTheme.Ink, SortKey.Size, true, false)
        val final = store(dir)
        assertEquals(EditorTextSize.Large, final.refresh().editorTextSize)
        assertEquals("# 标题\n\n原始正文", final.read(id))
        val catalog = File(dir, "catalog.properties")
        catalog.writeText(catalog.readText().lineSequence().filterNot { it.startsWith("editorTextSize=") }.joinToString("\n"))
        assertEquals(EditorTextSize.Medium, store(dir).refresh().editorTextSize)
    }
    @Test fun legacyInboxKeepsItsBodyAndBehavesAsAnOrdinaryFolderWithoutRecreation() {
        val dir = temp.newFolder()
        val catalog = File(dir, "catalog.properties")
        checkNotNull(javaClass.getResourceAsStream("/workspace-with-inbox.properties")).use { input ->
            catalog.outputStream().use { input.copyTo(it) }
        }
        val id = "12345678-1234-1234-1234-123456789abc"
        val body = File(dir, "documents/$id/body.md")
        checkNotNull(body.parentFile).mkdirs()
        body.writeText("# 旧稿\n\n中文 👩🏽‍💻  \n")
        val originalCatalog = catalog.readBytes()
        val originalBody = body.readBytes()
        val store = store(dir)
        assertArrayEquals(originalCatalog, catalog.readBytes())
        assertEquals(FontPreference(), store.refresh().uiFont)
        assertEquals(FontPreference(), store.refresh().editorFont)
        assertEquals(Workspace.LEGACY_INBOX, store.refresh().entry(id)?.parent)
        val destination = store.create("写作", Workspace.ROOT, DocumentKind.Folder)
        store.rename(Workspace.LEGACY_INBOX, "旧资料")
        store.move(Workspace.LEGACY_INBOX, destination)
        val next = store(dir)
        assertEquals(destination, next.refresh().entry(Workspace.LEGACY_INBOX)?.parent)
        assertEquals("旧资料", next.refresh().entry(Workspace.LEGACY_INBOX)?.name)
        assertEquals(Workspace.LEGACY_INBOX, next.refresh().entry(id)?.parent)
        assertEquals("# 旧稿\n\n中文 👩🏽‍💻  \n", next.read(id))
        assertArrayEquals(originalBody, body.readBytes())
        expect(Failure.Cycle) { next.move(Workspace.LEGACY_INBOX, Workspace.LEGACY_INBOX) }
        next.delete(Workspace.LEGACY_INBOX)
        val reopened = store(dir).refresh()
        assertNull(reopened.entry(Workspace.LEGACY_INBOX))
        assertNull(reopened.entry(id))
        assertNotNull(reopened.entry(destination))
    }
    @Test fun independentDocumentsSurviveRestartAndEmptySave() {
        val dir = temp.newFolder(); val store = store(dir)
        val a = store.create("记事", Workspace.ROOT, DocumentKind.Plain, "中文 👩🏽‍💻\n\n无末尾换行")
        val b = store.create("记事", Workspace.ROOT, DocumentKind.Plain, "独立内容")
        assertNotEquals(a, b)
        assertEquals("记事 (1)", store.refresh().entry(b)?.name)
        store.save(a, "")
        val reopened = store(dir)
        assertEquals("", reopened.read(a)); assertEquals("独立内容", reopened.read(b))
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
        val copiedFolder = store.refresh().entries.single { it.name == "原目录 (1)" }
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
        val peer = store.create("稿", Workspace.ROOT, DocumentKind.Plain, "应保留")
        store.delete(folder)
        val next = store(dir)
        assertNull(next.refresh().entry(folder)); assertNull(next.refresh().entry(doc))
        assertEquals("应保留", next.read(peer))
        assertFalse(File(dir, "documents/$doc").exists())
    }
    @Test fun folderDetailsIncludeNestedDiskMaterialAndLatestDescendantTime() {
        val dir = temp.newFolder(); val store = store(dir)
        val folder = store.create("父目录", Workspace.ROOT, DocumentKind.Folder)
        val nested = store.create("子目录", folder, DocumentKind.Folder)
        val empty = store.create("空目录", nested, DocumentKind.Folder)
        val first = store.create("第一篇", folder, DocumentKind.Plain, "中😀")
        val second = store.create("第二篇", nested, DocumentKind.Markdown, "# 正文")
        val peer = store.create("外部", Workspace.ROOT, DocumentKind.Plain, "不计入")
        val pending = File(dir, "documents/$second/body.md.new").apply { writeBytes(ByteArray(37)) }
        val legacy = File(dir, "documents/$first/previous.txt").apply { writeText("旧材料") }
        File(dir, "recovery/legacy").apply { mkdirs(); resolve("body.txt").writeText("不计入") }
        val latest = System.currentTimeMillis() + 60_000
        assertTrue(File(dir, "documents/$second/body.md").setLastModified(latest))
        val refreshed = store.refresh()
        val bodySize = TextRules.bytes("中😀").size.toLong() + TextRules.bytes("# 正文").size
        assertEquals(bodySize + pending.length() + legacy.length(), store.diskBytes(folder))
        assertEquals(0L, store.diskBytes(empty))
        assertEquals(latest, refreshed.entry(folder)?.modified)
        assertEquals(latest, refreshed.entry(nested)?.modified)
        assertEquals(refreshed.entry(empty)?.created, refreshed.entry(empty)?.modified)
        val created = refreshed.entry(first)?.created
        store.save(first, "更长的正文😀")
        val reopened = store(dir)
        assertEquals(created, reopened.refresh().entry(first)?.created)
        assertEquals(store.diskBytes(folder), reopened.diskBytes(folder))
        reopened.move(second, Workspace.ROOT)
        assertEquals(TextRules.bytes("更长的正文😀").size + legacy.length(), reopened.diskBytes(folder))
        assertEquals("不计入", reopened.read(peer))
    }
    @Test fun diskDetailsFailForMissingMaterialInsteadOfReportingZero() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("测试", Workspace.ROOT, DocumentKind.Plain, "正文")
        File(dir, "documents/$id/body.txt").delete()
        File(dir, "documents/$id").delete()
        try { store.diskBytes(id); fail("missing material must fail") } catch (_: IOException) { /* reported to UI */ }
    }
    @Test fun savesDoNotDependOnOrGenerateDocumentHistory() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("测试", Workspace.ROOT, DocumentKind.Plain, "初始正文")
        val material = File(dir, "documents/$id")
        store.save(id, "第二版本")
        assertEquals(setOf("body.txt"), material.list()?.toSet())
        // A legacy history path cannot block a new save, and is never read or replaced.
        val legacy = File(material, "previous.txt").apply { mkdir() }
        store.save(id, "最终正文")
        assertTrue(legacy.isDirectory)
        assertEquals("最终正文", store(dir).read(id))
        assertFalse(File(dir, "recovery").exists())
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
        val id = store.create("测试", Workspace.ROOT, DocumentKind.Plain, "旧的完整正文")
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
        val id = store.create("文稿", Workspace.ROOT, DocumentKind.Plain, "原稿")
        val snapshot = store.capture(id); fail = true
        try { store.paste(snapshot, Workspace.ROOT); fail() } catch (_: IOException) { /* injected */ }
        expect(Failure.Corrupt) { store.read(id) }
        store.load()
        try { store.delete(id); fail() } catch (_: IOException) { /* injected */ }
        val next = store(dir)
        assertEquals(1, next.refresh().entries.count { !it.folder }); assertEquals("原稿", next.read(id))
    }
    @Test fun corruptBodyCannotBeReplacedWithEmpty() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("文稿", Workspace.ROOT, DocumentKind.Plain, "初始正文")
        store.save(id, "第二版本")
        File(dir, "documents/$id/body.txt").writeBytes(byteArrayOf(0xFF.toByte()))
        val reopened = store(dir)
        assertTrue(reopened.refresh().entry(id)?.unreadable == true)
        expect(Failure.InvalidText) { reopened.save(id, "") }
        assertArrayEquals(byteArrayOf(0xFF.toByte()), File(dir, "documents/$id/body.txt").readBytes())
    }
    @Test fun corruptCatalogStopsInitializationWithoutOverwritingEvidence() {
        val dir = temp.newFolder(); val store = store(dir)
        store.create("文稿", Workspace.ROOT, DocumentKind.Plain, "应保留")
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
        val parsed = MarkdownParser.parse(source)
        assertEquals(1, parsed.root.children.first().level)
        assertTrue(parsed.elements.any { it.kind == "TaskListItemMarker" })
        val edited = MarkdownEdits.command(source, TextSelection(2, 4), MarkdownAction.Bold, document = parsed)!!.text
        assertEquals(source.substring(source.indexOf("\n\n")), edited.substring(edited.indexOf("\n\n")))
    }
    @Test fun naturalSiblingSortDoesNotFlattenOrMutateSource() {
        val store = store()
        val folder = store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        store.create("稿10", folder, DocumentKind.Plain); store.create("稿2", folder, DocumentKind.Plain)
        store.create("稿1", Workspace.ROOT, DocumentKind.Plain)
        val w = store.refresh()
        assertEquals(listOf("稿2", "稿10"), w.sorted(w.entries.filter { it.parent == folder }).map { it.name })
        assertEquals(listOf("稿10", "稿2"), w.copy(descending = true).sorted(w.entries.filter { it.parent == folder }).map { it.name })
        assertEquals(1, w.entries.count { !it.folder && it.parent == Workspace.ROOT })
    }
    @Test fun replayedImportRequestSurvivesRestartButNewRequestCreatesIndependentCopy() {
        val dir = temp.newFolder(); val original = store(dir)
        val id = original.create("导入", Workspace.ROOT, DocumentKind.Markdown, "正文", "request-a")
        val next = store(dir)
        assertEquals(id, next.create("导入", Workspace.ROOT, DocumentKind.Markdown, "不能覆盖", "request-a"))
        val second = next.create("导入", Workspace.ROOT, DocumentKind.Markdown, "另一次", "request-b")
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
        val id = store.create("导入", Workspace.ROOT, DocumentKind.Plain, "正文", "last-request")
        store.delete(id)
        val next = store(dir)
        expect(Failure.Missing) { next.create("导入", Workspace.ROOT, DocumentKind.Plain, "旧请求", "last-request") }
        val fresh = next.create("导入", Workspace.ROOT, DocumentKind.Plain, "主动再次打开", "fresh-request")
        assertEquals("主动再次打开", next.read(fresh))
    }
    @Test fun failedReloadInvalidatesStaleCatalogAndCannotAuthorizeFurtherWrites() {
        val dir = temp.newFolder(); val store = store(dir)
        val id = store.create("原稿", Workspace.ROOT, DocumentKind.Plain, "应保留")
        File(dir, "catalog.properties").writeText("schema=99\n")
        expect(Failure.Corrupt) { store.load() }
        expect(Failure.Corrupt) { store.save(id, "不能写入") }
        assertEquals("应保留", File(dir, "documents/$id/body.txt").readText())
    }
}
