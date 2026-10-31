package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.ExportFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ExportFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sameNamesCreateIndependentCopiesAndSurviveSourceCleanup() {
        val store = ExportFiles(temp.newFolder())
        val zipSource = temp.newFile().apply { writeText("ZIP snapshot 中文") }
        val first = store.prepare("备份.zip", "application/zip") { out -> zipSource.inputStream().use { it.copyTo(out) } }
        val second = store.prepare("备份.zip", "application/zip", first.file) { it.write("another snapshot".toByteArray()) }
        assertTrue(zipSource.delete())
        assertNotEquals(first.file, second.file)
        assertEquals("ZIP snapshot 中文", first.file.readText())
        assertEquals("another snapshot", second.file.readText())
    }

    @Test fun failedWriteLeavesNoPartialCopyAndPreservesActiveSnapshotForRetry() {
        val root = temp.newFolder()
        val store = ExportFiles(root)
        val active = store.prepare("草稿.txt", "text/plain") { it.write("before edits".toByteArray()) }
        try {
            store.prepare("草稿.txt", "text/plain", active.file) { it.write(1); throw IOException("Disk full") }
            fail("Expected write failure")
        } catch (_: IOException) { }
        assertEquals(1, root.listFiles()!!.size)
        val retry = ByteArrayOutputStream()
        ExportFiles.copy(active, retry)
        assertEquals("before edits", retry.toString("UTF-8"))
    }

    @Test fun cleanupKeepsRecentAndActiveCopiesAndDeletesOnlyExpiredCopies() {
        val root = temp.newFolder()
        val store = ExportFiles(root)
        val old = store.prepare("old.txt", "text/plain") { it.write(1) }
        val active = store.prepare("active.txt", "text/plain") { it.write(2) }
        val recent = store.prepare("recent.txt", "text/plain") { it.write(3) }
        val now = System.currentTimeMillis()
        assertTrue(old.file.parentFile!!.setLastModified(now - ExportFiles.RETENTION_MS - 1))
        assertTrue(active.file.parentFile!!.setLastModified(now - ExportFiles.RETENTION_MS - 1))
        store.cleanExpired(active.file, now)
        assertFalse(old.file.exists())
        assertTrue(active.file.exists())
        assertTrue(recent.file.exists())
    }

    @Test fun verificationRejectsTruncationExtraBytesAndSameSizeCorruption() {
        val snapshot = ExportFiles(temp.newFolder()).prepare("文稿.txt", "text/plain") { it.write("abc".toByteArray()) }
        for (bad in listOf("ab", "abcd", "abd")) {
            try { ExportFiles.verify(snapshot, ByteArrayInputStream(bad.toByteArray())); fail("Accepted corrupt export") }
            catch (_: IllegalStateException) { }
        }
        ExportFiles.verify(snapshot, ByteArrayInputStream("abc".toByteArray()))
    }

    @Test fun filenameCannotEscapeTheDedicatedSharingDirectory() {
        val root = temp.newFolder()
        val snapshot = ExportFiles(root).prepare("../../outside\\secret.txt", "text/plain") { it.write(7) }
        assertEquals(root.canonicalFile, snapshot.file.parentFile!!.parentFile!!.canonicalFile)
        assertEquals(7, snapshot.file.readBytes().single().toInt())
        assertFalse(File(root.parentFile, "outside").exists())
    }
}
