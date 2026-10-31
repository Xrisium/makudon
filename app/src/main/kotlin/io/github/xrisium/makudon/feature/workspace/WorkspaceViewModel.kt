package io.github.xrisium.makudon.feature.workspace

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.WorkspaceStore
import io.github.xrisium.makudon.data.LibraryController
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File

enum class Page { Recent, Directory, Settings, New, Editor, Licenses, Previous, Backup }
val MainPages = listOf(Page.Directory, Page.Recent, Page.Settings)

data class WorkspaceUi(
    val workspace: Workspace? = null,
    val page: Page = Page.Recent,
    val busy: Boolean = true,
    val savingPreferences: Boolean = false,
    val error: Failure? = null,
    val message: Int? = null,
    val selected: String = Workspace.ROOT,
    val newParent: String = Workspace.ROOT,
    val origin: Page = Page.Recent,
    val activeId: String? = null,
    val typeset: Boolean = true,
    val previous: String? = null,
    val mainTab: Page = Page.Recent,
    val navigationId: Long = 0,
    val backwards: Boolean = false,
    val transitioning: Boolean = false,
    val tabTransitioning: Boolean = false,
    val tabTransitionId: Long = 0,
    val revealFolder: String? = null,
    val directoryRevision: Long = 0,
)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    var ui by mutableStateOf(WorkspaceUi())
        private set
    var textState by mutableStateOf<TextFieldState?>(null)
        private set
    var save by mutableStateOf<SaveCoordinator?>(null)
        private set
    var blocks by mutableStateOf<List<MarkdownBlock>>(emptyList())
        private set
    var blockEpoch by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var history = EditHistory()
    private var historySource = ""
    private val paragraphFields = mutableMapOf<Int, TextFieldState>()
    data class ClipboardItem(val entry: Entry, val location: String)
    data class Clipboard(val items: List<ClipboardItem>, val cut: Boolean, val copies: List<WorkspaceStore.Copy>, val token: Long) {
        val ids: List<String> get() = items.map { it.entry.id }
    }
    data class PasteRequest(val clipboard: Clipboard, val targets: PasteTargets)
    var clipboard by mutableStateOf<Clipboard?>(null)
        private set
    var selection by mutableStateOf<Set<String>?>(null)
        private set
    var pasteRequest by mutableStateOf<PasteRequest?>(null)
        private set
    var deleteRequest by mutableStateOf<List<String>?>(null)
        private set
    private var clipboardToken = 0L
    private val ioLock = Mutex()
    private val library = LibraryController(application.filesDir, application.getString(R.string.library), available = {
        application.getSystemService(android.os.storage.StorageManager::class.java).let { storage ->
            storage.getAllocatableBytes(storage.getUuidForPath(application.filesDir))
        }
    })
    private val store: WorkspaceStore get() = library.store
    private var libraryEpoch = 0L
    val files = FileExchange(application, viewModelScope)
    val backup = BackupCoordinator(viewModelScope, application, ioLock, library, { barrier() }) { current ->
        libraryEpoch++
        observer?.cancel(); observer = null; save = null; textState = null
        paragraphFields.clear(); departingSession = null; sessionId++
        selection = null; clipboard = null; pasteRequest = null; deleteRequest = null
        canUndo = false; canRedo = false; history = EditHistory(); blocks = emptyList()
        ui = ui.copy(workspace = current, activeId = null, previous = null, selected = Workspace.ROOT, newParent = Workspace.ROOT,
            revealFolder = null, directoryRevision = ui.directoryRevision + 1, message = R.string.backup_library_changed, error = null)
        navigate(Page.Directory, backwards = true)
    }
    private var observer: Job? = null
    private var sessionId = 0L
    private var departingSession: Long? = null
    private var exportText: String? = null
    private var exportName: String = ""
    var licenseTexts by mutableStateOf<List<String>>(emptyList())
        private set
    private var loading: Job? = null
    private val externalRequests = mutableSetOf<String>()
    val pendingExportName: String get() = exportName

    init { reload() }
    private suspend fun <T> disk(block: () -> T): T = withContext(Dispatchers.IO + NonCancellable) { ioLock.withLock { block() } }
    private fun failure(e: Exception) = (e as? WorkspaceException)?.reason ?: Failure.Storage
    private fun launchWork(savingPreferences: Boolean = false, block: suspend () -> Unit) {
        if (ui.busy || ui.transitioning || ui.tabTransitioning || backup.locked) return
        ui = ui.copy(busy = true, savingPreferences = savingPreferences, error = null, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui = ui.copy(error = failure(e)) }
            finally { ui = ui.copy(busy = false, savingPreferences = false) }
        }
    }
    fun reload() {
        if (loading?.isActive == true || backup.locked) return
        ui = ui.copy(busy = true, error = null)
        loading = viewModelScope.launch {
            try { acceptWorkspace(disk { library.load() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui = ui.copy(error = failure(e)) }
            finally { ui = ui.copy(busy = false) }
        }
    }
    private suspend fun barrier(retry: Boolean = false): Boolean {
        flushParagraphs()
        val coordinator = save ?: return true
        while (true) {
            flushParagraphs()
            textState?.let { coordinator.changed(it.text.toString()) }
            if (!coordinator.flush(retry)) return false
            if (textState?.text?.toString() == coordinator.latest) return true
        }
    }
    fun backgroundSave() {
        finishTransition(ui.navigationId)
        ui = ui.copy(tabTransitioning = false)
        viewModelScope.launch { barrier() }
    }
    fun retrySave() { launchWork { barrier(true) } }
    fun dismissError() { ui = ui.copy(error = null, message = null) }
    fun inputRejected(reason: Failure = Failure.TooLarge) { ui = ui.copy(error = reason) }
    fun select(id: String) { if (ui.workspace?.entry(id)?.folder == true) ui = ui.copy(selected = id) }
    private fun navigate(page: Page, backwards: Boolean = false) {
        if (page == ui.page) return
        if (page != Page.Directory) { selection = null; pasteRequest = null; deleteRequest = null }
        ui = ui.copy(page = page, mainTab = if (page in MainPages) page else ui.mainTab,
            backwards = backwards, navigationId = ui.navigationId + 1, transitioning = true)
    }

    fun finishTabTransition(id: Long) {
        if (id == ui.tabTransitionId) ui = ui.copy(tabTransitioning = false)
    }

    fun selectTab(page: Page) {
        if (page !in MainPages || ui.page !in MainPages || ui.busy || ui.transitioning || ui.tabTransitioning || selection != null || page == ui.mainTab) return
        ui = ui.copy(page = page, mainTab = page, tabTransitioning = true, tabTransitionId = ui.tabTransitionId + 1)
    }

    // An outgoing editor keeps its field/session until its last animation frame. A stale
    // completion (including an old Activity) must never release a newer document session.
    fun finishTransition(id: Long) {
        if (id != ui.navigationId) return
        if (ui.page in MainPages && departingSession == sessionId) {
            observer?.cancel(); observer = null; save = null; textState = null
            paragraphFields.clear()
            departingSession = null
            ui = ui.copy(activeId = null)
        }
        ui = ui.copy(transitioning = false)
    }

    fun go(page: Page) {
        if (page in MainPages && ui.page in MainPages) { selectTab(page); return }
        launchWork {
            if (!barrier()) return@launchWork
            val refreshed = disk { store.refresh() }
            departingSession = sessionId
            ui = ui.copy(workspace = refreshed)
            navigate(page, backwards = true)
        }
    }
    fun newDocument(from: Page = ui.page, parent: String? = null) {
        if (ui.busy || ui.transitioning || ui.tabTransitioning || backup.locked) return
        val state = ui.workspace ?: return
        val requested = parent ?: if (from == Page.Recent) Workspace.ROOT else ui.selected
        val target = requested.takeIf { state.entry(it)?.folder == true } ?: Workspace.ROOT
        ui = ui.copy(newParent = target, origin = from, error = null)
        navigate(Page.New)
    }
    fun chooseNewParent(id: String) { if (ui.workspace?.entry(id)?.folder == true) ui = ui.copy(newParent = id) }
    fun create(kind: DocumentKind) { launchWork {
        val name = getApplication<Application>().getString(R.string.untitled)
        val id = disk { store.create(name, ui.newParent, kind) }
        openInternal(id, ui.origin)
    } }
    fun createFolder(name: String, parent: String, onSuccess: () -> Unit = {}) { launchWork {
        val id = disk { store.create(name, parent, DocumentKind.Folder) }
        ui = ui.copy(workspace = disk { store.refresh() }, selected = id)
        onSuccess()
    } }
    fun open(id: String) { launchWork { openInternal(id, if (ui.page == Page.Directory) Page.Directory else Page.Recent) } }
    private suspend fun openInternal(id: String, origin: Page) {
        if (!barrier()) return
        val text = disk { store.open(id) }
        val refreshed = disk { store.refresh() }
        observer?.cancel()
        paragraphFields.clear()
        val field = TextFieldState(text)
        val epoch = libraryEpoch
        val openedStore = store
        val session = SaveCoordinator(viewModelScope, text, SystemClock::uptimeMillis) { value -> disk {
            if (epoch != libraryEpoch || store !== openedStore) throw WorkspaceException(Failure.StaleRestore)
            store.save(id, value)
        } }
        sessionId++
        departingSession = null
        textState = field; save = session
        history = EditHistory(); historySource = text; canUndo = false; canRedo = false
        blocks = markdownBlocks(text); blockEpoch++
        val previousPage = ui.page
        ui = ui.copy(activeId = id, origin = origin, typeset = true, workspace = refreshed)
        navigate(Page.Editor, backwards = previousPage == Page.Previous)
        observer = viewModelScope.launch { snapshotFlow { field.text.toString() }.collect { recordEdit(it); session.changed(it) } }
    }
    fun back() {
        if (ui.busy || ui.transitioning || ui.tabTransitioning) return
        when (ui.page) {
            Page.Backup -> {
                if (backup.locked) backup.cancel() else navigate(Page.Settings, backwards = true)
            }
            Page.Editor -> go(ui.origin)
            Page.New -> navigate(ui.origin, backwards = true)
            Page.Licenses -> navigate(Page.Settings, backwards = true)
            Page.Previous -> navigate(if (textState != null) Page.Editor else ui.origin, backwards = true)
            else -> Unit
        }
    }
    fun typeset(value: Boolean) {
        if (ui.busy || ui.transitioning || ui.page != Page.Editor || ui.typeset == value) return
        flushParagraphs(); paragraphFields.clear(); if (value) { blocks = markdownBlocks(textState?.text?.toString().orEmpty()); blockEpoch++ }; ui = ui.copy(typeset = value)
    }
    fun registerParagraph(index: Int, field: TextFieldState) { paragraphFields[index] = field }
    fun unregisterParagraph(index: Int, field: TextFieldState) {
        if (paragraphFields[index] !== field) return
        blocks.getOrNull(index)?.let { if (it.text != field.text.toString()) editBlock(index, it.text, field.text.toString()) }
        paragraphFields.remove(index)
    }
    private fun flushParagraphs() {
        if (!ui.typeset || ui.page != Page.Editor) return
        paragraphFields.toMap().forEach { (index, field) -> blocks.getOrNull(index)?.let { if (it.text != field.text.toString()) editBlock(index, it.text, field.text.toString()) } }
    }
    private fun recordEdit(text: String) {
        history.record(historySource, text); historySource = text
        canUndo = history.canUndo; canRedo = history.canRedo
    }
    fun undo(redo: Boolean = false) {
        val field = textState ?: return
        flushParagraphs()
        paragraphFields.clear()
        recordEdit(field.text.toString())
        val next = if (redo) history.redo(historySource) else history.undo(historySource)
        historySource = next
        field.edit { replace(0, length, next) }
        save?.changed(next)
        canUndo = history.canUndo; canRedo = history.canRedo
        blocks = markdownBlocks(next); blockEpoch++
    }
    fun editBlock(index: Int, expected: String, replacement: String): Boolean {
        val field = textState ?: return false
        val block = blocks.getOrNull(index) ?: return false
        if (block.text != expected) return false
        val source = field.text.toString()
        val next = source.replaceRange(block.start + block.prefix.length, block.end, replacement)
        try { TextRules.bytes(next) } catch (e: WorkspaceException) { inputRejected(e.reason); return false }
        val delta = replacement.length - block.text.length
        field.edit { replace(block.start + block.prefix.length, block.end, replacement) }
        blocks = blocks.mapIndexed { n, b -> when { n < index -> b; n == index -> b.copy(end = b.end + delta, text = replacement); else -> b.copy(start = b.start + delta, end = b.end + delta) } }
        recordEdit(next); save?.changed(next)
        return true
    }
    fun inspect(onSuccess: () -> Unit) { launchWork {
        if (!barrier()) return@launchWork
        ui = ui.copy(workspace = disk { store.refresh() })
        onSuccess()
    } }
    fun rename(id: String, name: String, onSuccess: () -> Unit = {}) { launchWork {
        if (!barrier()) return@launchWork
        disk { store.rename(id, name) }
        ui = ui.copy(workspace = disk { store.refresh() })
        onSuccess()
    } }
    private fun acceptWorkspace(next: Workspace) {
        selection = selection?.let { ids -> next.selectionRoots(ids).map { it.id }.toSet() }
        clipboard?.takeIf { it.cut }?.let { clip ->
            val items = clip.items.filter { next.entry(it.entry.id) != null }
            if (items.size != clip.items.size) clipboard = if (items.isEmpty()) null else clip.copy(items = items, token = ++clipboardToken)
        }
        ui = ui.copy(workspace = next,
            selected = ui.selected.takeIf { next.entry(it)?.folder == true } ?: Workspace.ROOT,
            newParent = ui.newParent.takeIf { next.entry(it)?.folder == true } ?: Workspace.ROOT)
    }
    fun startSelection(id: String) {
        if (ui.busy || ui.transitioning || ui.tabTransitioning || ui.page != Page.Directory) return
        val state = ui.workspace ?: return
        if (state.entry(id) == null) return
        selection = if (id == Workspace.ROOT) emptySet() else setOf(id)
        ui = ui.copy(error = null, message = null)
    }
    fun toggleChecked(id: String) {
        if (ui.busy || ui.transitioning) return
        val ids = selection ?: return
        selection = ui.workspace?.toggleSelection(ids, id) ?: ids
    }
    fun clearSelection() { if (!ui.busy && selection != null) selection = emptySet() }
    fun exitSelection() { if (!ui.busy) { selection = null; ui = ui.copy(error = null, message = null) } }
    fun revealHandled(revision: Long) { if (revision == ui.directoryRevision) ui = ui.copy(revealFolder = null) }
    fun copy(id: String, cut: Boolean) = copyAll(listOf(id), cut)
    fun copySelection(cut: Boolean) = copyAll(selection.orEmpty(), cut)
    private fun copyAll(ids: Collection<String>, cut: Boolean) { if (ids.isEmpty()) return; launchWork {
        if (!barrier()) return@launchWork
        val state = ui.workspace ?: throw WorkspaceException(Failure.Missing)
        if (Workspace.ROOT in ids) throw WorkspaceException(Failure.Cycle)
        if (ids.any { state.entry(it) == null }) throw WorkspaceException(Failure.Missing)
        val roots = state.selectionRoots(ids)
        val snapshots = if (cut) emptyList() else disk { store.captureAll(roots.map { it.id }) }
        clipboard = Clipboard(roots.map { ClipboardItem(it, state.path(it.parent ?: Workspace.ROOT)) }, cut, snapshots, ++clipboardToken)
        ui = ui.copy(message = if (cut) R.string.cut_ready else R.string.copy_ready)
    } }
    fun requestPaste(context: String? = null) {
        if (ui.busy || ui.transitioning || ui.tabTransitioning) return
        val clip = clipboard ?: return
        val state = ui.workspace ?: return
        if (context != null && state.entry(context) == null) { ui = ui.copy(error = Failure.Missing); return }
        val ids = context?.let(::listOf) ?: selection.orEmpty()
        pasteRequest = PasteRequest(clip, state.pasteTargets(ids, ui.selected))
    }
    fun dismissPaste() { if (!ui.busy) pasteRequest = null }
    fun confirmPaste(destination: String) { val request = pasteRequest ?: return; paste(destination, request.clipboard.token) }
    fun paste(destination: String, expectedToken: Long? = null) { val clip = clipboard ?: return; launchWork {
        if (expectedToken != null && expectedToken != clip.token) throw WorkspaceException(Failure.Missing)
        if (!barrier()) return@launchWork
        disk { if (clip.cut) store.moveAll(clip.ids, destination) else store.pasteAll(clip.copies, destination) }
        if (clip.cut) clipboard = null
        acceptWorkspace(disk { store.refresh() })
        pasteRequest = null
        ui = ui.copy(selected = if (selection == null) destination else ui.selected, message = R.string.paste_done,
            revealFolder = destination, directoryRevision = ui.directoryRevision + 1)
    } }
    fun requestDelete() {
        if (!ui.busy) deleteRequest = ui.workspace?.selectionRoots(selection.orEmpty())?.map { it.id }?.takeIf { it.isNotEmpty() }
    }
    fun dismissDelete() { if (!ui.busy) deleteRequest = null }
    fun confirmDelete() { deleteRequest?.let(::deleteAll) }
    fun delete(id: String) = deleteAll(listOf(id))
    private fun deleteAll(ids: Collection<String>) { launchWork {
        if (!barrier()) return@launchWork
        val state = ui.workspace ?: return@launchWork
        val roots = state.selectionRoots(ids)
        val removed = roots.flatMap { state.descendants(it.id) }.toSet()
        val selectedParent = state.entry(ui.selected)?.parent ?: Workspace.ROOT
        val newParent = state.entry(ui.newParent)?.parent ?: Workspace.ROOT
        var cleanupFailure = false
        try { disk { store.deleteAll(ids) } }
        catch (e: WorkspaceException) { if (e.reason == Failure.Cleanup) cleanupFailure = true else throw e }
        val deletedActive = ui.activeId in removed
        if (deletedActive) {
            observer?.cancel(); observer = null; save = null; textState = null; paragraphFields.clear()
            departingSession = null
        }
        ui = ui.copy(selected = if (ui.selected in removed) selectedParent else ui.selected,
            newParent = if (ui.newParent in removed) newParent else ui.newParent,
            activeId = if (ui.activeId in removed) null else ui.activeId)
        acceptWorkspace(disk { store.refresh() })
        deleteRequest = null
        if (deletedActive && (ui.page == Page.Editor || ui.page == Page.Previous)) navigate(ui.origin, backwards = true)
        if (cleanupFailure) throw WorkspaceException(Failure.Cleanup)
    } }
    fun preferences(theme: ColorTheme? = null, sort: SortKey? = null, descending: Boolean? = null, foldersFirst: Boolean? = null) {
        val w = ui.workspace ?: return
        val nextTheme = theme ?: w.theme
        val nextSort = sort ?: w.sort
        val nextDescending = descending ?: w.descending
        val nextFoldersFirst = foldersFirst ?: w.foldersFirst
        // A repeated selection must not write the catalog or flash disabled colors.
        if (nextTheme == w.theme && nextSort == w.sort && nextDescending == w.descending && nextFoldersFirst == w.foldersFirst) return
        launchWork(savingPreferences = true) {
            disk { store.settings(nextTheme, nextSort, nextDescending, nextFoldersFirst) }
            ui = ui.copy(workspace = disk { store.refresh() })
        }
    }
    fun prepareExport(): Boolean {
        flushParagraphs()
        val text = textState?.text?.toString() ?: return false
        exportText = text
        exportName = ui.workspace?.entry(ui.activeId.orEmpty())?.name.orEmpty().ifBlank { getApplication<Application>().getString(R.string.untitled) } + ".txt"
        return true
    }
    fun exportCopy() {
        if (prepareExport()) files.text(exportName, checkNotNull(exportText))
    }
    fun licenses() { launchWork {
        licenseTexts = disk { licenseEntries.map { entry -> getApplication<Application>().resources.openRawResource(entry.resource).bufferedReader().use { it.readText() } } }
        navigate(Page.Licenses)
    } }
    fun backups() { launchWork {
        if (!barrier()) return@launchWork
        navigate(Page.Backup)
        backup.refresh()
    } }
    fun export(uri: Uri?) {
        val text = exportText
        if (uri == null) return
        launchWork {
            if (text == null) throw WorkspaceException(Failure.Missing)
            disk {
                if (uri.scheme != "content") throw WorkspaceException(Failure.Missing)
                val resolver = getApplication<Application>().contentResolver
                val bytes = TextRules.bytes(text)
                val stream = resolver.openOutputStream(uri, "w") ?: throw WorkspaceException(Failure.Storage)
                stream.use { it.write(bytes); it.flush() }
            }
            ui = ui.copy(message = R.string.export_done)
        }
    }
    fun import(uri: Uri?, requestId: String? = null, targetParent: String? = null) {
        if (uri == null) return
        viewModelScope.launch {
            loading?.join()
            snapshotFlow { !ui.busy && !ui.transitioning && !ui.tabTransitioning && !backup.locked }.first { it }
            if (requestId != null && requestId in externalRequests) return@launch
            val existing = ui.workspace?.entries?.firstOrNull { requestId != null && it.importRequestId == requestId }
            if (existing != null) { launchWork { openInternal(existing.id, Page.Recent) }; return@launch }
            if (requestId != null && ui.workspace?.lastImportRequestId == requestId) return@launch
            val target = targetParent ?: if (ui.page == Page.New) ui.newParent else Workspace.ROOT
            launchWork {
                if (!barrier()) { if (requestId != null) externalRequests.remove(requestId); return@launchWork }
                val imported = disk {
                    if (uri.scheme != "content") throw WorkspaceException(Failure.InvalidText)
                    val resolver = getApplication<Application>().contentResolver
                    var name = getApplication<Application>().getString(R.string.untitled)
                    try {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && cursor.moveToFirst()) name = cursor.getString(column).orEmpty()
                        }
                    } catch (_: UnsupportedOperationException) { /* Some providers expose streams without metadata queries. */ }
                    catch (_: IllegalArgumentException) { /* Missing metadata is not proof that the stream is unreadable. */ }
                    val mime = resolver.getType(uri)
                    if (mime !in setOf("text/plain", "text/markdown", "text/x-markdown") && !name.endsWith(".txt", true) && !name.endsWith(".md", true)) throw WorkspaceException(Failure.InvalidText)
                    val kind = if (name.endsWith(".md", true) || mime in setOf("text/markdown", "text/x-markdown")) DocumentKind.Markdown else DocumentKind.Plain
                    val stream = resolver.openInputStream(uri) ?: throw WorkspaceException(Failure.Storage)
                    val bytes = stream.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer, 0, minOf(buffer.size, TextRules.MAX_BYTES + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            if (output.size() > TextRules.MAX_BYTES) throw WorkspaceException(Failure.TooLarge)
                        }; output.toByteArray()
                    }
                    name = name.replace(Regex("(?i)\\.(?:txt|md)$"), "").map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }.joinToString("").trim().take(120).ifBlank { getApplication<Application>().getString(R.string.untitled) }
                    Triple(name, kind, TextRules.decode(bytes, imported = true))
                }
                val id = disk { store.create(imported.first, target, imported.second, imported.third, requestId) }
                if (requestId != null) externalRequests.add(requestId)
                openInternal(id, if (ui.page == Page.New) ui.origin else Page.Recent)
                ui = ui.copy(message = R.string.import_done)
            }
        }
    }
    fun previous(id: String? = ui.activeId) { if (id == null) return; launchWork {
        if (!barrier()) return@launchWork
        val text = disk { store.previous(id) }
        ui = ui.copy(previous = text, activeId = id, origin = if (ui.page == Page.Directory) Page.Directory else ui.origin)
        navigate(Page.Previous)
    } }
    fun restorePrevious() { val id = ui.activeId ?: return; launchWork {
        disk { store.restore(id) }
        observer?.cancel(); save = null; textState = null
        openInternal(id, ui.origin)
    } }
}
