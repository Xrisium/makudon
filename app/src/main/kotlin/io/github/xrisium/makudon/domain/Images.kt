package io.github.xrisium.makudon.domain

import java.net.URI
import java.security.MessageDigest
import java.util.UUID

enum class ImageFormat(val extension: String, val mime: String) {
    Jpeg("jpg", "image/jpeg"), Png("png", "image/png"), Webp("webp", "image/webp");
    companion object {
        fun fromName(name: String): ImageFormat? = when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "jpg", "jpeg" -> Jpeg; "png" -> Png; "webp" -> Webp; else -> null
        }
    }
}

data class ImageInfo(val key: String, val format: ImageFormat, val width: Int, val height: Int, val sha256: String, val orientation: Int = 1) {
    val displayWidth: Int get() = if (orientation in 5..8) height else width
    val displayHeight: Int get() = if (orientation in 5..8) width else height
    fun encoded() = listOf(key, format.name, width, height, sha256, orientation).joinToString("|")
    fun validate() {
        if (!ImageReferences.validKey(key) || !Regex("[a-f0-9]{64}").matches(sha256) || width <= 0 || height <= 0 || width.toLong() * height > ImageRules.MAX_PIXELS || orientation !in 1..8) {
            throw WorkspaceException(Failure.InvalidImage)
        }
    }
    companion object {
        fun decode(value: String): ImageInfo = try {
            val parts = value.split('|')
            if (parts.size !in 5..6) throw WorkspaceException(Failure.Corrupt)
            ImageInfo(parts[0], ImageFormat.valueOf(parts[1]), parts[2].toInt(), parts[3].toInt(), parts[4], parts.getOrNull(5)?.toInt() ?: 1).also { it.validate() }
        } catch (_: IllegalArgumentException) { throw WorkspaceException(Failure.Corrupt) }
        catch (_: WorkspaceException) { throw WorkspaceException(Failure.Corrupt) }
    }
}

