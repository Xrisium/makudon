package io.github.xrisium.makudon.feature.workspace

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.*
import io.github.xrisium.makudon.domain.TextRules
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

enum class ImportKind { Text, Archive }
data class ImportRequest(val kind: ImportKind, val id: String = UUID.randomUUID().toString(), val parent: String? = null)
data class FileExchangeUi(
    val visible: Boolean = false,
    val importing: Boolean = false,
    val busy: Boolean = false,
    val apps: List<FileApp> = emptyList(),
    val export: ExportFile? = null,
    val error: Int? = null,
    val message: String? = null,
)

/** One active file operation. Saved instance state never contains manuscript text or file bodies. */
class FileExchange(private val application: Application, private val scope: CoroutineScope) {
    var ui by mutableStateOf(FileExchangeUi())
        private set
    private val copies = ExportFiles(File(application.filesDir, "shared-exports"))
    private var pendingImport: ImportRequest? = null
    private var awaitingImport = false
    private var awaitingExport = false
    private var requestedExport: ExportFile? = null
    private var onClose: (() -> Unit)? = null
    private var prepareAgain: (() -> Unit)? = null

    private fun work(block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, error = null, message = null)
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui = ui.copy(error = if (e is DownloadCleanupException) R.string.file_download_cleanup_error else R.string.file_operation_error) }
            finally { ui = ui.copy(busy = false) }
        }
    }

    fun import(kind: ImportKind, parent: String? = null) {
        if (ui.busy || awaitingImport || awaitingExport) return
        pendingImport = ImportRequest(kind, parent = parent)
        ui = FileExchangeUi(visible = true, importing = true)
        work { ui = ui.copy(apps = withContext(Dispatchers.IO) { AndroidFileAccess.importApps(application, kind == ImportKind.Archive) }) }
    }

    fun launchImport(intent: Intent, launch: (Intent) -> Unit) {
        if (ui.busy || pendingImport == null) return
        try {
            awaitingImport = true
            ui = ui.copy(visible = false, error = null)
            launch(intent)
        } catch (_: android.content.ActivityNotFoundException) { launchFailed(true) }
        catch (_: SecurityException) { launchFailed(true) }
    }

    private fun launchFailed(importing: Boolean) {
        awaitingImport = false; awaitingExport = false
        ui = ui.copy(visible = true, importing = importing, error = R.string.file_app_unavailable)
    }

    fun imported(resultCode: Int, intent: Intent?): Pair<ImportRequest, Uri>? {
        val request = pendingImport
        awaitingImport = false
        return try {
            val uri = AndroidFileAccess.selectedUri(resultCode, intent)
            if (uri == null) {
                if (request != null) ui = ui.copy(visible = true)
                null
            } else {
                checkNotNull(request)
                close()
                request to uri
            }
        } catch (_: Exception) {
            ui = ui.copy(visible = true, importing = request != null, error = if (request == null) R.string.file_request_expired else R.string.file_invalid_selection)
            null
        }
    }

    fun text(name: String, text: String) = prepare(name, "text/plain", null) { output -> output.write(TextRules.bytes(text)) }
    fun archive(name: String, file: File, closed: () -> Unit) = prepare(name, "application/zip", closed) { output -> file.inputStream().use { it.copyTo(output) } }

    private fun prepare(name: String, mime: String, closed: (() -> Unit)?, write: (java.io.OutputStream) -> Unit) {
        if (ui.busy || awaitingImport || awaitingExport) return
        onClose = closed
        prepareAgain = { prepare(name, mime, closed, write) }
        val active = ui.export?.file
        ui = FileExchangeUi(visible = true)
        work {
            val export = withContext(Dispatchers.IO) { copies.prepare(name, mime, active, write) }
            ui = ui.copy(export = export)
            val apps = withContext(Dispatchers.IO) { AndroidFileAccess.apps(application, Intent(Intent.ACTION_SEND).setType(mime)) }
            ui = ui.copy(export = export, apps = apps)
        }
    }

    fun retryPreparation() { if (!ui.busy && ui.export == null) prepareAgain?.invoke() }

    fun saveTo(uri: Uri?) {
        awaitingExport = false
        if (uri == null) return
        val export = ui.export
        if (export == null) { ui = ui.copy(visible = true, error = R.string.file_request_expired); return }
        work {
            withContext(Dispatchers.IO) { AndroidFileAccess.write(application.contentResolver, export, uri) }
            ui = ui.copy(message = application.getString(R.string.file_saved_location))
        }
    }

    fun invalidExportSelection() {
        awaitingExport = false
        ui = ui.copy(visible = true, error = R.string.file_invalid_selection)
    }

    fun created(resultCode: Int, intent: Intent?) {
        val requested = requestedExport
        requestedExport = null
        awaitingExport = false
        try {
            val uri = AndroidFileAccess.selectedUri(resultCode, intent) ?: return
            if (requested == null || ui.export != requested) {
                ui = ui.copy(visible = true, error = R.string.file_request_expired)
                return
            }
            saveTo(uri)
        } catch (_: IllegalArgumentException) { invalidExportSelection() }
    }

    fun download() {
        val export = ui.export ?: return
        if (!AndroidFileAccess.supportsDownloads) return
        work {
            val name = withContext(Dispatchers.IO) { AndroidFileAccess.download(application.contentResolver, export) }
            ui = ui.copy(message = application.getString(R.string.file_download_saved, name))
        }
    }

    fun create(launch: (Intent) -> Unit) {
        val export = ui.export ?: return
        if (ui.busy || awaitingExport || awaitingImport) return
        try {
            awaitingExport = true
            requestedExport = export
            launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = export.mime; putExtra(Intent.EXTRA_TITLE, export.name)
            })
        } catch (_: android.content.ActivityNotFoundException) { launchFailed(false) }
        catch (_: SecurityException) { launchFailed(false) }
    }

    fun share(app: FileApp?, launch: (Intent) -> Unit) {
        val export = ui.export ?: return
        if (awaitingExport || awaitingImport) return
        work {
            // Refresh the lease before handoff, including a retry of a long-lived export session.
            withContext(Dispatchers.IO) { check(checkNotNull(export.file.parentFile).setLastModified(System.currentTimeMillis())) }
            try {
                val intent = AndroidFileAccess.shareIntent(application, export)
                launch(if (app == null) Intent.createChooser(intent, application.getString(R.string.file_share_other)) else intent.setComponent(app.component))
                ui = ui.copy(message = application.getString(R.string.file_shared))
            } catch (_: android.content.ActivityNotFoundException) { launchFailed(false) }
            catch (_: SecurityException) { launchFailed(false) }
            catch (_: IllegalArgumentException) { ui = ui.copy(error = R.string.file_operation_error) }
        }
    }

    fun close() {
        if (ui.busy) return
        pendingImport = null
        prepareAgain = null
        ui = FileExchangeUi()
        val closed = onClose; onClose = null; closed?.invoke()
    }
}
