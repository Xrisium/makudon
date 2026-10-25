package io.github.xrisium.makudon.domain

enum class ItemSelection { None, Direct, Inherited, Partial }

/** Stable operation roots: a folder represents its entire subtree exactly once. */
fun Workspace.selectionRoots(ids: Collection<String>): List<Entry> {
    val wanted = ids.toSet() - Workspace.ROOT
    val byId = entries.associateBy { it.id }
    return entries.filter { item ->
        if (item.id !in wanted) false else {
            var parent = item.parent
            var covered = false
            while (parent != null) {
                if (parent in wanted) { covered = true; break }
                parent = byId[parent]?.parent
            }
            !covered
        }
    }
}

fun Workspace.isWithin(id: String, ancestor: String): Boolean {
    var item = entry(id)
    while (item != null) {
        if (item.id == ancestor) return true
        item = item.parent?.let(::entry)
    }
    return false
}

fun Workspace.selectionOf(ids: Set<String>, id: String): ItemSelection = when {
    id == Workspace.ROOT -> ItemSelection.None
    id in ids -> ItemSelection.Direct
    ids.any { isWithin(id, it) } -> ItemSelection.Inherited
    entry(id)?.folder == true && ids.any { isWithin(it, id) } -> ItemSelection.Partial
    else -> ItemSelection.None
}

fun Workspace.toggleSelection(ids: Set<String>, id: String): Set<String> {
    if (id == Workspace.ROOT || entry(id) == null || selectionOf(ids, id) == ItemSelection.Inherited) return ids
    val next = if (id in ids) ids - id else ids.filterNot { isWithin(it, id) }.toSet() + id
    return selectionRoots(next).map { it.id }.toSet()
}

data class PasteTargets(val related: List<String>, val common: String?) {
    val suggested: String? get() = related.singleOrNull()
}

fun Workspace.pasteTargets(ids: Collection<String>, fallback: String): PasteTargets {
    val targets = if (Workspace.ROOT in ids) listOfNotNull(entry(Workspace.ROOT)) else selectionRoots(ids)
    val related = targets.map { if (it.folder) it.id else it.parent ?: Workspace.ROOT }.distinct()
        .ifEmpty { listOf(fallback.takeIf { entry(it)?.folder == true } ?: Workspace.ROOT) }
    var common: String? = related.first()
    while (common != null) {
        val candidate = common
        if (related.all { isWithin(it, candidate) }) break
        common = entry(candidate)?.parent
    }
    return PasteTargets(related, common?.takeIf { related.size > 1 && it !in related })
}

/** Used by both destination previews and the store immediately before publication. */
fun Workspace.pasteFailure(sourceIds: Collection<String>, cut: Boolean, destination: String): Failure? {
    if (entry(destination)?.folder != true) return Failure.Missing
    if (Workspace.ROOT in sourceIds) return Failure.Cycle
    if (cut && sourceIds.any { entry(it) == null }) return Failure.Missing
    val sources = selectionRoots(sourceIds)
    if (sources.any { it.folder && isWithin(destination, it.id) }) return Failure.Cycle
    if (!cut) return null
    if (sources.isEmpty()) return Failure.Missing
    if (sources.all { it.parent == destination }) return Failure.NoChange
    val moved = sources.map { it.copy(parent = destination) }
    val candidates = entries.filter { item -> sources.none { it.id == item.id } } + moved
    if (moved.any { TextRules.conflicts(candidates, it.name, destination, it.kind, it.id) }) return Failure.Conflict
    return null
}
