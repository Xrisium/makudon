package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.ThumbnailDiskCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThumbnailCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun key(n: Int) = n.toString(16).padStart(64, '0') + "-128-v1.png"
    @Test fun boundsDiskUseAndEvictsLeastRecentlyUsedEntry() {
        val dir = temp.newFolder(); val cache = ThumbnailDiskCache(dir, limit = 200)
        assertTrue(cache.put(key(1), ByteArray(100) { 1 }, cache.generation))
        File(dir, key(1)).setLastModified(1000)
        assertTrue(cache.put(key(2), ByteArray(100) { 2 }, cache.generation))
        File(dir, key(2)).setLastModified(2000)
        cache.read(key(1))
        assertTrue(cache.put(key(3), ByteArray(100) { 3 }, cache.generation))
        assertNull(cache.read(key(2))); assertNotNull(cache.read(key(1))); assertNotNull(cache.read(key(3)))
        assertTrue(cache.bytes() <= 200)
    }
    @Test fun clearFencesBothOldDiskAndMemoryWritesAndLeavesOtherDirectoriesUntouched() {
        val root = temp.newFolder(); val cache = ThumbnailDiskCache(File(root, "thumbnails"))
        val original = File(root, "original.png").apply { writeText("original") }
        val export = File(root, "export.zip").apply { writeText("export") }
        val epoch = cache.generation; cache.put(key(1), byteArrayOf(1), epoch)
        var memoryCleared = false; assertTrue(cache.clear { memoryCleared = true })
        assertTrue(memoryCleared); assertEquals(0L, cache.bytes())
        assertFalse(cache.put(key(2), byteArrayOf(2), epoch)); assertFalse(cache.current(epoch) { fail("stale memory write") })
        assertEquals("original", original.readText()); assertEquals("export", export.readText())
    }
    @Test fun systemRemovalIsAMissAndPartialClearReportsRemainingBytes() {
        val dir = temp.newFolder(); val cache = ThumbnailDiskCache(dir, remove = { it.name != key(2) && it.delete() })
        cache.put(key(1), ByteArray(10), cache.generation); cache.put(key(2), ByteArray(20), cache.generation)
        File(dir, key(1)).delete(); assertNull(cache.read(key(1)))
        assertFalse(cache.clear()); assertEquals(20L, cache.bytes())
    }
    @Test fun disabledPolicyFencesPendingCacheWriteWithoutRemovingExistingData() {
        val dir = temp.newFolder(); val cache = ThumbnailDiskCache(dir)
        assertTrue(cache.put(key(1), byteArrayOf(1), cache.generation))
        assertFalse(cache.put(key(2), byteArrayOf(2), cache.generation) { false })
        assertArrayEquals(byteArrayOf(1), cache.read(key(1))); assertNull(cache.read(key(2)))
        assertEquals(1L, cache.bytes())
    }
    @Test fun cacheKeysCannotEscapeTheDedicatedDirectory() {
        val cache = ThumbnailDiskCache(temp.newFolder())
        try { cache.read("../original.png"); fail("invalid key") } catch (_: IllegalArgumentException) { }
    }
}
