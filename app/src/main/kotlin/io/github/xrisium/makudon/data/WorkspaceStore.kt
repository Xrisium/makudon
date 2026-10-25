package io.github.xrisium.makudon.data

import io.github.xrisium.makudon.domain.*
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** The caller serializes all access on its IO dispatcher. Paths come exclusively from validated IDs. */
class WorkspaceStore(private val directory: File, private val atomic: AtomicDisk = AtomicDisk(), private val rootName: String = "Library") {
    private val catalog = File(directory, "catalog.properties")
    private var workspace: Workspace? = null
    private var baselineId: String? = null
    private var confirmedBody: ByteArray? = null
    private var uncertainBody: ByteArray? = null
    private val idPattern = Regex("[a-f0-9-]{36}|root|inbox")
    private fun nodeDirectory(id: String): File {
        if (!idPattern.matches(id) || id in setOf(Workspace.ROOT, Workspace.LEGACY_INBOX)) throw WorkspaceException(Failure.Corrupt)
        return File(directory, "documents/$id")
    }
    private fun body(entry: Entry) = File(nodeDirectory(entry.id), "body.${entry.kind.extension}")

    fun load(): Workspace {
        workspace = null
        directory.mkdirs()
        if (!catalog.exists()) {
            if (directory.listFiles().orEmpty().any { it.name != "catalog.properties.new" }) throw WorkspaceException(Failure.Corrupt)
            val now = System.currentTimeMillis()
            publish(Workspace(listOf(Entry(Workspace.ROOT, rootName, null, DocumentKind.Folder, now))))
        } else {
            val encoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(atomic.read(catalog, 4_194_304))).toString()
            val p = Properties().apply { load(StringReader(encoded)) }
            if (p.getProperty("schema") != "1") throw WorkspaceException(Failure.Corrupt)
            val count = p.getProperty("count")?.toIntOrNull()?.takeIf { it in 1..10_000 } ?: throw WorkspaceException(Failure.Corrupt)
            val entries = (0 until count).map { n ->
                fun value(key: String) = p.getProperty("$n.$key") ?: throw WorkspaceException(Failure.Corrupt)
                Entry(value("id"), value("name"), value("parent").ifEmpty { null }, DocumentKind.valueOf(value("kind")), value("created").toLong(), value("opened").toLong(), importRequestId = p.getProperty("$n.importRequest"))
            }
            val candidate = Workspace(entries, ColorTheme.valueOf(p.getProperty("theme")), SortKey.valueOf(p.getProperty("sort")), p.getProperty("descending").toBooleanStrict(), p.getProperty("foldersFirst").toBooleanStrict(), p.getProperty("lastImportRequest"))
            validate(candidate)
            workspace = candidate
        }
        return refresh()
    }

    private fun validate(state: Workspace) {
        if (state.entries.map { it.id }.distinct().size != state.entries.size) throw WorkspaceException(Failure.Corrupt)
        if (state.entries.count { it.parent == null } != 1 || state.entry(Workspace.ROOT)?.let { !it.folder || it.parent != null } != false) throw WorkspaceException(Failure.Corrupt)
        state.entries.forEach { node ->
            if (!idPattern.matches(node.id)) throw WorkspaceException(Failure.Corrupt)
            TextRules.checkName(node.name)
            if (!node.folder && node.id in setOf(Workspace.ROOT, Workspace.LEGACY_INBOX)) throw WorkspaceException(Failure.Corrupt)
            if (node.id != Workspace.ROOT && state.entry(node.parent.orEmpty())?.folder != true) throw WorkspaceException(Failure.Corrupt)
            var ancestor = node.parent
            val visited = mutableSetOf(node.id)
            while (ancestor != null) {
                if (!visited.add(ancestor)) throw WorkspaceException(Failure.Corrupt)
                ancestor = state.entry(ancestor)?.parent
            }
            if (node.parent != null && TextRules.conflicts(state.entries, node.name, node.parent, node.kind, node.id)) throw WorkspaceException(Failure.Corrupt)
        }
    }

    private fun publish(next: Workspace) {
        validate(next)
        val p = Properties()
        p["schema"] = "1"; p["count"] = next.entries.size.toString(); p["theme"] = next.theme.name
        p["sort"] = next.sort.name; p["descending"] = next.descending.toString(); p["foldersFirst"] = next.foldersFirst.toString()
        next.lastImportRequestId?.let { p["lastImportRequest"] = it }
        next.entries.forEachIndexed { n, entry ->
            p["$n.id"] = entry.id; p["$n.name"] = entry.name; p["$n.parent"] = entry.parent.orEmpty()
            p["$n.kind"] = entry.kind.name; p["$n.created"] = entry.created.toString(); p["$n.opened"] = entry.opened.toString()
            entry.importRequestId?.let { p["$n.importRequest"] = it }
        }
        val writer = StringWriter(); p.store(writer, "Makudon workspace v1")
        try { atomic.write(catalog, writer.toString().toByteArray(Charsets.UTF_8)) }
        catch (e: Exception) { workspace = null; throw e }
        workspace = next
    }

    private fun state() = workspace ?: throw WorkspaceException(Failure.Corrupt)
    private fun find(id: String) = state().entry(id) ?: throw WorkspaceException(Failure.Missing)
    private fun parent(id: String) { if (!find(id).folder) throw WorkspaceException(Failure.Missing) }
    fun refresh(): Workspace {
        val next = state().copy(entries = state().entries.map { e ->
            if (e.folder) e else try {
                val text = read(e.id)
                val excerpt = text.replace(Regex("(?m)^#{1,6} "), "").replace(Regex("\\s+"), " ").trim().take(220).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                e.copy(bytes = TextRules.bytes(text).size, modified = body(e).lastModified(), excerpt = excerpt, unreadable = false)
            } catch (_: java.io.IOException) { e.copy(unreadable = true) }
            catch (_: WorkspaceException) { e.copy(unreadable = true) }
        })
        val dated = next.copy(entries = next.entries.map { e ->
            if (!e.folder) e else {
                val descendants = next.descendants(e.id)
                e.copy(modified = next.entries.filter { it.id in descendants }.maxOf { it.modified })
            }
        })
        workspace = dated
        return dated
    }
    fun read(id: String): String { val entry = find(id); if (entry.folder) throw WorkspaceException(Failure.Missing); return TextRules.decode(atomic.read(body(entry), TextRules.MAX_BYTES)) }
    fun previous(id: String): String {
        val file = File(nodeDirectory(id), "previous.txt")
        if (!file.isFile) throw WorkspaceException(Failure.NoPrevious)
        return TextRules.decode(atomic.read(file, TextRules.MAX_BYTES))
    }
    fun save(id: String, text: String) {
        val entry = find(id)
        val target = TextRules.bytes(text)
        val file = body(entry)
        val baseline = atomic.read(file, TextRules.MAX_BYTES)
        TextRules.decode(baseline)
        if (baselineId == id && confirmedBody?.contentEquals(baseline) == false && uncertainBody?.contentEquals(baseline) != true) throw WorkspaceException(Failure.Corrupt)
        baselineId = id; confirmedBody = baseline; uncertainBody = null
        if (baseline.contentEquals(target)) return
        atomic.write(File(nodeDirectory(id), "previous.txt"), baseline)
        uncertainBody = target
        atomic.write(file, target)
        confirmedBody = target; uncertainBody = null
    }
    fun open(id: String): String {
        val text = read(id)
        baselineId = id; confirmedBody = TextRules.bytes(text); uncertainBody = null
        publish(state().copy(entries = state().entries.map { if (it.id == id) it.copy(opened = System.currentTimeMillis()) else it }))
        return text
    }
    private fun availableName(name: String, parent: String, kind: DocumentKind, entries: List<Entry> = state().entries): String {
        if (!TextRules.conflicts(entries, name, parent, kind)) return name
        var n = 1
        while (true) {
            val suffix = if (n == 1) "（副本）" else "（副本 $n）"
            val candidate = name.take(120 - suffix.length) + suffix
            if (!TextRules.conflicts(entries, candidate, parent, kind)) return candidate
            n++
        }
    }
    fun create(name: String, parent: String, kind: DocumentKind, text: String = "", importRequestId: String? = null): String {
        if (importRequestId != null) state().entries.firstOrNull { it.importRequestId == importRequestId }?.let { return it.id }
        if (importRequestId != null && state().lastImportRequestId == importRequestId) throw WorkspaceException(Failure.Missing)
        parent(parent); TextRules.checkName(name)
        if (kind == DocumentKind.Folder && TextRules.conflicts(state().entries, name, parent, kind)) throw WorkspaceException(Failure.Conflict)
        val entry = Entry(UUID.randomUUID().toString(), availableName(name, parent, kind), parent, kind, System.currentTimeMillis(), if (kind == DocumentKind.Folder) 0 else System.currentTimeMillis(), importRequestId = importRequestId)
        if (!entry.folder) atomic.write(body(entry), TextRules.bytes(text))
        publish(state().copy(entries = state().entries + entry, lastImportRequestId = importRequestId ?: state().lastImportRequestId))
        return entry.id
    }
    fun rename(id: String, name: String) {
        val entry = find(id); TextRules.checkName(name)
        val parent = entry.parent ?: throw WorkspaceException(Failure.Missing)
        if (TextRules.conflicts(state().entries, name, parent, entry.kind, id)) throw WorkspaceException(Failure.Conflict)
        publish(state().copy(entries = state().entries.map { if (it.id == id) it.copy(name = name) else it }))
    }
    fun move(id: String, destination: String) {
        parent(destination)
        val entry = find(id)
        if (id == Workspace.ROOT) throw WorkspaceException(Failure.Cycle)
        if (entry.folder && destination in state().descendants(id)) throw WorkspaceException(Failure.Cycle)
        if (TextRules.conflicts(state().entries, entry.name, destination, entry.kind, id)) throw WorkspaceException(Failure.Conflict)
        publish(state().copy(entries = state().entries.map { if (it.id == id) it.copy(parent = destination) else it }))
    }
    data class Copy(val entry: Entry, val text: String?, val children: List<Copy>)
    fun capture(id: String): Copy {
        val item = find(id)
        if (id == Workspace.ROOT) throw WorkspaceException(Failure.Cycle)
        return Copy(item, if (item.folder) null else read(id), state().entries.filter { it.parent == id }.map { capture(it.id) })
    }
    fun paste(snapshot: Copy, destination: String) {
        parent(destination)
        if (snapshot.entry.folder && destination in state().descendants(snapshot.entry.id)) throw WorkspaceException(Failure.Cycle)
        val additions = mutableListOf<Entry>()
        fun clone(copy: Copy, target: String, name: String) {
            val id = UUID.randomUUID().toString()
            val entry = copy.entry.copy(id = id, name = name, parent = target, created = System.currentTimeMillis(), opened = System.currentTimeMillis(), importRequestId = null)
            if (!entry.folder) atomic.write(body(entry), TextRules.bytes(copy.text ?: throw WorkspaceException(Failure.Corrupt)))
            additions += entry
            copy.children.forEach { clone(it, id, it.entry.name) }
        }
        clone(snapshot, destination, availableName(snapshot.entry.name, destination, snapshot.entry.kind))
        publish(state().copy(entries = state().entries + additions))
    }
    fun delete(id: String) {
        if (id == Workspace.ROOT) throw WorkspaceException(Failure.Missing)
        find(id)
        val removed = state().descendants(id)
        val documents = state().entries.filter { it.id in removed && !it.folder }
        publish(state().copy(entries = state().entries.filter { it.id !in removed }))
        // Publication happens before cleanup. An interrupted cleanup can never resurrect a deleted entry.
        if (documents.any { !nodeDirectory(it.id).deleteRecursively() }) throw WorkspaceException(Failure.Cleanup)
    }
    fun settings(theme: ColorTheme, sort: SortKey, descending: Boolean, foldersFirst: Boolean) {
        publish(state().copy(theme = theme, sort = sort, descending = descending, foldersFirst = foldersFirst))
    }
    fun restore(id: String): String {
        val entry = find(id)
        val old = previous(id)
        val source = nodeDirectory(id)
        val material = File(directory, "recovery/${UUID.randomUUID()}")
        if (!source.copyRecursively(material)) throw WorkspaceException(Failure.Storage)
        source.listFiles().orEmpty().filter { it.isFile }.forEach { original ->
            val copy = File(material, original.name)
            FileOutputStream(copy, true).use { it.fd.sync() }
            if (!original.readBytes().contentEquals(copy.readBytes())) throw WorkspaceException(Failure.Storage)
        }
        atomic.write(body(entry), TextRules.bytes(old))
        baselineId = id; confirmedBody = TextRules.bytes(old); uncertainBody = null
        return old
    }
}

/** Checked sync + atomic rename + exact readback; no successful acknowledgement on an uncertain result. */
open class AtomicDisk {
    open fun read(file: File, limit: Int): ByteArray = file.inputStream().use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > limit) throw WorkspaceException(Failure.TooLarge)
        }
        output.toByteArray()
    }
    open fun write(file: File, bytes: ByteArray) {
        if (file.parentFile?.let { it.isDirectory || it.mkdirs() } != true) throw WorkspaceException(Failure.Storage)
        val pending = File(file.parentFile, file.name + ".new")
        FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
        if (!read(pending, bytes.size).contentEquals(bytes)) throw WorkspaceException(Failure.Storage)
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (!read(file, bytes.size).contentEquals(bytes)) throw WorkspaceException(Failure.Storage)
    }
}
