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
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File

enum class Page { Recent, Directory, Settings, New, Editor, Licenses, Previous }
data class WorkspaceUi(
    val workspace: Workspace? = null,
    val page: Page = Page.Recent,
    val busy: Boolean = true,
    val error: Failure? = null,
    val message: Int? = null,
    val selected: String = Workspace.ROOT,
    val newParent: String = Workspace.INBOX,
    val origin: Page = Page.Recent,
    val activeId: String? = null,
    val typeset: Boolean = true,
    val previous: String? = null,
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
    data class Clipboard(val id: String, val cut: Boolean, val copy: WorkspaceStore.Copy? = null)
    var clipboard by mutableStateOf<Clipboard?>(null)
        private set
    private val ioLock = Mutex()
    private val store = WorkspaceStore(File(application.filesDir, "workspace"), initialNames = application.getString(R.string.library) to application.getString(R.string.inbox))
    private var observer: Job? = null
    private var exportText: String? = null
    var licenseText by mutableStateOf("")
        private set
    private var loading: Job? = null
    private val externalRequests = mutableSetOf<String>()
    val pendingExportName: String get() = ui.workspace?.entry(ui.activeId.orEmpty())?.name.orEmpty() + ".txt"

    init { reload() }
    private suspend fun <T> disk(block: () -> T): T = withContext(Dispatchers.IO + NonCancellable) { ioLock.withLock { block() } }
    private fun failure(e: Exception) = (e as? WorkspaceException)?.reason ?: Failure.Storage
    private fun launchWork(block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui = ui.copy(error = failure(e)) }
            finally { ui = ui.copy(busy = false) }
        }
    }
    fun reload() {
        if (loading?.isActive == true) return
        ui = ui.copy(busy = true, error = null)
        loading = viewModelScope.launch {
            try { ui = ui.copy(workspace = disk { store.load() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui = ui.copy(error = failure(e)) }
            finally { ui = ui.copy(busy = false) }
        }
    }
    private suspend fun barrier(retry: Boolean = false): Boolean {
        flushParagraphs()
        val coordinator = save ?: return true
        while (true) {
            textState?.let { coordinator.changed(it.text.toString()) }
            if (!coordinator.flush(retry)) return false
            if (textState?.text?.toString() == coordinator.latest) return true
        }
    }
    fun backgroundSave() { viewModelScope.launch { barrier() } }
    fun retrySave() { launchWork { barrier(true) } }
    fun dismissError() { ui = ui.copy(error = null, message = null) }
    fun inputRejected(reason: Failure = Failure.TooLarge) { ui = ui.copy(error = reason) }
    fun select(id: String) { if (ui.workspace?.entry(id)?.folder == true) ui = ui.copy(selected = id) }
    fun go(page: Page) { launchWork {
        if (!barrier()) return@launchWork
        observer?.cancel(); observer = null; save = null; textState = null
        paragraphFields.clear()
        ui = ui.copy(page = page, workspace = disk { store.refresh() }, activeId = null)
    } }
    fun newDocument(from: Page = ui.page, parent: String? = null) {
        val state = ui.workspace ?: return
        val target = parent ?: if (from == Page.Recent) { if (state.entry(Workspace.INBOX) != null) Workspace.INBOX else Workspace.ROOT } else ui.selected
        ui = ui.copy(page = Page.New, newParent = target, origin = from, error = null)
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
        observer?.cancel()
        paragraphFields.clear()
        val field = TextFieldState(text)
        val session = SaveCoordinator(viewModelScope, text, SystemClock::uptimeMillis) { value -> disk { store.save(id, value) } }
        textState = field; save = session
        history = EditHistory(); historySource = text; canUndo = false; canRedo = false
        blocks = markdownBlocks(text); blockEpoch++
        ui = ui.copy(page = Page.Editor, activeId = id, origin = origin, typeset = true, workspace = disk { store.refresh() })
        observer = viewModelScope.launch { snapshotFlow { field.text.toString() }.collect { recordEdit(it); session.changed(it) } }
    }
    fun back() { when (ui.page) {
        Page.Editor -> go(ui.origin)
        Page.New -> { ui = ui.copy(page = ui.origin) }
        Page.Licenses -> { ui = ui.copy(page = Page.Settings) }
        Page.Previous -> { ui = ui.copy(page = if (textState != null) Page.Editor else ui.origin, previous = null) }
        else -> Unit
    } }
    fun typeset(value: Boolean) { flushParagraphs(); paragraphFields.clear(); if (value) { blocks = markdownBlocks(textState?.text?.toString().orEmpty()); blockEpoch++ }; ui = ui.copy(typeset = value) }
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
    fun rename(id: String, name: String, onSuccess: () -> Unit = {}) { launchWork {
        if (!barrier()) return@launchWork
        disk { store.rename(id, name) }
        ui = ui.copy(workspace = disk { store.refresh() })
        onSuccess()
    } }
    fun copy(id: String, cut: Boolean) { launchWork {
        if (!barrier()) return@launchWork
        val snapshot = if (cut) null else disk { store.capture(id) }
        clipboard = Clipboard(id, cut, snapshot)
        ui = ui.copy(message = if (cut) R.string.cut_ready else R.string.copy_ready)
    } }
    fun paste(destination: String) { val clip = clipboard ?: return; launchWork {
        if (!barrier()) return@launchWork
        disk { if (clip.cut) store.move(clip.id, destination) else store.paste(clip.copy ?: throw WorkspaceException(Failure.Missing), destination) }
        if (clip.cut) clipboard = null
        ui = ui.copy(workspace = disk { store.refresh() }, selected = destination, message = R.string.paste_done)
    } }
    fun delete(id: String) { launchWork {
        if (!barrier()) return@launchWork
        val state = ui.workspace ?: return@launchWork
        val removed = state.descendants(id)
        val parent = state.entry(id)?.parent ?: return@launchWork
        try { disk { store.delete(id) } } finally {
            val next = disk { store.refresh() }
            if (next.entry(id) == null) {
                if (clipboard?.let { it.cut && it.id in removed } == true) clipboard = null
                if (ui.activeId in removed) { observer?.cancel(); observer = null; save = null; textState = null }
                ui = ui.copy(workspace = next, selected = if (ui.selected in removed) parent else ui.selected, newParent = if (ui.newParent in removed) parent else ui.newParent, activeId = if (ui.activeId in removed) null else ui.activeId)
            }
        }
    } }
    fun preferences(theme: ColorTheme? = null, sort: SortKey? = null, descending: Boolean? = null, foldersFirst: Boolean? = null) { launchWork {
        val w = ui.workspace ?: return@launchWork
        disk { store.settings(theme ?: w.theme, sort ?: w.sort, descending ?: w.descending, foldersFirst ?: w.foldersFirst) }
        ui = ui.copy(workspace = disk { store.refresh() })
    } }
    fun prepareExport(): Boolean {
        flushParagraphs()
        val text = textState?.text?.toString() ?: return false
        exportText = text
        return true
    }
    fun licenses() { launchWork {
        licenseText = disk { getApplication<Application>().resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
        ui = ui.copy(page = Page.Licenses)
    } }
    fun export(uri: Uri?) {
        val text = exportText; exportText = null
        if (uri == null) return
        launchWork {
            if (text == null) throw WorkspaceException(Failure.Missing)
            disk {
                val resolver = getApplication<Application>().contentResolver
                val bytes = TextRules.bytes(text)
                val stream = resolver.openOutputStream(uri, "w") ?: throw WorkspaceException(Failure.Storage)
                stream.use { it.write(bytes); it.flush() }
            }
            ui = ui.copy(message = R.string.export_done)
        }
    }
    fun import(uri: Uri?, requestId: String? = null) {
        if (uri == null) return
        viewModelScope.launch {
            loading?.join()
            if (requestId != null && requestId in externalRequests) return@launch
            if (ui.busy) { ui = ui.copy(error = Failure.Storage); return@launch }
            val existing = ui.workspace?.entries?.firstOrNull { requestId != null && it.importRequestId == requestId }
            if (existing != null) { launchWork { openInternal(existing.id, Page.Recent) }; return@launch }
            if (requestId != null && ui.workspace?.lastImportRequestId == requestId) return@launch
            val target = if (ui.page == Page.New) ui.newParent else if (ui.workspace?.entry(Workspace.INBOX) != null) Workspace.INBOX else Workspace.ROOT
            launchWork {
                if (!barrier()) { if (requestId != null) externalRequests.remove(requestId); return@launchWork }
                val imported = disk {
                    if (uri.scheme != "content") throw WorkspaceException(Failure.InvalidText)
                    val resolver = getApplication<Application>().contentResolver
                    var name = getApplication<Application>().getString(R.string.untitled)
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty() }
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
        ui = ui.copy(previous = text, activeId = id, origin = if (ui.page == Page.Directory) Page.Directory else ui.origin, page = Page.Previous)
    } }
    fun restorePrevious() { val id = ui.activeId ?: return; launchWork {
        disk { store.restore(id) }
        observer?.cancel(); save = null; textState = null
        openInternal(id, ui.origin)
    } }
}
