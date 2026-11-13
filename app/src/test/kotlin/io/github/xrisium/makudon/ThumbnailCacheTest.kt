package io.github.xrisium.makudon

import io.github.xrisium.makudon.data.ThumbnailDiskCache
import io.github.xrisium.makudon.data.ThumbnailKeys
import io.github.xrisium.makudon.domain.ThumbnailUsage
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
    private fun scoped(usage: ThumbnailUsage, n: Int = 1) = ThumbnailKeys.key(n.toString(16).padStart(64, '0'), 128, usage)
    @Test fun scenesHaveIndependentVersionsAndSizesWhileLegacyFilesRemainOtherPreviews() {
        val cache = ThumbnailDiskCache(temp.newFolder())
        val sizes = mapOf(ThumbnailUsage.Directory to 3, ThumbnailUsage.Menu to 5, ThumbnailUsage.Information to 7, ThumbnailUsage.Content to 11)
        sizes.forEach { (usage, bytes) -> assertTrue(cache.put(scoped(usage), ByteArray(bytes), cache.generation)) }
        assertEquals(sizes.mapValues { it.value.toLong() }, cache.sizes())
        assertEquals(26L, cache.bytes())
        assertTrue(cache.clear(ThumbnailUsage.Menu))
        assertNull(cache.read(scoped(ThumbnailUsage.Menu)))
        assertEquals(21L, cache.bytes())
        sizes.filterKeys { it != ThumbnailUsage.Menu }.forEach { (usage, bytes) -> assertEquals(bytes, cache.read(scoped(usage))?.size) }
    }
    @Test fun sceneClearFencesItsOldDiskAndMemoryWritesButAllowsOtherScenesToFinish() {
        val cache = ThumbnailDiskCache(temp.newFolder())
        val before = cache.generation
        assertTrue(cache.clear(ThumbnailUsage.Directory))
        assertFalse(cache.put(scoped(ThumbnailUsage.Directory), byteArrayOf(1), before))
        assertFalse(cache.current(before, ThumbnailUsage.Directory) { fail("old scene memory write") })
        for (other in listOf(ThumbnailUsage.Menu, ThumbnailUsage.Information, ThumbnailUsage.Content)) {
            assertTrue(cache.put(scoped(other), byteArrayOf(2), before))
            assertTrue(cache.current(before, other) { })
        }
        val fresh = cache.generation
        assertTrue(cache.put(scoped(ThumbnailUsage.Directory), byteArrayOf(3), fresh))
        assertTrue(cache.clear())
        ThumbnailUsage.entries.forEach { assertFalse(cache.put(scoped(it), byteArrayOf(4), fresh)) }
    }
    @Test fun partialSceneClearReportsRemainingSpaceWithoutRemovingOtherScenesOrOriginals() {
        val root = temp.newFolder()
        val cache = ThumbnailDiskCache(File(root, "thumbnails"), remove = { it.name != scoped(ThumbnailUsage.Menu, 2) && it.delete() })
        val original = File(root, "original.png").apply { writeText("original") }
        cache.put(scoped(ThumbnailUsage.Menu), ByteArray(10), cache.generation)
        cache.put(scoped(ThumbnailUsage.Menu, 2), ByteArray(20), cache.generation)
        cache.put(scoped(ThumbnailUsage.Information), ByteArray(30), cache.generation)
        assertFalse(cache.clear(ThumbnailUsage.Menu))
        assertEquals(20L, cache.sizes()[ThumbnailUsage.Menu])
        assertEquals(30L, cache.sizes()[ThumbnailUsage.Information])
        assertEquals("original", original.readText())
    }
    @Test fun allScenesShareOneDiskLimitAndLruOrder() {
        val dir = temp.newFolder(); val cache = ThumbnailDiskCache(dir, limit = 200)
        cache.put(scoped(ThumbnailUsage.Directory), ByteArray(100), cache.generation)
        File(dir, scoped(ThumbnailUsage.Directory)).setLastModified(1000)
        cache.put(scoped(ThumbnailUsage.Menu), ByteArray(100), cache.generation)
        File(dir, scoped(ThumbnailUsage.Menu)).setLastModified(2000)
        cache.read(scoped(ThumbnailUsage.Directory))
        cache.put(scoped(ThumbnailUsage.Information), ByteArray(100), cache.generation)
        assertNull(cache.read(scoped(ThumbnailUsage.Menu)))
        assertNotNull(cache.read(scoped(ThumbnailUsage.Directory)))
        assertNotNull(cache.read(scoped(ThumbnailUsage.Information)))
        assertEquals(200L, cache.bytes())
    }
}
