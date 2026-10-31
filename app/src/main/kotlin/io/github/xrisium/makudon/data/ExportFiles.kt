package io.github.xrisium.makudon.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Immutable export copies have their own lifetime, independent of editor and ZIP staging. */
data class ExportFile(val file: File, val name: String, val mime: String)

class ExportFiles(private val root: File) {
    fun prepare(name: String, mime: String, active: File? = null, write: (OutputStream) -> Unit): ExportFile {
        cleanExpired(active)
        val folder = File(root, UUID.randomUUID().toString())
        check(folder.mkdirs()) { "Cannot prepare export" }
        val safeName = name.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }.joinToString("").trim().take(160)
        val file = File(folder, safeName.ifBlank { "export" })
        try {
            file.outputStream().use { output -> write(output); output.flush(); output.fd.sync() }
            return ExportFile(file, file.name, mime)
        } catch (e: Exception) {
            if (!folder.deleteRecursively()) e.addSuppressed(IllegalStateException("Export cleanup failed"))
            throw e
        }
    }

    fun cleanExpired(active: File?, now: Long = System.currentTimeMillis()) {
        if (!root.exists()) return
        val folders = checkNotNull(root.listFiles()) { "Cannot inspect export copies" }
        for (folder in folders) {
            if (folder == active?.parentFile || now - folder.lastModified() < RETENTION_MS) continue
            check(folder.deleteRecursively()) { "Cannot clean expired export" }
        }
    }

    companion object {
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        fun copy(source: ExportFile, output: OutputStream) { source.file.inputStream().use { it.copyTo(output) }; output.flush() }
        fun verify(source: ExportFile, input: InputStream) {
            fun digest(stream: InputStream): ByteArray {
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= source.file.length()) { "Export size mismatch" }
                    hash.update(buffer, 0, count)
                }
                check(total == source.file.length()) { "Incomplete export" }
                return hash.digest()
            }
            val expected = source.file.inputStream().use(::digest)
            check(MessageDigest.isEqual(expected, digest(input))) { "Export content mismatch" }
        }
    }
}
