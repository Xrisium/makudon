package io.github.xrisium.makudon.data

import java.io.File

/** Disposable derivatives only. The generation fences writes started before a user clear. */
class ThumbnailDiskCache(
    private val directory: File,
    private val limit: Long = 64L * 1024 * 1024,
    private val remove: (File) -> Boolean = { it.delete() },
) {
    private val lock = Any()
    private var epoch = 0L
    val generation: Long get() = synchronized(lock) { epoch }
    private fun file(key: String): File {
        require(Regex("[a-f0-9]{64}-(128|512|1024)-v1\\.png").matches(key))
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
        if (generation != epoch || !allowed() || bytes.size > limit) return@synchronized false
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
    fun current(generation: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (epoch != generation) false else { action(); true }
    }
    fun bytes(): Long = synchronized(lock) { bytesLocked() }
    private fun bytesLocked(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    fun clear(action: () -> Unit = {}): Boolean = synchronized(lock) {
        epoch++; action()
        var complete = true
        directory.listFiles().orEmpty().filter { it.isFile }.forEach {
            try { if (!remove(it)) complete = false } catch (_: SecurityException) { complete = false }
        }
        complete
    }
}
