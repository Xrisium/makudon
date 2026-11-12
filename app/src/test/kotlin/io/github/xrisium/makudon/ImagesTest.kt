package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImagesTest {
    @get:Rule val temp = TemporaryFolder()
    private val png get() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9n8AAAAASUVORK5CYII=")
    private fun expect(reason: Failure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: WorkspaceException) { assertEquals(reason, e.reason) }
    }
    private fun library() = LibraryController(temp.newFolder()).also { it.load() }
    private fun add(store: WorkspaceStore, parent: String = Workspace.ROOT, name: String = "照片", bytes: ByteArray = png): Entry {
        val id = store.createImage(name, parent, bytes, ImageRules.inspect(bytes))
        return checkNotNull(store.refresh().entry(id))
    }
    private fun plan(lib: LibraryController, source: PreparedArchive, mode: RestoreMode, choices: Map<String, ConflictChoice> = emptyMap()): RestorePlan =
        RestorePlanner.plan(lib.store.refresh(), source.inventory, mode, choices, "恢复", 1800000000000,
            sameText = { local, node, paths -> lib.store.read(local) == source.restoredText(node.id, paths) }) { local, node -> lib.store.readMaterial(local).contentEquals(source.material(node)) }
    private fun zip(vararg items: Pair<String, ByteArray>): PreparedArchive {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip -> items.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return LibraryArchive.read(file, temp.newFolder())
    }

    @Test fun imageBytesAndStableReferenceSurviveMoveRenameAndReload() {
        val lib = library(); val image = add(lib.store)
        val key = checkNotNull(image.image).key
        val text = ImageReferences.syntax("图", ImageReferences.uri(key))
        val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Markdown, text)
        val folder = lib.store.create("目录", Workspace.ROOT, DocumentKind.Folder)
        lib.store.rename(image.id, "新名"); lib.store.move(image.id, folder); lib.store.move(doc, folder)
        lib.load()
        assertArrayEquals(png, lib.store.readMaterial(image.id))
        assertEquals(text, lib.store.read(doc)); assertEquals(key, lib.store.refresh().entry(image.id)?.image?.key)
        assertTrue(lib.store.refresh().recentGroups(RecentCalendar.current()).values.flatten().none { it.isImage })
    }
    @Test fun formatsSizesAndCorruptMaterialAreRejectedWithoutPublishingAnEntry() {
        val lib = library(); val before = lib.store.refresh()
        expect(Failure.InvalidImage) { ImageRules.inspect("not an image".toByteArray()) }
        expect(Failure.ImageTooLarge) { ImageRules.inspect(ByteArray(ImageRules.MAX_BYTES + 1)) }
        val huge = png.copyOf().apply { for (i in 16..23) this[i] = 0x7f }
        expect(Failure.ImageTooLarge) { ImageRules.inspect(huge) }
        val info = ImageRules.inspect(png)
        expect(Failure.Corrupt) { lib.store.createImage("坏", Workspace.ROOT, png.copyOf().apply { this[40] = 5 }, info) }
        assertEquals(before.entries, lib.store.refresh().entries)
    }
    @Test fun failedImageWriteDoesNotPublishOrDamageExistingText() {
        var failImage = false
        val store = WorkspaceStore(temp.newFolder(), object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) { if (failImage && file.name == "body.png") throw IOException("injected"); super.write(file, bytes) }
        })
        store.load(); val doc = store.create("稿", Workspace.ROOT, DocumentKind.Plain, "保留")
        failImage = true
        try { add(store); fail("write should fail") } catch (_: IOException) { }
        assertEquals("保留", store.read(doc)); assertTrue(store.refresh().entries.none { it.isImage })
    }
    @Test fun oldIndexIsBackedUpOnlyWhenPublishingTheUpgradeAndPreferencesPersist() {
        val dir = temp.newFolder(); val store = WorkspaceStore(dir); store.load()
        val doc = store.create("稿", Workspace.ROOT, DocumentKind.Plain, "原文")
        val catalog = File(dir, "catalog.properties")
        val properties = Properties().apply { catalog.reader().use { load(it) }; setProperty("schema", "1"); remove("directoryImages") }
        catalog.writer().use { properties.store(it, "legacy") }; val original = catalog.readBytes()
        val upgraded = WorkspaceStore(dir); val current = upgraded.load()
        assertFalse(File(dir, "catalog.schema1.bak").exists())
        assertEquals(DirectoryImages.Thumbnail, current.directoryImages)
        upgraded.settings(current.theme, current.sort, current.descending, current.foldersFirst, directoryImages = DirectoryImages.Icon)
        assertArrayEquals(original, File(dir, "catalog.schema1.bak").readBytes())
        assertEquals(DirectoryImages.Icon, WorkspaceStore(dir).load().directoryImages)
        assertEquals("原文", upgraded.read(doc))
    }
    @Test fun thumbnailPreferencesDefaultOnPersistIndependentlyAndSurviveFailedWrites() {
        val dir = temp.newFolder()
        var failCatalog = false
        val store = WorkspaceStore(dir, object : AtomicDisk() {
            override fun write(file: File, bytes: ByteArray) {
                if (failCatalog && file.name == "catalog.properties") throw IOException("injected")
                super.write(file, bytes)
            }
        })
        val initial = store.load()
        assertEquals(ThumbnailCaching(), initial.thumbnailCaching)
        val catalog = File(dir, "catalog.properties")
        val legacy = Properties().apply { catalog.reader(Charsets.UTF_8).use { load(it) } }
        listOf("cacheDirectoryThumbnails", "cacheMenuThumbnails", "cacheInformationThumbnails").forEach { legacy.remove(it) }
        catalog.writer(Charsets.UTF_8).use { legacy.store(it, "without optional preferences") }
        assertEquals(ThumbnailCaching(), WorkspaceStore(dir).load().thumbnailCaching)
        for (choice in listOf(ThumbnailCaching(false, true, true), ThumbnailCaching(false, false, true), ThumbnailCaching(false, false, false))) {
            store.settings(initial.theme, initial.sort, initial.descending, initial.foldersFirst, thumbnailCaching = choice)
            assertEquals(choice, WorkspaceStore(dir).load().thumbnailCaching)
        }
        failCatalog = true
        try {
            store.settings(initial.theme, initial.sort, initial.descending, initial.foldersFirst, thumbnailCaching = ThumbnailCaching())
            fail("write should fail")
        } catch (_: IOException) { }
        expect(Failure.Corrupt) { store.refresh() }
        assertEquals(ThumbnailCaching(false, false, false), WorkspaceStore(dir).load().thumbnailCaching)
    }
    @Test fun copyingDocumentSharesImageButCopyingBatchRemapsOnlyInternalReferences() {
        val lib = library(); val folder = lib.store.create("源", Workspace.ROOT, DocumentKind.Folder)
        val image = add(lib.store, folder); val key = checkNotNull(image.image).key
        val doc = lib.store.create("稿", folder, DocumentKind.Markdown, ImageReferences.syntax("图", ImageReferences.uri(key)))
        val target = lib.store.create("目标", Workspace.ROOT, DocumentKind.Folder)
        lib.store.pasteAll(lib.store.captureAll(listOf(doc)), target)
        val single = lib.store.refresh().entries.single { it.parent == target }
        assertEquals(setOf(key), ImageReferences.keys(lib.store.read(single.id)))
        val copies = lib.store.captureAll(listOf(folder))
        lib.store.delete(folder)
        lib.store.pasteAll(copies, Workspace.ROOT)
        val current = lib.store.refresh(); val copyImage = current.entries.single { it.isImage }
        val copyDoc = current.entries.single { it.kind == DocumentKind.Markdown && it.id != single.id }
        assertNotEquals(key, copyImage.image?.key)
        assertEquals(setOf(copyImage.image?.key), ImageReferences.keys(lib.store.read(copyDoc.id)))
        assertEquals(setOf(key), ImageReferences.keys(lib.store.read(single.id)))
        assertArrayEquals(png, lib.store.readMaterial(copyImage.id)); lib.store.releaseCopies(copies)
    }
    @Test fun trashRestorePreservesLogicalImageKeyEvenWhenDirectoryIdMustChange() {
        val dir = temp.newFolder(); val lib = LibraryController(dir).also { it.load() }; val trash = TrashStore(dir, lib); trash.load()
        val image = add(lib.store); val key = image.image?.key
        val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Markdown, ImageReferences.syntax("图", ImageReferences.uri(checkNotNull(key))))
        trash.delete(listOf(image.id)); assertEquals(setOf(key), ImageReferences.keys(lib.store.read(doc)))
        // An unindexed interrupted material forces allocation of a different directory node ID.
        lib.store.materialFile(doc).parentFile?.parentFile?.resolve(image.id)?.mkdirs()
        val node = trash.inventory().nodes.single()
        val restored = trash.restore(listOf(node.id), false) { " (恢复 $it)" }.entries.single()
        assertNotEquals(image.id, restored.id); assertEquals(key, restored.image?.key)
        assertArrayEquals(png, lib.store.readMaterial(restored.id))
    }
    @Test fun zipRoundTripPreservesImageBytesAndRebindsReferencesForEveryModeAndUndo() {
        val origin = library(); val image = add(origin.store)
        origin.store.create("日记", Workspace.ROOT, DocumentKind.Markdown, ImageReferences.syntax("照片", ImageReferences.uri(checkNotNull(image.image).key)))
        val exported = temp.newFile(); LibraryArchive.writeMaterials(exported, LibraryArchive.exportPlan(origin.store.refresh()), origin.store::readMaterial)
        java.util.zip.ZipFile(exported).use { zip ->
            val text = zip.getInputStream(zip.getEntry("日记.md")).bufferedReader().readText()
            assertFalse(text.contains("makudon-image:")); assertTrue(text.contains("照片.png"))
        }
        RestoreMode.entries.forEach { mode ->
            val lib = library(); val source = LibraryArchive.read(exported, temp.newFolder())
            val candidate = lib.prepare(plan(lib, source, mode), source, lib.fingerprint()); lib.commit(candidate)
            val state = lib.store.refresh(); val restoredImage = state.entries.single { it.isImage }; val doc = state.entries.single { it.kind == DocumentKind.Markdown }
            assertArrayEquals(png, lib.store.readMaterial(restoredImage.id))
            assertEquals(setOf(restoredImage.image?.key), ImageReferences.keys(lib.store.read(doc.id)))
            val repeated = plan(lib, source, RestoreMode.Incremental)
            // Separate mode's wrapper changes external paths, so it is deliberately not a path match.
            if (mode != RestoreMode.Separate) { assertTrue(repeated.resolved); assertFalse(repeated.changed) }
            lib.undo(); assertTrue(lib.store.refresh().entries.none { it.isImage }); lib.undo()
            assertArrayEquals(png, lib.store.readMaterial(restoredImage.id))
        }
    }
    @Test fun restoringAnImageKeyConflictRemapsOnlyTheRestoredDocuments() {
        val dir = temp.newFolder(); val lib = LibraryController(dir).also { it.load() }; val trash = TrashStore(dir, lib); trash.load()
        val folder = lib.store.create("删除批次", Workspace.ROOT, DocumentKind.Folder)
        val image = add(lib.store, folder); val key = checkNotNull(image.image).key
        lib.store.create("原稿", folder, DocumentKind.Markdown, ImageReferences.syntax("图", ImageReferences.uri(key)))
        trash.delete(listOf(folder))
        val activeImage = lib.store.createImage("当前图片", Workspace.ROOT, png, checkNotNull(image.image))
        val activeDoc = lib.store.create("当前稿", Workspace.ROOT, DocumentKind.Markdown, ImageReferences.syntax("图", ImageReferences.uri(key)))
        trash.restore(listOf(trash.inventory().roots.single().id), false) { " (恢复 $it)" }
        val state = lib.store.refresh(); val restored = state.entries.single { it.isImage && it.id != activeImage }
        assertNotEquals(key, restored.image?.key)
        assertEquals(setOf(key), ImageReferences.keys(lib.store.read(activeDoc)))
        assertEquals(setOf(restored.image?.key), ImageReferences.keys(lib.store.read(state.entries.single { it.name == "原稿" }.id)))
    }
    @Test fun incomingDocumentsFollowExplicitImageConflictChoices() {
        ConflictChoice.entries.forEach { choice ->
            val lib = library(); val local = add(lib.store, name = "photo")
            val changed = png.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
            val source = zip("photo.png" to changed, "incoming.md" to "![图](photo.png)".toByteArray())
            val imageNode = source.inventory.nodes.single { it.kind == DocumentKind.Image }
            val planned = plan(lib, source, RestoreMode.Incremental, mapOf(imageNode.id to choice))
            assertTrue(planned.resolved)
            lib.commit(lib.prepare(planned, source, lib.fingerprint()))
            val state = lib.store.refresh(); val doc = state.entries.single { it.kind == DocumentKind.Markdown }
            val key = ImageReferences.keys(lib.store.read(doc.id)).single(); val image = state.entries.single { it.image?.key == key }
            assertArrayEquals(if (choice == ConflictChoice.Local) png else changed, lib.store.readMaterial(image.id))
            if (choice == ConflictChoice.Both) { assertNotEquals(local.image?.key, key); assertEquals(2, state.entries.count { it.isImage }) }
            else assertEquals(local.image?.key, key)
        }
    }
    @Test fun differentImageExtensionsNeverReplaceOrRebindAnExistingImage() {
        val jpeg = java.io.ByteArrayOutputStream().also { output -> javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", output) }.toByteArray()
        val lib = library(); val original = add(lib.store, name = "photo", bytes = jpeg)
        val source = zip("photo.png" to png, "incoming.md" to "![图](photo.png)".toByteArray())
        val planned = plan(lib, source, RestoreMode.Incremental)
        assertTrue(planned.conflicts.isEmpty())
        lib.commit(lib.prepare(planned, source, lib.fingerprint()))
        val state = lib.store.refresh(); assertEquals(2, state.entries.count { it.isImage })
        assertArrayEquals(jpeg, lib.store.readMaterial(original.id))
        val doc = state.entries.single { it.kind == DocumentKind.Markdown }
        val imported = state.entries.single { it.image?.format == ImageFormat.Png }
        assertEquals(setOf(imported.image?.key), ImageReferences.keys(lib.store.read(doc.id)))
    }
    @Test fun referencesIgnoreCodeAndHtmlAndResolveOnlyWithinArchive() {
        val key = java.util.UUID.randomUUID().toString(); val syntax = ImageReferences.syntax("图 [一]", ImageReferences.uri(key))
        val text = "$syntax\n\n`$syntax`\n\n```\n$syntax\n```\n\n<!-- $syntax -->"
        assertEquals(1, ImageReferences.images(text).size)
        val other = java.util.UUID.randomUUID().toString()
        val mapped = ImageReferences.remap(text, mapOf(key to other))
        assertTrue(mapped.startsWith(ImageReferences.syntax("图 [一]", ImageReferences.uri(other))))
        assertTrue(mapped.contains("`$syntax`"))
        assertEquals("images/图 片.png", ImageReferences.resolve("docs/a.md", "../images/%E5%9B%BE%20%E7%89%87.png"))
        listOf("../../outside.png", "/data/a.png", "file:///a.png", "https://example.com/a.png", "..%2f..%2fa.png").forEach { assertNull(ImageReferences.resolve("docs/a.md", it)) }
    }
    @Test fun insertionIsOneBoundedEditAndPreservesSurroundingText() {
        val key = java.util.UUID.randomUUID().toString()
        val result = ImageReferences.insert("前选中后", TextSelection(1, 3), "图", key)
        assertEquals("前\n\n${ImageReferences.syntax("图", ImageReferences.uri(key))}\n\n后", result.text)
        assertEquals(1, ImageReferences.standalone(MarkdownParser.parse(result.text)).size)
        expect(Failure.InvalidImage) { ImageReferences.insert("`代码`", TextSelection(2), "图", key) }
        assertTrue(ImageReferences.standalone(MarkdownParser.parse("![外部](https://example.com/image.png)\n\n![未授权](../image.png)")).isEmpty())
    }
    @Test fun documentZipContainsItsImageFolderAndDeduplicatesReferencesAndRejectsMissingImages() {
        val lib = library(); val image = add(lib.store); val key = checkNotNull(image.image).key
        val text = ImageReferences.syntax("一", ImageReferences.uri(key)) + "\n\n" + ImageReferences.syntax("二", ImageReferences.uri(key))
        val doc = lib.store.create("稿", Workspace.ROOT, DocumentKind.Markdown, text)
        val plan = LibraryArchive.documentPlan(lib.store.refresh(), doc, text)
        assertEquals(3, plan.items.size); assertEquals(1, plan.items.count { it.entry.isImage })
        val file = temp.newFile(); LibraryArchive.writeMaterials(file, plan, lib.store::readMaterial)
        java.util.zip.ZipFile(file).use { zip ->
            assertNotNull(zip.getEntry("images/"))
            assertFalse(zip.getInputStream(zip.getEntry("稿.md")).bufferedReader().readText().contains("makudon-image:"))
        }
        lib.store.delete(image.id)
        expect(Failure.MissingImage) { LibraryArchive.documentPlan(lib.store.refresh(), doc, text) }
    }
    @Test fun exifOrientationSwapsDisplayDimensionsWithoutChangingOriginalPixelsOrDigest() {
        val bytes = java.io.ByteArrayOutputStream().also { output -> javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", output) }.toByteArray()
        val exif = byteArrayOf(69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val oriented = bytes.take(2).toByteArray() + byteArrayOf(255.toByte(), 225.toByte(), 0, (exif.size + 2).toByte()) + exif + bytes.drop(2).toByteArray()
        val info = ImageRules.inspect(oriented)
        assertEquals(6, info.orientation); assertEquals(8, info.width); assertEquals(4, info.height)
        assertEquals(4, info.displayWidth); assertEquals(8, info.displayHeight)
        assertEquals(ImageRules.digest(oriented), info.sha256); assertEquals(info, ImageInfo.decode(info.encoded()))
    }
    @Test fun rewritingImageDestinationPreservesFormattedAltAndExactInlineTitleAndSpacing() {
        val old = ImageReferences.uri(java.util.UUID.randomUUID().toString())
        val next = ImageReferences.uri(java.util.UUID.randomUUID().toString())
        val source = "前文 ![图 **粗体** `[代码]` [括号]]( <$old>  '标题 \"保留\"' ) 后文\n"
        assertEquals(source.replace(old, next), ImageReferences.rewrite(source) { next })
        val bare = source.replace("<$old>", old)
        assertEquals(bare.replace(old, next), ImageReferences.rewrite(bare) { next })
    }
    @Test fun referenceStyleImageKeepsNestedAltSyntaxAndTitleWhileRebinding() {
        val old = ImageReferences.uri(java.util.UUID.randomUUID().toString())
        val next = ImageReferences.uri(java.util.UUID.randomUUID().toString())
        val alt = "**图** [链接](https://example.com/])"
        val source = "![$alt][photo]\n\n[photo]: <$old> '说明'\n"
        val result = ImageReferences.rewrite(source) { next }
        assertTrue(result.startsWith("![$alt](<$next> \"说明\")"))
        val image = ImageReferences.images(result).single()
        assertEquals("说明", image.title); assertEquals(next, image.destination)
        assertTrue(result.endsWith("[photo]: <$old> '说明'\n"))
    }
}
