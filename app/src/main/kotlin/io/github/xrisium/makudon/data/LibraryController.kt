package io.github.xrisium.makudon.data

import io.github.xrisium.makudon.domain.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class RestoreSnapshot(val time: Long, val documents: Int, val bytes: Long, val retired: Int)
data class CandidateLibrary(val id: String, val baseline: String, val fingerprint: String)

/** The caller serializes this controller and its active store with the same IO mutex. */
class LibraryController(
    private val directory: File,
    private val rootName: String = "Library",
    private val atomic: AtomicDisk = AtomicDisk(),
    private val available: () -> Long = { localFreeSpace(directory) },
    private val cleanup: (File) -> Boolean = { it.deleteRecursively() },
) {
    private data class Pointer(val active: String, val previous: String? = null, val time: Long = 0, val retired: List<String> = emptyList())
    private val pointerFile = File(directory, "library-state.properties")
    private var pointer: Pointer? = null
    private var activeStore: WorkspaceStore? = null
    val store: WorkspaceStore get() = activeStore ?: throw WorkspaceException(Failure.Corrupt)

    private fun path(id: String): File = when {
        id == "legacy" -> File(directory, "workspace")
        Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id) -> File(directory, "library-generations/$id")
        else -> throw WorkspaceException(Failure.Corrupt)
    }
    private fun makeStore(id: String) = WorkspaceStore(path(id), atomic, rootName)

    fun load(): Workspace {
        val existingStore = activeStore
        val existingId = pointer?.active
        activeStore = null; pointer = null
        // A missing established pointer must not silently select a stale legacy snapshot.
        if (!pointerFile.exists() && File(directory, "library-generations").listFiles().orEmpty().isNotEmpty()) throw WorkspaceException(Failure.Corrupt)
        val next = if (pointerFile.exists()) {
            val properties = Properties().apply { load(StringReader(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(atomic.read(pointerFile, 65_536))).toString())) }
            if (properties.getProperty("schema") != "1") throw WorkspaceException(Failure.Corrupt)
            Pointer(properties.getProperty("active") ?: throw WorkspaceException(Failure.Corrupt),
                properties.getProperty("previous"), properties.getProperty("time")?.toLongOrNull() ?: throw WorkspaceException(Failure.Corrupt),
                properties.getProperty("retired").orEmpty().split(',').filter { it.isNotEmpty() })
        } else Pointer("legacy")
        validate(next)
        if (pointerFile.exists() && !File(path(next.active), "catalog.properties").isFile) throw WorkspaceException(Failure.Corrupt)
        val candidate = if (existingId == next.active && existingStore != null) existingStore else makeStore(next.active)
        val workspace = candidate.load()
        // Establish authority before any generation can be prepared; no legacy body is moved.
        if (!pointerFile.exists()) publish(next)
        pointer = next; activeStore = candidate
        return workspace
    }

    private fun validate(p: Pointer) {
        path(p.active)
        p.previous?.let { path(it); if (it == p.active) throw WorkspaceException(Failure.Corrupt) }
        if (p.retired.size > 128 || p.retired.distinct().size != p.retired.size) throw WorkspaceException(Failure.Corrupt)
        p.retired.forEach { if (it == p.active || it == p.previous) throw WorkspaceException(Failure.Corrupt); path(it) }
    }
    private fun publish(next: Pointer) {
        validate(next)
        val properties = Properties().apply {
            setProperty("schema", "1"); setProperty("active", next.active); setProperty("time", next.time.toString())
            next.previous?.let { setProperty("previous", it) }
            setProperty("retired", next.retired.joinToString(","))
        }
        val writer = StringWriter(); properties.store(writer, "Makudon library pointer")
        // Even a post-rename readback error invalidates every cached writable handle.
        try { atomic.write(pointerFile, writer.toString().toByteArray(Charsets.UTF_8)) }
        catch (e: Exception) { pointer = null; activeStore = null; throw e }
        pointer = next
    }

    fun fingerprint(check: () -> Unit = {}): String {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(atomic.read(File(path(p.active), "catalog.properties"), 4_194_304))
        store.refresh().entries.filter { !it.folder }.sortedBy { it.id }.forEach {
            check()
            digest.update(it.id.toByteArray(Charsets.UTF_8))
            digest.update(TextRules.bytes(store.read(it.id)))
            digest.update(it.modified.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun prepare(plan: RestorePlan, archive: PreparedArchive, expected: String, check: () -> Unit = {}): CandidateLibrary {
        if (!plan.resolved) throw WorkspaceException(Failure.Conflict)
        if (!plan.changed) throw WorkspaceException(Failure.NoChange)
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        if (fingerprint(check) != expected) throw WorkspaceException(Failure.StaleRestore)
        var required = 8L * 1024 * 1024
        plan.workspace.entries.filter { !it.folder }.forEach {
            required += it.bytes
            if (it.id !in plan.sources) required += store.previousBytes(it.id)?.size ?: 0
        }
        if (available() < required) throw WorkspaceException(Failure.Space)
        val id = UUID.randomUUID().toString()
        val candidate = makeStore(id)
        try {
            candidate.prepare(plan.workspace, { entry ->
                val incoming = plan.sources[entry.id]
                if (incoming != null) TextRules.bytes(archive.text(incoming)) to null
                else TextRules.bytes(store.read(entry.id)) to store.previousBytes(entry.id)
            }, check)
            check()
            if (candidate.load().entries.any { it.unreadable }) throw WorkspaceException(Failure.Corrupt)
        } catch (e: Exception) {
            // This fresh generation has never been named by the pointer.
            if (path(id).exists() && !cleanup(path(id))) e.addSuppressed(WorkspaceException(Failure.Cleanup))
            throw e
        }
        return CandidateLibrary(id, p.active, expected)
    }

    fun abandon(candidate: CandidateLibrary) {
        val p = pointer ?: return // Uncertain publication: never guess whether the candidate is live.
        if (candidate.id == p.active || candidate.id == p.previous || candidate.id in p.retired) return
        if (path(candidate.id).exists() && !cleanup(path(candidate.id))) throw WorkspaceException(Failure.Cleanup)
    }

    /** No cancellation from this point until the new pointer is read back and the handle switched. */
    fun commit(candidate: CandidateLibrary) {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        if (p.active != candidate.baseline || fingerprint() != candidate.fingerprint) throw WorkspaceException(Failure.StaleRestore)
        val nextStore = makeStore(candidate.id)
        if (!File(path(candidate.id), "catalog.properties").isFile || nextStore.load().entries.any { it.unreadable }) throw WorkspaceException(Failure.Corrupt)
        val retired = (p.retired + listOfNotNull(p.previous)).distinct()
        publish(Pointer(candidate.id, p.active, System.currentTimeMillis(), retired))
        activeStore = nextStore
        cleanRetired()
    }

    fun snapshot(): RestoreSnapshot? {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        val previous = p.previous ?: return if (p.retired.isEmpty()) null else RestoreSnapshot(0, 0, 0, p.retired.size)
        val folder = path(previous)
        if (!File(folder, "catalog.properties").isFile) throw WorkspaceException(Failure.Corrupt)
        val workspace = makeStore(previous).load()
        return RestoreSnapshot(p.time, workspace.entries.count { !it.folder }, folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }, p.retired.size)
    }

    fun undo() {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        val previous = p.previous ?: throw WorkspaceException(Failure.NoSnapshot)
        if (!File(path(previous), "catalog.properties").isFile) throw WorkspaceException(Failure.Corrupt)
        val restored = makeStore(previous)
        if (restored.load().entries.any { it.unreadable }) throw WorkspaceException(Failure.Corrupt)
        publish(Pointer(previous, p.active, System.currentTimeMillis(), p.retired))
        activeStore = restored
    }

    fun clearSnapshot() {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        publish(p.copy(previous = null, time = 0, retired = (p.retired + listOfNotNull(p.previous)).distinct()))
        cleanRetired()
    }

    private fun cleanRetired() {
        val p = pointer ?: throw WorkspaceException(Failure.Corrupt)
        val remaining = p.retired.filter { id ->
            val target = path(id)
            try { target.exists() && !cleanup(target) }
            catch (_: java.io.IOException) { true }
            catch (_: SecurityException) { true }
        }
        if (remaining != p.retired) publish(p.copy(retired = remaining))
        if (remaining.isNotEmpty()) throw WorkspaceException(Failure.Cleanup)
    }
}

// JVM tests use this fallback; Android injects StorageManager.getAllocatableBytes.
@Suppress("UsableSpace")
private fun localFreeSpace(directory: File): Long = directory.usableSpace
