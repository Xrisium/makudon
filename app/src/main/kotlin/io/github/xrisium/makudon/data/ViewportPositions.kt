package io.github.xrisium.makudon.data

import io.github.xrisium.makudon.domain.EditorMode
import io.github.xrisium.makudon.domain.ViewportPosition
import io.github.xrisium.makudon.domain.WorkspaceException
import java.io.File
import java.io.IOException

/** Optional local resume metadata. Failure must never block or masquerade as a body save. */
internal object ViewportPositions {
    fun read(file: File, disk: AtomicDisk): ViewportPosition? = try {
        val parts = disk.read(file, 256).toString(Charsets.UTF_8).split(',')
        if (parts.size != 4) null else {
            val anchor = parts[0].toIntOrNull()
            val offset = parts[1].toIntOrNull()
            val height = parts[2].toFloatOrNull()
            val mode = EditorMode.entries.firstOrNull { it.name == parts[3] }
            if (anchor == null || anchor < 0 || offset == null || offset < 0 || height == null || !height.isFinite() || height <= 0 || mode == null) null
            else ViewportPosition(anchor, offset, height, mode)
        }
    } catch (_: IOException) { null }
    catch (_: WorkspaceException) { null }

    fun write(file: File, value: ViewportPosition, disk: AtomicDisk): Boolean = try {
        disk.write(file, "${value.anchor},${value.offset},${value.lineHeight},${value.mode.name}".toByteArray(Charsets.UTF_8))
        true
    } catch (_: IOException) { false }
    catch (_: WorkspaceException) { false }
}
