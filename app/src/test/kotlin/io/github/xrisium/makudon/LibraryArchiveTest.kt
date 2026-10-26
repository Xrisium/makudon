package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name).apply { time = 1_700_000_000_000 }); out.write(bytes); out.closeEntry()
        } }
    }
    private fun archive(vararg entries: Pair<String, String>) = LibraryArchive.read(zip(*entries.map { it.first to it.second.toByteArray(Charsets.UTF_8) }.toTypedArray()), temp.newFolder())
    private fun expect(reason: Failure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: WorkspaceException) { assertEquals(reason, e.reason) }
    }
    private fun library(directory: File = temp.newFolder(), disk: AtomicDisk = AtomicDisk()) = LibraryController(directory, atomic = disk).also { it.load() }
    private fun plan(controller: LibraryController, source: PreparedArchive, mode: RestoreMode, choices: Map<String, ConflictChoice> = emptyMap()) =
        RestorePlanner.plan(controller.store.refresh(), source.inventory, mode, choices, "恢复 2026-10-27 1200", 1_800_000_000_000) { id, node -> controller.store.read(id) == source.text(node) }
    private fun restore(controller: LibraryController, source: PreparedArchive, mode: RestoreMode, choices: Map<String, ConflictChoice> = emptyMap()) {
        controller.commit(controller.prepare(plan(controller, source, mode, choices), source, controller.fingerprint()))
    }

    @Test fun computerZipNeedsNoManifestAndPreservesOuterDirectoriesEmptyFoldersAndTextKinds() {
        val source = archive("电脑项目/日记.md" to "\uFEFF# 中文 😀\r\n\r\n  缩进\r末尾", "电脑项目/空.txt" to "", "空目录/" to "", "电脑项目/photo.png" to "image")
        assertEquals(2, source.inventory.documents)
        assertEquals(listOf("电脑项目/photo.png"), source.inventory.skipped)
        assertEquals("# 中文 😀\n\n  缩进\n末尾", source.text(source.inventory.nodes.first { it.kind == DocumentKind.Markdown }.id))
        assertTrue(source.inventory.nodes.any { it.name == "空目录" && it.kind == DocumentKind.Folder })
        val lib = library()
        restore(lib, source, RestoreMode.Separate)
        val ws = lib.store.refresh()
        val wrapper = ws.entries.single { it.name.startsWith("恢复 ") }
        assertEquals(Workspace.ROOT, wrapper.parent)
        assertTrue(ws.entries.any { it.name == "电脑项目" && it.parent == wrapper.id })
        assertTrue(ws.entries.none { node -> source.inventory.nodes.any { it.id == node.id } })
        assertTrue(ws.entries.filter { !it.folder }.all { it.opened == 0L && it.created == 1_800_000_000_000 })
    }

    @Test fun exportRoundTripContainsOnlyDirectoriesAndPlainBodiesAndNoWrapperOrHistory() {
        val lib = library()
        val folder = lib.store.create("中文 😀", Workspace.ROOT, DocumentKind.Folder)
        lib.store.create("空目录", Workspace.ROOT, DocumentKind.Folder)
        val md = lib.store.create("文稿", folder, DocumentKind.Markdown, "旧版本")
        lib.store.save(md, "# 新正文 👩🏽‍💻\n\n  不裁剪\n")
        lib.store.create("空", Workspace.ROOT, DocumentKind.Plain, "")
        val file = temp.newFile()
        LibraryArchive.write(file, LibraryArchive.exportPlan(lib.store.refresh()), lib.store::read)
        java.util.zip.ZipFile(file).use { assertEquals(setOf("中文 😀/", "中文 😀/文稿.md", "空目录/", "空.txt"), it.entries().asSequence().map { e -> e.name }.toSet()) }
        val source = LibraryArchive.read(file, temp.newFolder())
        val target = library()
        restore(target, source, RestoreMode.Replace)
        val text = target.store.refresh().entries.single { it.kind == DocumentKind.Markdown }
        assertEquals(lib.store.read(md), target.store.read(text.id))
        expect(Failure.NoPrevious) { target.store.previous(text.id) }
        assertTrue(target.store.refresh().entries.any { it.name == "空目录" })
    }

    @Test fun exportPhysicalFileFolderCollisionIsPreviewedAndRoundTripsWithoutOverwriting() {
        val lib = library()
        lib.store.create("同名.txt", Workspace.ROOT, DocumentKind.Folder)
        val id = lib.store.create("同名", Workspace.ROOT, DocumentKind.Plain, "正文")
        val plan = LibraryArchive.exportPlan(lib.store.refresh())
        assertEquals(1, plan.renames.size)
        assertEquals("同名（副本）.txt", plan.items.single { it.entry.id == id }.path)
        LibraryArchive.write(temp.newFile(), plan, lib.store::read)
        assertEquals("同名", lib.store.refresh().entry(id)?.name)
    }

    @Test fun incrementalRequiresExplicitChoicesAndAllThreeChoicesPreserveLocalOnlyDocuments() {
        ConflictChoice.entries.forEach { choice ->
            val lib = library()
            val folder = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
            val conflict = lib.store.create("冲突", folder, DocumentKind.Markdown, "本地")
            val localOnly = lib.store.create("独有", folder, DocumentKind.Plain, "保留")
            val same = lib.store.create("相同", folder, DocumentKind.Plain, "相同正文")
            val source = archive("目录/冲突.md" to "备份", "目录/相同.txt" to "相同正文", "目录/新增.txt" to "新增")
            val unresolved = plan(lib, source, RestoreMode.Incremental)
            assertEquals(1, unresolved.conflicts.size)
            assertFalse(unresolved.resolved)
            expect(Failure.Conflict) { lib.prepare(unresolved, source, lib.fingerprint()) }
            val key = unresolved.conflicts.single().source.id
            restore(lib, source, RestoreMode.Incremental, mapOf(key to choice))
            assertEquals("保留", lib.store.read(localOnly))
            assertEquals("相同正文", lib.store.read(same))
            assertEquals(if (choice == ConflictChoice.Backup) "备份" else "本地", lib.store.read(conflict))
            val copies = lib.store.refresh().entries.filter { it.name.startsWith("冲突（恢复副本") }
            assertEquals(if (choice == ConflictChoice.Both) 1 else 0, copies.size)
            copies.firstOrNull()?.let { assertEquals("备份", lib.store.read(it.id)); assertNotEquals(conflict, it.id) }
            assertTrue(lib.store.refresh().entries.any { it.name == "新增" && it.parent == folder })
        }
    }

    @Test fun sameBodyWithDifferentTimeSkipsAndRenamedPathCreatesNewDocument() {
        val lib = library()
        val id = lib.store.create("旧名", Workspace.ROOT, DocumentKind.Plain, "相同")
        val source = archive("旧名.txt" to "相同")
        val plan = plan(lib, source, RestoreMode.Incremental)
        assertTrue(plan.resolved); assertFalse(plan.changed)
        assertEquals(1, plan.unchanged)
        val renamed = archive("新名.txt" to "相同")
        restore(lib, renamed, RestoreMode.Incremental)
        assertEquals("相同", lib.store.read(id))
        assertEquals(2, lib.store.refresh().entries.count { !it.folder })
    }

    @Test fun caseOnlyCollisionsAreRenamedBeforePlanningAndDescendantsFollowTheirFolder() {
        val source = archive("A/x.md" to "甲", "a/x.md" to "乙", "note.TXT" to "一", "NOTE.txt" to "二")
        assertTrue(source.inventory.renames.size >= 2)
        val lib = library()
        restore(lib, source, RestoreMode.Replace)
        val bodies = lib.store.refresh().entries.filter { !it.folder }.map { lib.store.read(it.id) }.toSet()
        assertEquals(setOf("甲", "乙", "一", "二"), bodies)
    }

    @Test fun replacementAndUndoSurviveRestartAndDoNotShareMutableBodies() {
        val dir = temp.newFolder()
        var lib = library(dir)
        val id = lib.store.create("本地", Workspace.ROOT, DocumentKind.Plain, "上一版")
        lib.store.save(id, "恢复前")
        restore(lib, archive("备份.md" to "备份正文"), RestoreMode.Replace)
        lib = library(dir)
        assertNull(lib.store.refresh().entry(id))
        val restored = lib.store.refresh().entries.single { !it.folder }
        lib.store.save(restored.id, "恢复后继续编辑")
        lib.undo()
        lib = library(dir)
        assertEquals("恢复前", lib.store.read(id))
        assertEquals("上一版", lib.store.previous(id))
        lib.store.save(id, "撤回后新编辑")
        lib.undo()
        lib = library(dir)
        assertEquals("恢复后继续编辑", lib.store.read(restored.id))
        lib.undo()
        assertEquals("撤回后新编辑", lib.store.read(id))
    }

    @Test fun secondSuccessfulRestoreReplacesSnapshotAndManualCleanupPreservesCurrentLibrary() {
        val dir = temp.newFolder()
        val lib = library(dir)
        lib.store.create("最早", Workspace.ROOT, DocumentKind.Plain, "原始")
        restore(lib, archive("一次.txt" to "一次"), RestoreMode.Replace)
        restore(lib, archive("二次.txt" to "二次"), RestoreMode.Replace)
        assertFalse(File(dir, "workspace").exists())
        assertEquals(2, File(dir, "library-generations").listFiles()?.size)
        lib.undo()
        assertEquals("一次", lib.store.read(lib.store.refresh().entries.single { !it.folder }.id))
        lib.clearSnapshot()
        assertNull(lib.snapshot())
        expect(Failure.NoSnapshot) { lib.undo() }
        assertEquals("一次", library(dir).store.refresh().entries.single { !it.folder }.name)
    }

    @Test fun prepareIsInvisibleUntilCommitAndStalePlansCannotPublish() {
        val dir = temp.newFolder()
        val lib = library(dir)
        val id = lib.store.create("本地", Workspace.ROOT, DocumentKind.Plain, "未改")
        val source = archive("备份.txt" to "备份")
        val prepared = lib.prepare(plan(lib, source, RestoreMode.Replace), source, lib.fingerprint())
        assertEquals("未改", library(dir).store.read(id))
        lib.store.save(id, "已改")
        expect(Failure.StaleRestore) { lib.commit(prepared) }
        assertEquals("已改", library(dir).store.read(id))
        assertNull(lib.snapshot())
    }

    @Test fun candidateBodyOrCatalogFailureLeavesOriginalAndExistingSnapshotUntouched() {
        listOf("body.txt", "catalog.properties").forEach { target ->
            val dir = temp.newFolder()
            var fail = false
            val disk = object : AtomicDisk() {
                override fun write(file: File, bytes: ByteArray) {
                    if (fail && file.name == target) throw IOException("injected candidate write failure")
                    super.write(file, bytes)
                }
            }
            val lib = library(dir, disk)
            lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
            restore(lib, archive("当前.txt" to "当前"), RestoreMode.Replace)
            val prior = lib.snapshot()
            val source = archive("新库.txt" to "新库")
            val plan = plan(lib, source, RestoreMode.Replace)
            val baseline = lib.fingerprint()
            fail = true
            try { lib.prepare(plan, source, baseline); fail("expected failure") } catch (_: IOException) { }
            assertEquals("当前", library(dir).store.refresh().entries.single { !it.folder }.name)
            assertEquals(prior, lib.snapshot())
        }
    }

    @Test fun pointerFailureBeforeAndAfterRenameStopsCachedWritesAndReloadSelectsACompleteLibrary() {
        listOf(false, true).forEach { after ->
            val dir = temp.newFolder()
            var inject = false
            val disk = object : AtomicDisk() {
                override fun write(file: File, bytes: ByteArray) {
                    if (inject && file.name == "library-state.properties") {
                        if (after) super.write(file, bytes)
                        throw IOException("injected pointer failure")
                    }
                    super.write(file, bytes)
                }
            }
            val lib = library(dir, disk)
            lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
            val source = archive("新库.txt" to "新库")
            val candidate = lib.prepare(plan(lib, source, RestoreMode.Replace), source, lib.fingerprint())
            inject = true
            try { lib.commit(candidate); fail("expected pointer error") } catch (_: IOException) { }
            expect(Failure.Corrupt) { lib.store.refresh() }
            val reloaded = library(dir)
            assertEquals(if (after) "新库" else "原始", reloaded.store.refresh().entries.single { !it.folder }.name)
            if (after) { reloaded.undo(); assertEquals("原始", reloaded.store.refresh().entries.single { !it.folder }.name) }
        }
    }

    @Test fun insufficientSpaceAndCancellationDoNotChangeCurrentOrSnapshot() {
        val dir = temp.newFolder()
        val lib = LibraryController(dir, available = { 0 }).also { it.load() }
        val id = lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
        val source = archive("备份.txt" to "备份")
        expect(Failure.Space) { lib.prepare(plan(lib, source, RestoreMode.Replace), source, lib.fingerprint()) }
        val normal = library(dir)
        try { normal.prepare(plan(normal, source, RestoreMode.Replace), source, normal.fingerprint()) { throw java.util.concurrent.CancellationException() }; fail("cancel expected") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals("原始", library(dir).store.read(id))
        assertNull(normal.snapshot())
    }

    @Test fun failedSnapshotCleanupIsRetryableAndNeverDeletesActiveLibrary() {
        val dir = temp.newFolder()
        var deny = true
        val lib = LibraryController(dir, cleanup = { if (deny) false else it.deleteRecursively() }).also { it.load() }
        lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
        restore(lib, archive("新库.txt" to "新库"), RestoreMode.Replace)
        expect(Failure.Cleanup) { lib.clearSnapshot() }
        assertTrue(checkNotNull(lib.snapshot()).retired > 0)
        assertEquals("新库", lib.store.refresh().entries.single { !it.folder }.name)
        deny = false
        lib.clearSnapshot()
        assertNull(lib.snapshot())
        assertEquals("新库", library(dir).store.refresh().entries.single { !it.folder }.name)
    }

    @Test fun corruptPointerOrMissingActiveCatalogCannotInitializeAnEmptyLibrary() {
        val dir = temp.newFolder()
        val lib = library(dir)
        lib.store.create("保留", Workspace.ROOT, DocumentKind.Plain, "保留")
        restore(lib, archive("新库.txt" to "新库"), RestoreMode.Replace)
        File(dir, "library-state.properties").writeText("schema=1\nactive=../../escape\ntime=0\n")
        expect(Failure.Corrupt) { library(dir) }
        assertTrue(File(dir, "workspace/catalog.properties").exists())
        File(dir, "library-state.properties").writeText("schema=1\nactive=00000000-0000-0000-0000-000000000000\ntime=0\n")
        expect(Failure.Corrupt) { library(dir) }
        assertFalse(File(dir, "library-generations/00000000-0000-0000-0000-000000000000").exists())
    }

    @Test fun traversalAbsoluteBackslashAndExcessDepthAreRejectedWithoutWritingOutsideStage() {
        listOf("../a.md", "/a.md", "C:/a.md", "a\\b.md", "a/./b.md", "a//b.md").forEach { path ->
            expect(Failure.InvalidArchive) { LibraryArchive.read(zip(path to "正文".toByteArray()), temp.newFolder()) }
        }
        expect(Failure.ArchiveLimit) { archive(("a/".repeat(128) + "x.md") to "正文") }
    }

    @Test fun fileDirectoryClashIsRejectedInBothOrdersIncludingUnsupportedFiles() {
        listOf(listOf("a.md", "a.md/b.txt"), listOf("a.md/b.txt", "a.md"), listOf("a.bin", "a.bin/b.txt"), listOf("a.bin/b.txt", "a.bin")).forEach { paths ->
            expect(Failure.InvalidArchive) { archive(*paths.map { it to "正文" }.toTypedArray()) }
        }
    }

    @Test fun malformedEncodingOverlongSupportedTextAndNoDocumentsRejectTheWholePackage() {
        expect(Failure.InvalidText) { LibraryArchive.read(zip("a.md" to byteArrayOf(0xff.toByte())), temp.newFolder()) }
        expect(Failure.TooLarge) { archive("a.md" to "中".repeat(100_001)) }
        expect(Failure.EmptyArchive) { archive("empty/" to "", "only.png" to "image") }
        expect(Failure.InvalidName) { archive(("a".repeat(121) + ".txt") to "正文") }
    }

    @Test fun truncatedAndCrcTamperedArchivesAreRejected() {
        val file = zip("a.md" to "正文".toByteArray())
        file.writeBytes(file.readBytes().dropLast(12).toByteArray())
        expect(Failure.InvalidArchive) { LibraryArchive.read(file, temp.newFolder()) }
        val stored = temp.newFile()
        val bytes = "UNIQUE_BODY".toByteArray()
        ZipOutputStream(stored.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.txt").apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value })
            out.write(bytes); out.closeEntry()
        }
        val raw = stored.readBytes()
        val offset = raw.indices.first { i -> i + bytes.size <= raw.size && raw.copyOfRange(i, i + bytes.size).contentEquals(bytes) }
        raw[offset] = 'X'.code.toByte(); stored.writeBytes(raw)
        expect(Failure.InvalidArchive) { LibraryArchive.read(stored, temp.newFolder()) }
    }

    @Test fun exactlyDuplicateZipPathsAreRejected() {
        val file = zip("a.md" to "一".toByteArray(), "b.md" to "二".toByteArray())
        val bytes = file.readBytes()
        for (i in 0..bytes.size - 4) if (bytes.copyOfRange(i, i + 4).contentEquals("b.md".toByteArray())) bytes[i] = 'a'.code.toByte()
        file.writeBytes(bytes)
        expect(Failure.InvalidArchive) { LibraryArchive.read(file, temp.newFolder()) }
    }

    @Test fun exportedArchiveCannotSilentlySkipUnreadableBodies() {
        val lib = library()
        lib.store.create("坏稿", Workspace.ROOT, DocumentKind.Plain, "正文")
        val file = temp.newFile()
        try { LibraryArchive.write(file, LibraryArchive.exportPlan(lib.store.refresh()), { throw IOException("unreadable") }); fail("export must fail") }
        catch (_: IOException) { }
    }

    @Test fun entryFloodAndForgedExpansionSizeAreRejectedBeforeMaterializingBodies() {
        val flood = temp.newFile()
        ZipOutputStream(flood.outputStream()).use { out -> repeat(20_001) { out.putNextEntry(ZipEntry("$it.bin")); out.closeEntry() } }
        val destination = temp.newFolder()
        expect(Failure.ArchiveLimit) { LibraryArchive.read(flood, destination) }
        assertTrue(destination.listFiles().orEmpty().isEmpty())
        val bomb = zip("a.bin" to byteArrayOf(0))
        val raw = bomb.readBytes()
        val central = (0..raw.size - 4).first { raw[it] == 0x50.toByte() && raw[it + 1] == 0x4b.toByte() && raw[it + 2] == 0x01.toByte() && raw[it + 3] == 0x02.toByte() }
        val size = LibraryArchive.MAX_BYTES + 1
        repeat(4) { raw[central + 24 + it] = (size shr (8 * it)).toByte() }
        bomb.writeBytes(raw)
        expect(Failure.ArchiveLimit) { LibraryArchive.read(bomb, temp.newFolder()) }
    }

    @Test fun encryptedFlagIsRejectedAndDoesNotBecomePlainText() {
        val file = zip("a.md" to "正文".toByteArray())
        val raw = file.readBytes()
        raw[6] = (raw[6].toInt() or 1).toByte()
        val central = (0..raw.size - 4).first { raw[it] == 0x50.toByte() && raw[it + 1] == 0x4b.toByte() && raw[it + 2] == 0x01.toByte() && raw[it + 3] == 0x02.toByte() }
        raw[central + 8] = (raw[central + 8].toInt() or 1).toByte()
        file.writeBytes(raw)
        expect(Failure.InvalidArchive) { LibraryArchive.read(file, temp.newFolder()) }
    }

    @Test fun cancellationAfterFirstCandidateBodyCleansOnlyTheUnpublishedGeneration() {
        val dir = temp.newFolder()
        val lib = library(dir)
        val id = lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
        val source = archive("a.txt" to "一", "b.md" to "二")
        val prepared = plan(lib, source, RestoreMode.Replace)
        val baseline = lib.fingerprint()
        var checks = 0
        try { lib.prepare(prepared, source, baseline) { if (++checks == 3) throw java.util.concurrent.CancellationException() }; fail("cancel expected") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals("原始", library(dir).store.read(id))
        assertTrue(File(dir, "library-generations").listFiles().orEmpty().isEmpty())
    }

    @Test fun unchangedIncrementalRestoreCannotReplaceAnExistingSnapshot() {
        val lib = library()
        lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, "原始")
        val source = archive("当前.txt" to "当前")
        restore(lib, source, RestoreMode.Replace)
        val snapshot = lib.snapshot()
        val unchanged = plan(lib, source, RestoreMode.Incremental)
        assertFalse(unchanged.changed)
        expect(Failure.NoChange) { lib.prepare(unchanged, source, lib.fingerprint()) }
        assertEquals(snapshot, lib.snapshot())
        lib.undo()
        assertEquals("原始", lib.store.refresh().entries.single { !it.folder }.name)
    }

    @Test fun missingEstablishedPointerCannotSelectAStaleLegacyLibraryOrCreateAnEmptyOne() {
        val dir = temp.newFolder()
        val lib = library(dir)
        lib.store.create("最早", Workspace.ROOT, DocumentKind.Plain, "最早")
        restore(lib, archive("当前.txt" to "当前"), RestoreMode.Replace)
        assertTrue(File(dir, "library-state.properties").delete())
        expect(Failure.Corrupt) { library(dir) }
        assertTrue(File(dir, "workspace/catalog.properties").exists())
        assertTrue(File(dir, "library-generations").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun fullCatalogStillAllowsChoosingLocalOrBackupWithoutAllocatingAnUnconfirmedCopy() {
        val root = Entry(Workspace.ROOT, "库", null, DocumentKind.Folder, 1)
        val local = Workspace(listOf(root) + (0 until 9_999).map { Entry(java.util.UUID.randomUUID().toString(), "文稿$it", Workspace.ROOT, DocumentKind.Plain, 1) })
        val incoming = ArchiveNode(java.util.UUID.randomUUID().toString(), "文稿0", null, DocumentKind.Plain, 2, 1, "文稿0.txt")
        val archive = ArchiveInventory(listOf(incoming), emptyList(), emptyList())
        val unresolved = RestorePlanner.plan(local, archive, RestoreMode.Incremental, folderName = "恢复", now = 3) { _, _ -> false }
        assertFalse(unresolved.resolved)
        assertEquals(10_000, unresolved.workspace.entries.size)
        val adopted = RestorePlanner.plan(local, archive, RestoreMode.Incremental, mapOf(incoming.id to ConflictChoice.Backup), "恢复", 3) { _, _ -> false }
        assertTrue(adopted.resolved)
        assertEquals(1, adopted.updated)
        assertEquals(10_000, adopted.workspace.entries.size)
    }

    @Test fun exportVerificationDoesNotNormalizePrivateBodyBytes() {
        val lib = library()
        val original = "\uFEFF正文\r\n保留\r换行"
        lib.store.create("原始", Workspace.ROOT, DocumentKind.Plain, original)
        val file = temp.newFile()
        LibraryArchive.write(file, LibraryArchive.exportPlan(lib.store.refresh()), lib.store::read)
        java.util.zip.ZipFile(file).use { zip -> assertArrayEquals(TextRules.bytes(original), zip.getInputStream(zip.getEntry("原始.txt")).use { it.readBytes() }) }
    }

    @Test fun oversizedCentralDirectoryIsRejectedBeforeZipFileAllocatesItsNames() {
        val file = zip("a.md" to "正文".toByteArray())
        val bytes = file.readBytes()
        val end = bytes.size - 22
        val size = 16 * 1024 * 1024 + 1
        repeat(4) { bytes[end + 12 + it] = (size shr (it * 8)).toByte() }
        file.writeBytes(bytes)
        expect(Failure.ArchiveLimit) { LibraryArchive.read(file, temp.newFolder()) }
    }
}
