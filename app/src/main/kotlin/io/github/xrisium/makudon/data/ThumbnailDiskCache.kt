package io.github.xrisium.makudon.data

import java.io.File
import io.github.xrisium.makudon.domain.ThumbnailUsage

object ThumbnailKeys {
    private val legacy = Regex("[a-f0-9]{64}-(128|512|1024)-v1\\.png")
    private val scoped = Regex("[a-f0-9]{64}-(128|512|1024)-v2-(Directory|Menu|Information)\\.png")
    fun key(digest: String, size: Int, usage: ThumbnailUsage): String =
        if (usage == ThumbnailUsage.Content) "$digest-$size-v1.png" else "$digest-$size-v2-${usage.name}.png"
    fun usage(key: String): ThumbnailUsage? = if (legacy.matches(key)) ThumbnailUsage.Content
        else scoped.matchEntire(key)?.groupValues?.get(2)?.let(ThumbnailUsage::valueOf)
}

data class ThumbnailInventory(val sizes: Map<ThumbnailUsage, Long>, val populated: Set<ThumbnailUsage>) {
    val bytes: Long get() = sizes.values.sum()
}

/** Disposable derivatives only. The generation fences writes started before a user clear. */
class ThumbnailDiskCache(
    private val directory: File,
    private val limit: Long = 64L * 1024 * 1024,
    private val remove: (File) -> Boolean = { it.delete() },
) {
    private val lock = Any()
    private var epoch = 0L
    private var cleared = 0L
    private val clearedScopes = mutableMapOf<ThumbnailUsage, Long>()
    val generation: Long get() = synchronized(lock) { epoch }
    private fun file(key: String): File {
        require(ThumbnailKeys.usage(key) != null)
        return File(directory, key)
    }
    fun read(key: String): ByteArray? = synchronized(lock) {
        val target = file(key)
        if (!target.isFile) return@synchronized null
        try {
            AtomicDisk().read(target, 8 * 1024 * 1024).also { target.setLastModified(System.currentTimeMillis()) }
        } catch (_: java.io.IOException) { remove(target); null }
        catch (_: io.github.xrisium.makudon.domain.WorkspaceException) { remove(target); null }
    }
    fun put(key: String, bytes: ByteArray, generation: Long, allowed: () -> Boolean = { true }): Boolean = synchronized(lock) {
        if (!currentLocked(generation, checkNotNull(ThumbnailKeys.usage(key))) || !allowed() || bytes.size > limit) return@synchronized false
        try {
            AtomicDisk().write(file(key), bytes)
            var total = bytesLocked()
            for (old in directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }) {
                if (total <= limit) break
                val size = old.length(); if (remove(old)) total -= size
            }
            total <= limit
        } catch (_: java.io.IOException) { false }
        catch (_: SecurityException) { false }
        catch (_: io.github.xrisium.makudon.domain.WorkspaceException) { false }
    }
    private fun currentLocked(generation: Long, usage: ThumbnailUsage) =
        generation in maxOf(cleared, clearedScopes[usage] ?: 0L)..epoch
    fun current(generation: Long, usage: ThumbnailUsage = ThumbnailUsage.Content, action: () -> Unit): Boolean = synchronized(lock) {
        if (!currentLocked(generation, usage)) false else { action(); true }
    }
    fun bytes(): Long = synchronized(lock) { bytesLocked() }
    fun sizes(): Map<ThumbnailUsage, Long> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isFile }.groupBy { ThumbnailKeys.usage(it.name) ?: ThumbnailUsage.Content }
            .mapValues { (_, files) -> files.sumOf { it.length() } }
    }
    private fun bytesLocked(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    fun clear(usage: ThumbnailUsage? = null, action: () -> Unit = {}): Boolean = synchronized(lock) {
        epoch++
        if (usage == null) cleared = epoch else clearedScopes[usage] = epoch
        action()
        var complete = true
        directory.listFiles().orEmpty().filter { it.isFile && (usage == null || ThumbnailKeys.usage(it.name) == usage) }.forEach {
            try { if (!remove(it)) complete = false } catch (_: SecurityException) { complete = false }
        }
        complete
    }
}
