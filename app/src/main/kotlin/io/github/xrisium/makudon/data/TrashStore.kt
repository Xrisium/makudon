package io.github.xrisium.makudon.data

import io.github.xrisium.makudon.domain.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import java.util.UUID

data class TrashNode(val id: String, val entry: Entry, val parent: String?, val location: String, val deleted: Long)
data class TrashInventory(val nodes: List<TrashNode> = emptyList(), val bytes: Long = 0, val cleanupPending: Boolean = false) {
    val roots: List<TrashNode> get() = nodes.filter { it.parent == null }.sortedByDescending { it.deleted }
    fun descendants(id: String): Set<String> {
        val found = mutableSetOf(id)
        do { val size = found.size; nodes.forEach { if (it.parent in found) found += it.id } } while (size != found.size)
        return found
    }
    fun selection(ids: Collection<String>): List<TrashNode> {
        if (ids.isEmpty() || ids.any { id -> nodes.none { it.id == id } }) throw WorkspaceException(Failure.Missing)
        return nodes.filter { it.id in ids && nodes.none { other -> other.id in ids && other.id != it.id && it.id in descendants(other.id) } }
    }
}
data class TrashRestoreResult(val entries: List<Entry>, val locations: List<String>)

/** One IO mutex with LibraryController. A durable journal plus a catalog receipt resolves both-index commits. */
class TrashStore(
    directory: File,
    private val library: LibraryController,
    private val atomic: AtomicDisk = AtomicDisk(),
    private val cleanup: (File) -> Boolean = { it.deleteRecursively() },
) {
    private data class Source(val library: String, val id: String)
    private data class Pending(val token: String, val library: String, val deleting: Boolean, val nodes: List<String>, val documents: List<String>)
    private data class State(val nodes: List<TrashNode> = emptyList(), val garbage: List<String> = emptyList(), val sources: List<Source> = emptyList(), val pending: Pending? = null)
    private val directory = File(directory, "trash")
    private val index = File(this.directory, "index.properties")
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private var state: State? = null
    val ready: Boolean get() = state != null && state?.pending == null
    private fun material(id: String): File {
        if (!uuid.matches(id)) throw WorkspaceException(Failure.Corrupt)
        return File(directory, "materials/$id")
    }
    private fun current() = state ?: throw WorkspaceException(Failure.Corrupt)
    private fun encode(next: State): ByteArray {
        validate(next)
        val p = Properties()
        p["schema"] = "1"; p["count"] = next.nodes.size.toString()
        next.nodes.forEachIndexed { n, node ->
            val e = node.entry
            p["$n.id"] = node.id; p["$n.parent"] = node.parent.orEmpty(); p["$n.location"] = node.location; p["$n.deleted"] = node.deleted.toString()
            p["$n.original"] = e.id; p["$n.originalParent"] = e.parent.orEmpty(); p["$n.name"] = e.name; p["$n.kind"] = e.kind.name
            p["$n.created"] = e.created.toString(); p["$n.opened"] = e.opened.toString(); p["$n.modified"] = e.modified.toString(); p["$n.bytes"] = e.bytes.toString()
        }
        p["garbage"] = next.garbage.joinToString(",")
        p["sources"] = next.sources.joinToString(",") { "${it.library}/${it.id}" }
        next.pending?.let { pending ->
            p["pending.token"] = pending.token; p["pending.library"] = pending.library; p["pending.deleting"] = pending.deleting.toString()
            p["pending.nodes"] = pending.nodes.joinToString(","); p["pending.documents"] = pending.documents.joinToString(",")
        }
        val writer = StringWriter(); p.store(writer, "Makudon trash v1")
        val bytes = writer.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 8_388_608) throw WorkspaceException(Failure.CatalogFull)
        return bytes
    }
    private fun publish(next: State) {
        val bytes = encode(next)
        try { atomic.write(index, bytes); state = next } catch (e: Exception) { state = null; throw e }
    }
    private fun publishPending(next: State) {
        // Both journal outcomes must fit before the workspace receipt can be committed.
        encode(settled(next, true)); encode(settled(next, false))
        publish(next)
    }
    private fun validate(s: State) {
        if (s.nodes.size > 20_000 || s.garbage.size > 20_000 || s.sources.size > 20_000) throw WorkspaceException(Failure.CatalogFull)
        val byId = s.nodes.associateBy { it.id }
        if (byId.size != s.nodes.size) throw WorkspaceException(Failure.Corrupt)
        s.nodes.forEach { node ->
            material(node.id); TextRules.checkName(node.entry.name)
            if (!uuid.matches(node.entry.id) && node.entry.id != Workspace.LEGACY_INBOX) throw WorkspaceException(Failure.Corrupt)
            if (node.parent != null && byId[node.parent]?.entry?.folder != true) throw WorkspaceException(Failure.Corrupt)
            val seen = mutableSetOf(node.id); var parent = node.parent
            while (parent != null) { if (!seen.add(parent)) throw WorkspaceException(Failure.Corrupt); parent = byId[parent]?.parent }
        }
        s.garbage.forEach { material(it); if (it in byId) throw WorkspaceException(Failure.Corrupt) }
        s.sources.forEach { if (!uuid.matches(it.id) || (it.library != "legacy" && !uuid.matches(it.library))) throw WorkspaceException(Failure.Corrupt) }
        s.pending?.let {
            if (!uuid.matches(it.token) || (it.library != "legacy" && !uuid.matches(it.library)) || it.nodes.any { id -> id !in byId } || it.documents.any { id -> !uuid.matches(id) }) throw WorkspaceException(Failure.Corrupt)
        }
    }
    fun load(): TrashInventory {
        state = null
        if (!index.exists()) {
            if (directory.listFiles().orEmpty().any { it.name != "index.properties.new" }) throw WorkspaceException(Failure.Corrupt)
            publish(State())
        } else {
            val encoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(atomic.read(index, 8_388_608))).toString()
            val p = Properties().apply { load(StringReader(encoded)) }
            fun value(key: String) = p.getProperty(key) ?: throw WorkspaceException(Failure.Corrupt)
            fun list(key: String) = p.getProperty(key).orEmpty().split(',').filter { it.isNotEmpty() }
            if (value("schema") != "1") throw WorkspaceException(Failure.Corrupt)
            val count = value("count").toInt().takeIf { it in 0..20_000 } ?: throw WorkspaceException(Failure.Corrupt)
            val nodes = (0 until count).map { n ->
                val e = Entry(value("$n.original"), value("$n.name"), value("$n.originalParent").ifEmpty { null }, DocumentKind.valueOf(value("$n.kind")), value("$n.created").toLong(), value("$n.opened").toLong(), value("$n.modified").toLong(), value("$n.bytes").toInt())
                TrashNode(value("$n.id"), e, value("$n.parent").ifEmpty { null }, value("$n.location"), value("$n.deleted").toLong())
            }
            val pending = p.getProperty("pending.token")?.let { Pending(it, value("pending.library"), value("pending.deleting").toBooleanStrict(), list("pending.nodes"), list("pending.documents")) }
            val sources = list("sources").map { val parts = it.split('/'); if (parts.size != 2) throw WorkspaceException(Failure.Corrupt); Source(parts[0], parts[1]) }
            val next = State(nodes, list("garbage"), sources, pending)
            validate(next); state = next
        }
        reconcile()
        return inventory()
    }
    private fun reconcile() {
        val s = current(); val pending = s.pending ?: return
        if (library.activeId != pending.library) throw WorkspaceException(Failure.Corrupt)
        val committed = library.store.refresh().trashOperation == pending.token
        publish(settled(s, committed))
    }
    private fun settled(s: State, committed: Boolean): State {
        val pending = s.pending ?: throw WorkspaceException(Failure.Corrupt)
        val remove = if (committed != pending.deleting) pending.nodes.toSet() else emptySet()
        val garbage = (s.garbage + remove).distinct()
        val sources = if (committed == pending.deleting) s.sources + pending.documents.map { Source(pending.library, it) } else s.sources
        return s.copy(nodes = s.nodes.filter { it.id !in remove }, garbage = garbage, sources = sources.distinct(), pending = null)
    }
    fun inventory(): TrashInventory {
        if (current().pending != null) throw WorkspaceException(Failure.Corrupt)
        val bytes = File(directory, "materials").walkTopDown().filter { it.isFile }.sumOf { it.length() } + current().sources.sumOf { library.trashSourceBytes(it.library, it.id) }
        return TrashInventory(current().nodes, bytes, current().garbage.isNotEmpty() || current().sources.isNotEmpty())
    }
    fun retryCleanup(): TrashInventory {
        val s = current()
        if (s.pending != null) throw WorkspaceException(Failure.Corrupt)
        val garbage = s.garbage.filter { id -> !attempt { !material(id).exists() || cleanup(material(id)) } }
        val sources = s.sources.filter { source -> !attempt { library.cleanTrashSource(source.library, source.id) } }
        if (garbage != s.garbage || sources != s.sources) publish(s.copy(garbage = garbage, sources = sources))
        return inventory()
    }
    private fun attempt(action: () -> Boolean): Boolean = try { action() } catch (_: java.io.IOException) { false } catch (_: SecurityException) { false }
    fun read(id: String): String {
        val node = current().nodes.firstOrNull { it.id == id && !it.entry.folder } ?: throw WorkspaceException(Failure.Missing)
        return TextRules.decode(atomic.read(File(material(node.id), "body"), TextRules.MAX_BYTES))
    }
    fun delete(ids: Collection<String>): TrashInventory {
        val s = current(); if (s.pending != null) throw WorkspaceException(Failure.Corrupt)
        val workspace = library.store.refresh()
        if (ids.isEmpty() || ids.any { workspace.entry(it) == null } || Workspace.ROOT in ids) throw WorkspaceException(Failure.Missing)
        val roots = workspace.selectionRoots(ids)
        val removed = roots.flatMap { workspace.descendants(it.id) }.toSet()
        val mapping = removed.associateWith { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val nodes = workspace.entries.filter { it.id in removed }.map { e -> TrashNode(mapping.getValue(e.id), e, mapping[e.parent], workspace.path(e.parent ?: Workspace.ROOT), now) }
        // Register unpublished material for cleanup before writing any bytes.
        publish(s.copy(garbage = s.garbage + mapping.values))
        nodes.filter { !it.entry.folder }.forEach { node ->
            atomic.write(File(material(node.id), "body"), TextRules.bytes(library.store.read(node.entry.id)))
            library.store.previousBytes(node.entry.id)?.let { previous -> TextRules.decode(previous); atomic.write(File(material(node.id), "previous"), previous) }
        }
        val token = UUID.randomUUID().toString()
        val documents = nodes.filter { !it.entry.folder }.map { it.entry.id }
        publishPending(s.copy(nodes = s.nodes + nodes, pending = Pending(token, library.activeId, true, nodes.map { it.id }, documents)))
        try { library.store.removeToTrash(roots.map { it.id }, token) } catch (e: Exception) { state = null; throw e }
        reconcile()
        return retryCleanup()
    }
    fun needsRoot(ids: Collection<String>): Boolean {
        val workspace = library.store.refresh()
        return inventory().selection(ids).any { workspace.entry(it.entry.parent.orEmpty())?.folder != true }
    }
    fun restore(ids: Collection<String>, allowRoot: Boolean, restoredSuffix: (Int) -> String): TrashRestoreResult {
        val s = current(); val inventory = inventory(); val roots = inventory.selection(ids)
        val workspace = library.store.refresh()
        if (!allowRoot && needsRoot(ids)) throw WorkspaceException(Failure.Missing)
        val selected = roots.flatMap { inventory.descendants(it.id) }.toSet()
        val nodes = s.nodes.filter { it.id in selected }
        val used = workspace.entries.map { it.id }.toMutableSet()
        val mapping = nodes.associate { node ->
            val original = node.entry.id
            val id = if (uuid.matches(original) && original !in used && !library.store.hasMaterial(original)) original else UUID.randomUUID().toString()
            used += id; node.id to id
        }
        val additions = mutableListOf<Entry>()
        fun append(node: TrashNode) {
            val isRoot = node in roots
            val parent = if (isRoot) node.entry.parent?.takeIf { workspace.entry(it)?.folder == true } ?: Workspace.ROOT else mapping[node.parent] ?: throw WorkspaceException(Failure.Corrupt)
            var name = node.entry.name; var n = 0
            while (TextRules.conflicts(workspace.entries + additions, name, parent, node.entry.kind)) {
                val suffix = restoredSuffix(++n)
                var base = node.entry.name.take(120 - suffix.length)
                if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
                name = base + suffix
            }
            additions += node.entry.copy(id = mapping.getValue(node.id), name = name, parent = parent, importRequestId = null)
            nodes.filter { it.parent == node.id }.forEach(::append)
        }
        roots.forEach(::append)
        val token = UUID.randomUUID().toString()
        publishPending(s.copy(pending = Pending(token, library.activeId, false, nodes.map { it.id }, additions.filter { !it.folder }.map { it.id })))
        val sources = nodes.associateBy { mapping.getValue(it.id) }
        try {
            library.store.restoreFromTrash(additions, token) { e ->
                val source = material(sources.getValue(e.id).id)
                val previous = File(source, "previous")
                atomic.read(File(source, "body"), TextRules.MAX_BYTES) to if (previous.exists()) atomic.read(previous, TextRules.MAX_BYTES) else null
            }
        } catch (e: Exception) { state = null; throw e }
        reconcile(); retryCleanup()
        val next = library.store.refresh()
        return TrashRestoreResult(roots.map { root -> additions.first { it.id == mapping[root.id] } }, roots.map { root -> next.path(additions.first { it.id == mapping[root.id] }.parent ?: Workspace.ROOT) })
    }
    fun purge(ids: Collection<String>): TrashInventory {
        val s = current(); val inventory = inventory()
        val removed = inventory.selection(ids).flatMap { inventory.descendants(it.id) }.toSet()
        publish(s.copy(nodes = s.nodes.filter { it.id !in removed }, garbage = (s.garbage + removed).distinct()))
        return retryCleanup()
    }
}