/** Header inspection is platform independent; Android also verifies a sampled decode on import. */
object ImageRules {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_PIXELS = 100_000_000L
    fun inspect(bytes: ByteArray, key: String = UUID.randomUUID().toString()): ImageInfo {
        if (bytes.size > MAX_BYTES) throw WorkspaceException(Failure.ImageTooLarge)
        fun u8(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255) ?: throw WorkspaceException(Failure.InvalidImage)
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (0..3).fold(0L) { n, k -> (n shl 8) or u8(i + k).toLong() }
        fun le32(i: Int) = (0..3).fold(0L) { n, k -> n or (u8(i + k).toLong() shl (k * 8)) }
        fun tag(i: Int, text: String) = text.indices.all { bytes.getOrNull(i + it)?.toInt()?.and(255) == text[it].code }
        var width = 0L; var height = 0L
        val format = when {
            tag(0, "\u0089PNG\r\n\u001a\n") && tag(12, "IHDR") -> {
                if (be32(8) != 13L || bytes.size < 45) throw WorkspaceException(Failure.InvalidImage)
                width = be32(16); height = be32(20); ImageFormat.Png
            }
            bytes.size >= 4 && u8(0) == 255 && u8(1) == 216 -> {
                if (u8(bytes.size - 2) != 255 || u8(bytes.size - 1) != 217) throw WorkspaceException(Failure.InvalidImage)
                var at = 2
                while (at + 4 < bytes.size) {
                    if (u8(at++) != 255) throw WorkspaceException(Failure.InvalidImage)
                    while (u8(at) == 255) at++
                    val marker = u8(at++)
                    if (marker == 218 || marker == 217) break
                    if (marker == 1 || marker in 208..215) continue
                    val size = be16(at)
                    if (size < 2 || at.toLong() + size > bytes.size) throw WorkspaceException(Failure.InvalidImage)
                    if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)) {
                        if (size < 8) throw WorkspaceException(Failure.InvalidImage)
                        height = be16(at + 3).toLong(); width = be16(at + 5).toLong(); break
                    }
                    at += size
                }
                ImageFormat.Jpeg
            }
            tag(0, "RIFF") && tag(8, "WEBP") -> {
                if (le32(4) + 8 != bytes.size.toLong()) throw WorkspaceException(Failure.InvalidImage)
                when {
                    tag(12, "VP8X") -> {
                        if (u8(20) and 2 != 0) throw WorkspaceException(Failure.InvalidImage)
                        width = 1 + (le32(24) and 0xffffff); height = 1 + (le32(27) and 0xffffff)
                    }
                    tag(12, "VP8L") && u8(20) == 47 -> {
                        val bits = le32(21); width = 1 + (bits and 0x3fff); height = 1 + ((bits shr 14) and 0x3fff)
                    }
                    tag(12, "VP8 ") && tag(23, "\u009d\u0001\u002a") -> {
                        width = (u8(26) or (u8(27) shl 8)).and(0x3fff).toLong()
                        height = (u8(28) or (u8(29) shl 8)).and(0x3fff).toLong()
                    }
                    else -> throw WorkspaceException(Failure.InvalidImage)
                }
                ImageFormat.Webp
            }
            else -> throw WorkspaceException(Failure.InvalidImage)
        }
        if (width <= 0 || height <= 0) throw WorkspaceException(Failure.InvalidImage)
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE || width * height > MAX_PIXELS) throw WorkspaceException(Failure.ImageTooLarge)
        return ImageInfo(key, format, width.toInt(), height.toInt(), digest(bytes), exifOrientation(bytes, format)).also { it.validate() }
    }
    private fun exifOrientation(bytes: ByteArray, format: ImageFormat): Int {
        fun tag(at: Int, value: String) = value.indices.all { bytes.getOrNull(at + it)?.toInt()?.and(255) == value[it].code }
        fun u8(at: Int) = bytes[at].toInt() and 255
        fun number(at: Int, count: Int, little: Boolean): Long = (0 until count).fold(0L) { value, n -> value or (u8(at + n).toLong() shl (8 * if (little) n else count - n - 1)) }
        fun tiff(base: Int, end: Int): Int {
            if (base < 0 || end > bytes.size || base + 8 > end) return 1
            val little = tag(base, "II")
            if (!little && !tag(base, "MM")) return 1
            if (number(base + 2, 2, little) != 42L) return 1
            val start = base.toLong() + number(base + 4, 4, little)
            if (start < base || start + 2 > end) return 1
            val entries = number(start.toInt(), 2, little)
            for (index in 0 until minOf(entries, 4096L).toInt()) {
                val at = start + 2 + index * 12L
                if (at + 12 > end) break
                val offset = at.toInt()
                if (number(offset, 2, little) == 274L && number(offset + 2, 2, little) == 3L && number(offset + 4, 4, little) == 1L) {
                    return number(offset + 8, 2, little).toInt().takeIf { it in 1..8 } ?: 1
                }
            }
            return 1
        }
        try {
            var at = if (format == ImageFormat.Jpeg) 2 else if (format == ImageFormat.Png) 8 else 12
            while (at + 8 <= bytes.size) {
                if (format == ImageFormat.Jpeg) {
                    if (u8(at++) != 255) break
                    while (u8(at) == 255) at++
                    val marker = u8(at++)
                    if (marker == 218 || marker == 217) break
                    if (marker == 1 || marker in 208..215) continue
                    val size = number(at, 2, false).toInt()
                    if (size < 2 || at.toLong() + size > bytes.size) break
                    if (marker == 225 && tag(at + 2, "Exif\u0000\u0000")) return tiff(at + 8, at + size)
                    at += size
                } else {
                    val png = format == ImageFormat.Png
                    val size = number(at + if (png) 0 else 4, 4, !png)
                    val data = at + 8
                    if (size > bytes.size.toLong() - data) break
                    if (tag(at + if (png) 4 else 0, if (png) "eXIf" else "EXIF")) {
                        return tiff(data + if (tag(data, "Exif\u0000\u0000")) 6 else 0, data + size.toInt())
                    }
                    at = data + size.toInt() + if (png) 4 else (size % 2).toInt()
                }
            }
        } catch (_: IndexOutOfBoundsException) { return 1 }
        return 1
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

object ImageReferences {
    private const val PREFIX = "makudon-image:"
    fun validKey(key: String) = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(key)
    fun uri(key: String): String { if (!validKey(key)) throw WorkspaceException(Failure.InvalidImage); return PREFIX + key }
    fun key(destination: String): String? = destination.removePrefix(PREFIX).takeIf { destination.startsWith(PREFIX) && validKey(it) }
    fun images(source: String): List<MdElement> {
        val result = mutableListOf<MdElement>()
        fun visit(node: MdElement) { if (node.kind == "Image") result += node else node.children.forEach(::visit) }
        visit(MarkdownParser.parse(source).root)
        return result
    }
    fun keys(source: String): Set<String> = images(source).mapNotNull { key(it.destination) }.toSet()
    fun label(node: MdElement) = node.descendants().filter { it.kind == "Text" || it.kind == "Code" }.joinToString("") { it.text }
    fun syntax(label: String, destination: String) = "![${label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace('\n', ' ')}](<${destination.replace("<", "%3C").replace(">", "%3E")}>)"
    fun rewrite(source: String, destination: (MdElement) -> String?): String {
        var result = source
        images(source).sortedByDescending { it.range.start }.forEach { node ->
            destination(node)?.takeIf { it != node.destination }?.let { result = result.replaceRange(node.range.start, node.range.end, rewritten(source, node, it)) }
        }
        TextRules.bytes(result)
        return result
    }
    private fun rewritten(source: String, node: MdElement, destination: String): String {
        val raw = source.substring(node.range.start, node.range.end)
        val literal = "<${destination.replace("<", "%3C").replace(">", "%3E")}>"
        // AST spans let bracket-like text inside code, HTML or nested links stay literal.
        val protected = node.children.asSequence().flatMap { it.descendants() }
            .filter { it.kind in setOf("Code", "Link", "Image", "HtmlInline") }.map { it.range }
            .sortedWith(compareBy<SourceRange> { it.start }.thenByDescending { it.end }).toList()
        var at = 2; var depth = 1; var labelEnd = -1
        if (raw.startsWith("![")) while (at < raw.length) {
            val protectedRange = protected.firstOrNull { it.start == node.range.start + at && it.end > it.start }
            if (protectedRange != null) { at = protectedRange.end - node.range.start; continue }
            when (raw[at]) {
                '\\' -> { at += 2; continue }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { labelEnd = at; break } }
            }
            at++
        }
        if (labelEnd < 0) throw WorkspaceException(Failure.InvalidText)
        if (raw.getOrNull(labelEnd + 1) == '(') {
            var start = labelEnd + 2
            while (raw.getOrNull(start)?.isWhitespace() == true) start++
            var end = start
            if (raw.getOrNull(start) == '<') {
                end++
                while (end < raw.length && raw[end] != '>') end += if (raw[end] == '\\') 2 else 1
                if (end >= raw.length) throw WorkspaceException(Failure.InvalidText)
                end++
            } else {
                var parentheses = 0
                while (end < raw.length) {
                    val char = raw[end]
                    if (char == '\\') { end += 2; continue }
                    if (char == ')' && parentheses == 0 || char.isWhitespace() && parentheses == 0) break
                    if (char == '(') parentheses++ else if (char == ')') parentheses--
                    end++
                }
            }
            val replacement = if (raw.getOrNull(start) == '<' || destination.any { it.isWhitespace() || it in "<>()\\" }) literal else destination
            return raw.replaceRange(start, end, replacement)
        }
        // Reference-style images become inline so shared definitions need not be rewritten.
        val title = node.title.takeIf { it.isNotEmpty() }?.let { " \"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }.orEmpty()
        return raw.substring(0, labelEnd + 1) + "(" + literal + title + ")"
    }
    fun remap(source: String, mapping: Map<String, String>) = rewrite(source) { node -> key(node.destination)?.let(mapping::get)?.let(::uri) }
    fun standalone(document: MarkdownDocument): List<MdElement> = document.elements.filter { node ->
        node.kind == "Paragraph" && node.children.size == 1 && node.children.single().kind == "Image" && key(node.children.single().destination) != null
    }
    fun insert(source: String, selection: TextSelection, label: String, key: String): EditResult {
        if (selection.min < 0 || selection.max > source.length) throw WorkspaceException(Failure.InvalidImage)
        val document = MarkdownParser.parse(source)
        if (document.elements.any { node -> node.kind in setOf("FencedCodeBlock", "IndentedCodeBlock", "Code", "TableBlock", "HtmlBlock", "HtmlInline", "Image") &&
                (if (selection.min == selection.max) selection.min >= node.range.start && selection.min < node.range.end else selection.min < node.range.end && selection.max > node.range.start) }) throw WorkspaceException(Failure.InvalidImage)
        val prefix = source.substring(0, selection.min)
        val suffix = source.substring(selection.max)
        val before = if (prefix.isEmpty() || prefix.endsWith("\n\n")) "" else if (prefix.endsWith('\n')) "\n" else "\n\n"
        val after = if (suffix.startsWith("\n\n")) "" else if (suffix.startsWith('\n')) "\n" else "\n\n"
        val insertion = before + syntax(label, uri(key)) + after
        val result = prefix + insertion + suffix
        TextRules.bytes(result)
        return EditResult(result, TextSelection(prefix.length + insertion.length))
    }
    fun relative(from: String, to: String): String {
        val parent = from.split('/').dropLast(1); val target = to.split('/')
        val common = parent.zip(target).takeWhile { it.first == it.second }.size
        val path = List(parent.size - common) { ".." } + target.drop(common)
        return path.joinToString("/") { if (it == "..") it else URI(null, null, it, null).rawPath.replace("(", "%28").replace(")", "%29") }
    }
    /** Resolve only relative archive paths, never a device path or an external URI. */
    fun resolve(from: String, destination: String): String? = try {
        val uri = URI(destination)
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null || destination.startsWith('/') || '\\' in destination) null
        else {
            val parts = from.split('/').dropLast(1).toMutableList()
            var valid = true
            uri.path.split('/').forEach { part -> when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) valid = false else parts.removeAt(parts.lastIndex)
                else -> if ('\\' in part || part.any { it.isISOControl() }) valid = false else parts += part
            } }
            if (valid && parts.isNotEmpty()) parts.joinToString("/") else null
        }
    } catch (_: java.net.URISyntaxException) { null }
}
