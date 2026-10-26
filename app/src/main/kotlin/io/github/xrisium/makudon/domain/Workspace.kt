package io.github.xrisium.makudon.domain

import java.text.Collator
import java.util.Locale

enum class DocumentKind(val extension: String) { Folder(""), Plain("txt"), Markdown("md") }
enum class SortKey { Name, Modified, Created, Type, Size }
enum class ColorTheme { System, Paper, Cream, Mist, Sage, Ink }

data class Entry(
    val id: String,
    val name: String,
    val parent: String?,
    val kind: DocumentKind,
    val created: Long,
    val opened: Long = 0,
    val modified: Long = created,
    val bytes: Int = 0,
    val excerpt: String = "",
    val unreadable: Boolean = false,
    val importRequestId: String? = null,
) {
    val folder: Boolean get() = kind == DocumentKind.Folder
    val filename: String get() = if (folder) name else "$name.${kind.extension}"
}

data class Workspace(
    val entries: List<Entry>,
    val theme: ColorTheme = ColorTheme.System,
    val sort: SortKey = SortKey.Name,
    val descending: Boolean = false,
    val foldersFirst: Boolean = true,
    val lastImportRequestId: String? = null,
) {
    fun entry(id: String) = entries.firstOrNull { it.id == id }
    fun path(id: String): String {
        val item = entry(id) ?: return ""
        return if (item.parent == null || item.parent == ROOT) item.name
        else path(item.parent) + " / " + item.name
    }
    fun descendants(id: String): Set<String> {
        val result = mutableSetOf(id)
        var changed: Boolean
        do {
            changed = false
            entries.forEach { if (it.parent in result && result.add(it.id)) changed = true }
        } while (changed)
        return result
    }
    fun sorted(items: List<Entry>): List<Entry> {
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        fun natural(a: String, b: String): Int {
            val parts = Regex("\\d+|\\D+")
            val left = parts.findAll(a).map { it.value }.toList()
            val right = parts.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                val c = if (x.first().isDigit() && y.first().isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else collator.compare(x, y)
                if (c != 0) return c
            }
            return left.size.compareTo(right.size)
        }
        return items.sortedWith { a, b ->
            if (foldersFirst && a.folder != b.folder) return@sortedWith if (a.folder) -1 else 1
            val comparison = if (foldersFirst && a.folder && b.folder) natural(a.name, b.name) else when (sort) {
                SortKey.Name -> natural(a.name, b.name)
                SortKey.Modified -> a.modified.compareTo(b.modified)
                SortKey.Created -> a.created.compareTo(b.created)
                SortKey.Type -> a.kind.compareTo(b.kind)
                SortKey.Size -> a.bytes.compareTo(b.bytes)
            }
            val stable = comparison.takeIf { it != 0 } ?: natural(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
            if (descending) -stable else stable
        }
    }

    companion object {
        const val ROOT = "root"
        // Schema 1 workspaces may still contain this folder. Preserve its ID and contents.
        const val LEGACY_INBOX = "inbox"
    }
}

enum class Failure { InvalidName, Conflict, Cycle, Missing, InvalidText, TooLarge, Storage, Corrupt, NoPrevious, Cleanup, NoChange, ClipboardTooLarge, CatalogFull, InvalidArchive, ArchiveLimit, EmptyArchive, StaleRestore, NoSnapshot, Space }
class WorkspaceException(val reason: Failure, val detail: String? = null) : Exception(reason.name)

object TextRules {
    const val MAX_BYTES = 1_048_576
    const val MAX_CODE_POINTS = 100_000
    fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')
    fun bytes(text: String): ByteArray {
        if ('\u0000' in text || text.any { it.code in 1..8 || it.code in 14..31 }) throw WorkspaceException(Failure.InvalidText)
        if (text.codePointCount(0, text.length) > MAX_CODE_POINTS) throw WorkspaceException(Failure.TooLarge)
        val encoder = Charsets.UTF_8.newEncoder()
        val buffer = try { encoder.encode(java.nio.CharBuffer.wrap(text)) } catch (_: java.nio.charset.CharacterCodingException) {
            throw WorkspaceException(Failure.InvalidText)
        }
        if (buffer.remaining() > MAX_BYTES) throw WorkspaceException(Failure.TooLarge)
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    fun decode(data: ByteArray, imported: Boolean = false): String {
        if (data.size > MAX_BYTES) throw WorkspaceException(Failure.TooLarge)
        var text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw WorkspaceException(Failure.InvalidText) }
        if ('\u0000' in text || text.any { it.code in 1..8 || it.code in 14..31 }) throw WorkspaceException(Failure.InvalidText)
        if (imported) text = normalize(text.removePrefix("\uFEFF"))
        if (imported && (text.startsWith("%PDF-") || text.startsWith("GIF87a") || text.startsWith("GIF89a"))) throw WorkspaceException(Failure.InvalidText)
        bytes(text)
        return text
    }
    fun checkName(name: String) {
        if (name.isBlank() || name != name.trim() || name.length > 120 || name in setOf(".", "..") || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw WorkspaceException(Failure.InvalidName)
        }
    }
    fun conflicts(entries: List<Entry>, name: String, parent: String, kind: DocumentKind, except: String? = null) =
        entries.any { it.id != except && it.parent == parent && it.kind == kind && it.name.equals(name, ignoreCase = true) }
}

/** A block range includes no separator. Edits replace this range only; untouched Markdown is never serialized. */
data class MarkdownBlock(val start: Int, val end: Int, val prefix: String, val text: String, val heading: Int, val editable: Boolean)
fun markdownBlocks(source: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    var start = 0
    while (start <= source.length) {
        val separator = source.indexOf("\n\n", start).let { if (it < 0) source.length else it }
        val raw = source.substring(start, separator)
        val heading = Regex("^(#{1,2}) ([^\\n]*)$").matchEntire(raw)
        val unsupported = raw.contains(Regex("(?m)^(?:```|~~~|[>+*\\-] |\\d+\\. |\\||<| {4}|\\t)|[*_`\\[\\]]"))
        val prefix = heading?.groupValues?.get(1)?.plus(" ") ?: ""
        result += MarkdownBlock(start, separator, prefix, raw.removePrefix(prefix), prefix.trim().length, !unsupported)
        if (separator == source.length) break
        start = separator + 2
    }
    return result
}
