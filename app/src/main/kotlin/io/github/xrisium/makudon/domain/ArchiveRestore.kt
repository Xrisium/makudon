package io.github.xrisium.makudon.domain

import java.util.UUID

enum class RestoreMode { Separate, Incremental, Replace }
enum class ConflictChoice { Local, Backup, Both }

data class ArchiveNode(
    val id: String,
    val name: String,
    val parent: String?,
    val kind: DocumentKind,
    val modified: Long,
    val bytes: Int = 0,
    val sourcePath: String,
)
data class ArchiveRename(val from: String, val to: String)
data class ArchiveInventory(val nodes: List<ArchiveNode>, val skipped: List<String>, val renames: List<ArchiveRename>) {
    val documents: Int get() = nodes.count { it.kind != DocumentKind.Folder }
}
data class RestoreConflict(val source: ArchiveNode, val local: Entry, val path: String)
data class RestorePlan(
    val mode: RestoreMode,
    val workspace: Workspace,
    val sources: Map<String, String>,
    val conflicts: List<RestoreConflict>,
    val decisions: Map<String, ConflictChoice>,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val removed: Int,
    val folder: String?,
) {
    val resolved: Boolean get() = conflicts.all { it.source.id in decisions }
    val changed: Boolean get() = added > 0 || updated > 0 || removed > 0
}

/** Plans only metadata. The caller compares bounded, validated bodies on its IO dispatcher. */
object RestorePlanner {
    fun plan(
        local: Workspace,
        archive: ArchiveInventory,
        mode: RestoreMode,
        decisions: Map<String, ConflictChoice> = emptyMap(),
        folderName: String,
        now: Long,
        sameBody: (String, String) -> Boolean,
    ): RestorePlan {
        if (archive.documents == 0) throw WorkspaceException(Failure.EmptyArchive)
        val entries = (if (mode == RestoreMode.Replace) local.entries.filter { it.id == Workspace.ROOT } else local.entries).toMutableList()
        val sources = mutableMapOf<String, String>()
        val mapping = mutableMapOf<String?, String>(null to Workspace.ROOT)
        val conflicts = mutableListOf<RestoreConflict>()
        var added = 0
        var updated = 0
        var unchanged = 0
        var separateName: String? = null
        fun unique(name: String, parent: String, kind: DocumentKind, suffix: String): String {
            var candidate = name
            var n = 1
            while (TextRules.conflicts(entries, candidate, parent, kind)) {
                val tail = if (n == 1) "（$suffix）" else "（$suffix $n）"
                candidate = safeNamePrefix(name, 120 - tail.length) + tail
                n++
            }
            return candidate
        }
        if (mode == RestoreMode.Separate) {
            separateName = unique(folderName, Workspace.ROOT, DocumentKind.Folder, "恢复副本")
            val folder = Entry(UUID.randomUUID().toString(), separateName, Workspace.ROOT, DocumentKind.Folder, now)
            entries += folder
            mapping[null] = folder.id
            added++
        }
        archive.nodes.forEach { node ->
            val parent = mapping[node.parent] ?: throw WorkspaceException(Failure.InvalidArchive)
            val match = if (mode == RestoreMode.Incremental) entries.firstOrNull {
                it.parent == parent && it.kind == node.kind && it.name.equals(node.name, ignoreCase = true)
            } else null
            if (match?.folder == true) { mapping[node.id] = match.id; unchanged++; return@forEach }
            if (match != null && sameBody(match.id, node.id)) { unchanged++; return@forEach }
            val choice = if (match != null) {
                conflicts += RestoreConflict(node, match, node.sourcePath)
                // Unresolved conflicts must remain previewable even when a copy would exceed capacity.
                decisions[node.id] ?: return@forEach
            } else null
            if (choice == ConflictChoice.Local) { unchanged++; return@forEach }
            if (choice == ConflictChoice.Backup && match != null) {
                entries[entries.indexOf(match)] = match.copy(modified = node.modified, bytes = node.bytes)
                sources[match.id] = node.id
                updated++
            } else {
                val name = unique(node.name, parent, node.kind, "恢复副本")
                val entry = Entry(UUID.randomUUID().toString(), name, parent, node.kind, now, modified = node.modified, bytes = node.bytes)
                entries += entry
                mapping[node.id] = entry.id
                if (!entry.folder) sources[entry.id] = node.id
                added++
            }
            if (entries.size > 10_000) throw WorkspaceException(Failure.CatalogFull)
        }
        return RestorePlan(mode, local.copy(entries = entries), sources, conflicts, decisions.toMap(), added, updated,
            unchanged, if (mode == RestoreMode.Replace) local.entries.size - 1 else 0, separateName)
    }
}

/** Keep surrogate pairs intact when reserving room for a generated suffix. */
fun safeNamePrefix(name: String, length: Int): String = name.take(length).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
}
