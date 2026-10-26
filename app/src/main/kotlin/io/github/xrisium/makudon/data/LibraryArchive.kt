package io.github.xrisium.makudon.data

import io.github.xrisium.makudon.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PreparedArchive(val directory: File, val inventory: ArchiveInventory) {
    fun body(id: String): File {
        if (!Regex("[a-f0-9-]{36}").matches(id) || inventory.nodes.none { it.id == id && it.kind != DocumentKind.Folder }) {
            throw WorkspaceException(Failure.InvalidArchive)
        }
        return File(directory, "$id.txt")
    }
    fun text(id: String): String = TextRules.decode(AtomicDisk().read(body(id), TextRules.MAX_BYTES))
}
data class ExportItem(val entry: Entry, val path: String)
data class ArchiveExport(val items: List<ExportItem>, val renames: List<ArchiveRename>)

/** All materialized paths use generated IDs. ZIP names are only logical catalog input. */
object LibraryArchive {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_DEPTH = 128

    fun copySource(input: InputStream, target: File, check: () -> Unit = {}) {
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(32 * 1024)
            var size = 0L
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit)
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
    }

    fun read(file: File, destination: File, check: () -> Unit = {}, allowEmpty: Boolean = false, imported: Boolean = true): PreparedArchive {
        if (file.length() > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit)
        checkDirectory(file)
        if (!destination.mkdirs() && !destination.isDirectory) throw WorkspaceException(Failure.Storage)
        val nodes = linkedMapOf<String, ArchiveNode>()
        val explicit = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        var readingPath: String? = null
        var expanded = 0L
        fun folder(path: String, modified: Long): String {
            var parent: String? = null
            var current = ""
            path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                TextRules.checkName(segment)
                current = if (current.isEmpty()) segment else "$current/$segment"
                val existing = nodes[current]
                if (existing != null && existing.kind != DocumentKind.Folder) throw WorkspaceException(Failure.InvalidArchive)
                val node = existing ?: ArchiveNode(UUID.randomUUID().toString(), segment, parent, DocumentKind.Folder, modified, sourcePath = current)
                    .also { nodes[current] = it }
                parent = node.id
            }
            return parent ?: throw WorkspaceException(Failure.InvalidArchive)
        }
        try {
            ZipFile(file, Charsets.UTF_8).use { zip ->
                if (zip.size() > MAX_ENTRIES) throw WorkspaceException(Failure.ArchiveLimit)
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    check()
                    val entry = enumeration.nextElement()
                    readingPath = entry.name
                    val path = checkedPath(entry.name, entry.isDirectory)
                    if (!explicit.add(path)) throw WorkspaceException(Failure.InvalidArchive)
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) throw WorkspaceException(Failure.InvalidArchive)
                    if (entry.size > MAX_BYTES - expanded) throw WorkspaceException(Failure.ArchiveLimit)
                    val modified = entry.time.takeIf { it > 0 && it <= 253_402_300_799_000L } ?: System.currentTimeMillis()
                    val kind = when {
                        entry.isDirectory -> DocumentKind.Folder
                        path.endsWith(".md", true) -> DocumentKind.Markdown
                        path.endsWith(".txt", true) -> DocumentKind.Plain
                        else -> null
                    }
                    val crc = CRC32()
                    var size = 0L
                    val content = if (kind != null && kind != DocumentKind.Folder) ByteArrayOutputStream() else null
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            check()
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n; expanded += n
                            if (expanded > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit)
                            if (content != null && size > TextRules.MAX_BYTES) throw WorkspaceException(Failure.TooLarge)
                            crc.update(buffer, 0, n)
                            content?.write(buffer, 0, n)
                        }
                    }
                    if (entry.crc != crc.value || entry.size != size || (entry.isDirectory && size != 0L)) throw WorkspaceException(Failure.InvalidArchive)
                    // Structural clashes are invalid even when the file would otherwise be skipped.
                    if (!entry.isDirectory && (nodes.containsKey(path) || explicit.any { it.startsWith("$path/") })) throw WorkspaceException(Failure.InvalidArchive)
                    val parentPath = path.substringBeforeLast('/', "")
                    if (kind == null) {
                        if (parentPath.isNotEmpty()) folder(parentPath, modified)
                        nodes[path] = ArchiveNode(UUID.randomUUID().toString(), path.substringAfterLast('/'), null, DocumentKind.Plain, modified, sourcePath = path)
                        skipped += path
                    } else if (kind == DocumentKind.Folder) {
                        folder(path, modified)
                    } else {
                        val name = path.substringAfterLast('/').dropLast(kind.extension.length + 1)
                        TextRules.checkName(name)
                        val parent = if (parentPath.isEmpty()) null else folder(parentPath, modified)
                        val text = TextRules.decode(checkNotNull(content).toByteArray(), imported = imported)
                        val bytes = TextRules.bytes(text)
                        val node = ArchiveNode(UUID.randomUUID().toString(), name, parent, kind, modified, bytes.size, path)
                        nodes[path] = node
                        AtomicDisk().write(File(destination, "${node.id}.txt"), bytes)
                    }
                }
            }
        } catch (e: WorkspaceException) { throw WorkspaceException(e.reason, readingPath ?: e.detail) }
        catch (e: ZipException) { throw WorkspaceException(Failure.InvalidArchive, readingPath) }
        catch (e: IllegalArgumentException) { throw WorkspaceException(Failure.InvalidArchive) }
        val supported = nodes.values.filter { it.sourcePath !in skipped }
        if (supported.size >= 10_000) throw WorkspaceException(Failure.CatalogFull)
        if (!allowEmpty && supported.none { it.kind != DocumentKind.Folder }) throw WorkspaceException(Failure.EmptyArchive)
        val renamed = mutableListOf<ArchiveRename>()
        val normalized = mutableListOf<ArchiveNode>()
        // Parent-first order also handles explicit directory records appearing after their children.
        supported.sortedWith(compareBy<ArchiveNode> { it.sourcePath.count { c -> c == '/' } }.thenBy { it.sourcePath }).forEach { node ->
            var name = node.name
            var n = 1
            while (normalized.any { it.parent == node.parent && it.kind == node.kind && it.name.equals(name, true) }) {
                val suffix = if (n == 1) "（恢复副本）" else "（恢复副本 $n）"
                name = safeNamePrefix(node.name, 120 - suffix.length) + suffix
                n++
            }
            val item = node.copy(name = name)
            normalized += item
        }
        val paths = mutableMapOf<String?, String>(null to "")
        normalized.forEach { node ->
            val segment = node.name + if (node.kind == DocumentKind.Folder) "" else ".${node.kind.extension}"
            val path = (paths[node.parent]?.let { if (it.isEmpty()) "" else "$it/" } ?: throw WorkspaceException(Failure.InvalidArchive)) + segment
            paths[node.id] = path
            if (path != node.sourcePath) renamed += ArchiveRename(node.sourcePath, path)
        }
        return PreparedArchive(destination, ArchiveInventory(normalized, skipped, renamed))
    }

    private fun checkedPath(name: String, directory: Boolean): String {
        val path = if (directory) name.removeSuffix("/") else name
        if (path.isEmpty() || path.startsWith('/') || '\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path) || path.any { it.isISOControl() }) {
            throw WorkspaceException(Failure.InvalidArchive)
        }
        val segments = path.split('/')
        if (segments.size > MAX_DEPTH) throw WorkspaceException(Failure.ArchiveLimit)
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) throw WorkspaceException(Failure.InvalidArchive)
        return path
    }

    /** Bound the central directory before ZipFile allocates its entry/name table. */
    private fun checkDirectory(file: File) {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val tail = ByteArray(minOf(length, 65_557L).toInt())
            input.seek(length - tail.size); input.readFully(tail)
            fun u16(offset: Int) = (tail[offset].toInt() and 255) or ((tail[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int): Long = (0..3).fold(0L) { value, n -> value or ((tail[offset + n].toLong() and 255) shl (8 * n)) }
            val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
                tail[offset] == 0x50.toByte() && tail[offset + 1] == 0x4b.toByte() && tail[offset + 2] == 5.toByte() && tail[offset + 3] == 6.toByte() &&
                    offset + 22 + u16(offset + 20) == tail.size
            } ?: throw WorkspaceException(Failure.InvalidArchive)
            if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)) throw WorkspaceException(Failure.InvalidArchive)
            if (u16(end + 10) > MAX_ENTRIES || u32(end + 12) > 16L * 1024 * 1024) throw WorkspaceException(Failure.ArchiveLimit)
            if (u32(end + 16) + u32(end + 12) > length - tail.size + end) throw WorkspaceException(Failure.InvalidArchive)
        }
    }

    fun exportPlan(workspace: Workspace): ArchiveExport {
        val children = workspace.entries.filter { it.id != Workspace.ROOT }.groupBy { it.parent }
        val items = mutableListOf<ExportItem>()
        val renames = mutableListOf<ArchiveRename>()
        val parents = ArrayDeque<Pair<String, String>>()
        parents.add(Workspace.ROOT to "")
        while (parents.isNotEmpty()) {
            val (parent, prefix) = parents.removeFirst()
            val used = mutableSetOf<String>()
            children[parent].orEmpty().sortedWith(compareBy<Entry> { !it.folder }.thenBy { it.filename }.thenBy { it.id }).forEach { entry ->
                var segment = entry.filename
                var n = 1
                while (!used.add(segment.lowercase(Locale.ROOT))) {
                    val suffix = if (n == 1) "（副本）" else "（副本 $n）"
                    segment = safeNamePrefix(entry.name, 120 - suffix.length) + suffix + if (entry.folder) "" else ".${entry.kind.extension}"
                    n++
                }
                val path = prefix + segment
                checkedPath(path, false)
                val original = logicalPath(workspace, entry)
                if (path != original) renames += ArchiveRename(original, path)
                items += ExportItem(entry, path)
                if (entry.folder) parents.add(entry.id to "$path/")
            }
        }
        if (items.size != workspace.entries.size - 1 || items.size > MAX_ENTRIES) throw WorkspaceException(Failure.InvalidArchive)
        return ArchiveExport(items, renames)
    }

    private fun logicalPath(workspace: Workspace, entry: Entry): String {
        val segments = mutableListOf(entry.filename)
        var parent = entry.parent
        while (parent != null && parent != Workspace.ROOT) {
            val folder = workspace.entry(parent) ?: throw WorkspaceException(Failure.Corrupt)
            segments += folder.name
            parent = folder.parent
        }
        return segments.asReversed().joinToString("/")
    }

    fun write(file: File, plan: ArchiveExport, read: (String) -> String, check: () -> Unit = {}) {
        var expanded = 0L
        val expected = mutableMapOf<String, String>()
        FileOutputStream(file).use { disk ->
            val bounded = object : FilterOutputStream(disk) {
                var size = 0L
                override fun write(b: Int) { size++; if (size > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit); out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { size += len; if (size > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit); out.write(b, off, len) }
                override fun close() { flush() } // Keep the descriptor open for sync after ZIP finish.
            }
            ZipOutputStream(bounded, Charsets.UTF_8).use { zip ->
                plan.items.forEach { item ->
                    check()
                    val entry = ZipEntry(item.path + if (item.entry.folder) "/" else "").apply { time = item.entry.modified }
                    zip.putNextEntry(entry)
                    if (!item.entry.folder) {
                        val bytes = TextRules.bytes(read(item.entry.id))
                        expanded += bytes.size
                        if (expanded > MAX_BYTES) throw WorkspaceException(Failure.ArchiveLimit)
                        zip.write(bytes)
                        expected[item.path] = digest(bytes)
                    }
                    zip.closeEntry()
                }
            }
            disk.fd.sync()
        }
        val verification = File(file.parentFile, "verify-${UUID.randomUUID()}")
        try {
            val parsed = read(file, verification, check, allowEmpty = true, imported = false)
            val actual = parsed.inventory.nodes.filter { it.kind != DocumentKind.Folder }.associate { it.sourcePath to digest(TextRules.bytes(parsed.text(it.id))) }
            if (actual != expected || parsed.inventory.nodes.size != plan.items.size) throw WorkspaceException(Failure.InvalidArchive)
        } finally {
            if (verification.exists() && !verification.deleteRecursively()) throw WorkspaceException(Failure.Cleanup)
        }
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
