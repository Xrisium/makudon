package io.github.xrisium.makudon.feature.workspace

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BackupUi(
    val busy: Boolean = false,
    val committing: Boolean = false,
    val progress: Int? = null,
    val error: Failure? = null,
    val detail: String? = null,
    val message: Int? = null,
    val inventory: ArchiveInventory? = null,
    val mode: RestoreMode = RestoreMode.Separate,
    val plan: RestorePlan? = null,
    val exportReady: Boolean = false,
    val exportRenames: List<ArchiveRename>? = null,
    val snapshot: RestoreSnapshot? = null,
    val needsReload: Boolean = false,
)

/** Owns private disk staging; neither Compose nor saved instance state holds archive bodies. */
class BackupCoordinator(
    private val scope: CoroutineScope,
    private val application: Application,
    private val lock: Mutex,
    private val library: LibraryController,
    private val barrier: suspend () -> Boolean,
    private val switched: (Workspace) -> Unit,
) {
    var ui by mutableStateOf(BackupUi())
        private set
    private var job: Job? = null
    private var stage: File? = null
    private var archive: PreparedArchive? = null
    private var baseline: String? = null
    private var exporting: ArchiveExport? = null
    private var exportFile: File? = null
    private var decisions = emptyMap<String, ConflictChoice>()
    private var restoreFolder = ""
    private var restoreTime = 0L
    val locked: Boolean get() = ui.busy || stage != null || ui.needsReload
    val pendingExportName: String get() = application.getString(R.string.backup_filename, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()))

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
    private fun run(progress: Int, action: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, progress = progress, error = null, detail = null, message = null)
        job = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = (e as? WorkspaceException)?.reason ?: Failure.Storage
                ui = ui.copy(error = reason, detail = (e as? WorkspaceException)?.detail, needsReload = ui.committing)
            } finally { ui = ui.copy(busy = false, committing = false, progress = null) }
        }
    }
    private fun newStage(): File {
        if (stage != null) throw WorkspaceException(Failure.Conflict)
        return File(application.cacheDir, "library-archives/${UUID.randomUUID()}").also {
            if (!it.mkdirs()) throw WorkspaceException(Failure.Storage)
            stage = it
        }
    }
    fun refresh() { run(R.string.backup_checking) {
        if (ui.needsReload) {
            val current = io { library.load() }
            switched(current)
            ui = ui.copy(needsReload = false)
        }
        ui = ui.copy(snapshot = io { library.snapshot() })
    } }
    fun chooseMode(mode: RestoreMode) { if (!ui.busy) ui = ui.copy(mode = mode, plan = null, error = null) }

    fun read(uri: Uri?) {
        if (uri == null || locked) return
        run(R.string.backup_reading) {
            if (!barrier()) throw WorkspaceException(Failure.Storage)
            val currentJob = currentCoroutineContext().job
            val prepared = io {
                if (uri.scheme != "content") throw WorkspaceException(Failure.InvalidArchive)
                val folder = newStage()
                val zip = File(folder, "source.zip")
                val input = application.contentResolver.openInputStream(uri) ?: throw WorkspaceException(Failure.Storage)
                input.use { LibraryArchive.copySource(it, zip) { currentJob.ensureActive() } }
                LibraryArchive.read(zip, File(folder, "contents"), { currentJob.ensureActive() })
            }
            archive = prepared
            baseline = io { library.fingerprint { currentJob.ensureActive() } }
            restoreTime = System.currentTimeMillis()
            restoreFolder = application.getString(R.string.backup_folder_name, SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date(restoreTime)))
            decisions = emptyMap()
            ui = ui.copy(inventory = prepared.inventory, mode = RestoreMode.Separate, plan = null)
        }
    }

    fun preview() { val source = archive ?: return; run(R.string.backup_planning) {
        val currentJob = currentCoroutineContext().job
        val planned = io {
            if (library.fingerprint { currentJob.ensureActive() } != baseline) throw WorkspaceException(Failure.StaleRestore)
            RestorePlanner.plan(library.store.refresh(), source.inventory, ui.mode, decisions, restoreFolder, restoreTime) { local, incoming ->
                currentJob.ensureActive()
                library.store.read(local) == source.text(incoming)
            }
        }
        ui = ui.copy(plan = planned)
    } }

    fun decide(id: String, choice: ConflictChoice) {
        if (ui.busy || ui.plan?.conflicts?.none { it.source.id == id } != false) return
        decisions = decisions + (id to choice)
        preview()
    }

    fun restore() { val plan = ui.plan ?: return; val source = archive ?: return
        if (!plan.resolved || ui.needsReload) return
        run(R.string.backup_preparing) {
            if (!barrier()) throw WorkspaceException(Failure.Storage)
            if (!plan.changed) {
                discard()
                ui = ui.copy(message = R.string.backup_unchanged)
                return@run
            }
            val currentJob = currentCoroutineContext().job
            val candidate = io { library.prepare(plan, source, baseline ?: throw WorkspaceException(Failure.StaleRestore)) { currentJob.ensureActive() } }
            try {
                currentCoroutineContext().ensureActive()
                ui = ui.copy(committing = true, progress = R.string.backup_committing)
                withContext(NonCancellable) {
                    var cleanupFailed = false
                    val current = withContext(Dispatchers.IO) { lock.withLock {
                        try { library.commit(candidate) }
                        catch (e: WorkspaceException) { if (e.reason == Failure.Cleanup) cleanupFailed = true else throw e }
                        library.store.refresh()
                    } }
                    switched(current)
                    discard()
                    ui = ui.copy(snapshot = io { library.snapshot() }, message = if (cleanupFailed) R.string.backup_done_cleanup else R.string.backup_restored)
                }
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { lock.withLock { library.abandon(candidate) } }
            }
        }
    }

    fun prepareExport() {
        if (locked) return
        run(R.string.backup_preparing_export) {
            if (!barrier()) throw WorkspaceException(Failure.Storage)
            val plan = io { newStage(); LibraryArchive.exportPlan(library.store.refresh()) }
            exporting = plan
            if (plan.renames.isNotEmpty()) ui = ui.copy(exportRenames = plan.renames)
            else generateExport()
        }
    }
    fun confirmExportNames() { if (ui.exportRenames == null) return; run(R.string.backup_preparing_export) { generateExport() } }
    private suspend fun generateExport() {
        val plan = exporting ?: throw WorkspaceException(Failure.Missing)
        val folder = stage ?: throw WorkspaceException(Failure.Missing)
        val currentJob = currentCoroutineContext().job
        val file = io {
            File(folder, "library.zip").also { LibraryArchive.write(it, plan, { id -> library.store.read(id) }) { currentJob.ensureActive() } }
        }
        exportFile = file
        ui = ui.copy(exportReady = true, exportRenames = null)
    }
    fun export(uri: Uri?) {
        if (uri == null || !ui.exportReady) return
        val file = exportFile ?: return
        run(R.string.backup_writing) {
            val currentJob = currentCoroutineContext().job
            io {
                if (uri.scheme != "content") throw WorkspaceException(Failure.Storage)
                val output = application.contentResolver.openOutputStream(uri, "w") ?: throw WorkspaceException(Failure.Storage)
                output.use { target -> file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) { currentJob.ensureActive(); val n = input.read(buffer); if (n < 0) break; target.write(buffer, 0, n) }
                    target.flush()
                } }
            }
            discard()
            ui = ui.copy(message = R.string.backup_exported)
        }
    }

    fun undo() { if (locked) return; run(R.string.backup_preparing) {
        if (!barrier()) throw WorkspaceException(Failure.Storage)
        ui = ui.copy(committing = true, progress = R.string.backup_committing)
        withContext(NonCancellable) {
            val current = withContext(Dispatchers.IO) { lock.withLock { library.undo(); library.store.refresh() } }
            switched(current)
            ui = ui.copy(snapshot = io { library.snapshot() }, message = R.string.backup_undone)
        }
    } }
    fun clearSnapshot() { if (locked) return; run(R.string.backup_cleaning) {
        ui = ui.copy(committing = true)
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { lock.withLock { library.clearSnapshot() } }
            ui = ui.copy(snapshot = null, message = R.string.backup_snapshot_cleared)
        }
    } }

    private suspend fun discard() {
        val folder = stage
        archive = null; baseline = null; exporting = null; exportFile = null; decisions = emptyMap()
        stage = null
        ui = ui.copy(inventory = null, plan = null, exportReady = false, exportRenames = null, mode = RestoreMode.Separate)
        if (folder != null) withContext(Dispatchers.IO + NonCancellable) {
            if (folder.exists() && !folder.deleteRecursively()) throw WorkspaceException(Failure.Cleanup)
        }
    }
    /** Returns false only during the non-cancellable publication window. */
    fun cancel(): Boolean {
        if (ui.committing) return false
        val running = job
        if (ui.busy) running?.cancel()
        scope.launch {
            running?.join()
            try { discard(); ui = ui.copy(error = null, detail = null, message = null) }
            catch (e: Exception) { ui = ui.copy(error = (e as? WorkspaceException)?.reason ?: Failure.Storage) }
        }
        return true
    }
}
