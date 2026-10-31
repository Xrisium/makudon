package io.github.xrisium.makudon.data

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider

data class FileApp(val component: ComponentName, val label: String, val icon: Drawable) {
    val huawei: Boolean get() = component.packageName == "com.huawei.filemanager"
}

/** Android URI and Intent handling stays outside the private library and Compose UI. */
object AndroidFileAccess {
    fun importIntent(action: String = Intent.ACTION_GET_CONTENT): Intent = Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun apps(context: Context, intent: Intent): List<FileApp> {
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return matches.filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.packageName != context.packageName }
            .map { FileApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(context.packageManager).toString(), it.loadIcon(context.packageManager)) }
            .distinctBy { it.component }
            .sortedWith(compareByDescending<FileApp> { it.huawei }.thenBy { it.label }.thenBy { it.component.flattenToString() })
    }

    fun importApps(context: Context, archive: Boolean): List<FileApp> {
        // Query relevant types, but launch with */*: vendors frequently mislabel .md and ZIP.
        // This excludes image/audio-only pickers from a document-selection app list.
        val types = if (archive) listOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            else listOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")
        return types.flatMap { apps(context, importIntent().setType(it)) }.distinctBy { it.component }
            .sortedWith(compareByDescending<FileApp> { it.huawei }.thenBy { it.label }.thenBy { it.component.flattenToString() })
    }

    /** Null means cancellation. Invalid or multi-file successful results must be reported, not imported. */
    fun selectedUri(resultCode: Int, data: Intent?): Uri? {
        if (resultCode == Activity.RESULT_CANCELED) return null
        require(resultCode == Activity.RESULT_OK) { "File selection failed" }
        val clip = data?.clipData
        require(clip == null || clip.itemCount == 1) { "Select one file" }
        val uri = data?.data ?: clip?.getItemAt(0)?.uri
        require(uri != null && uri.scheme == "content") { "Missing readable file URI" }
        require(clip == null || data.data == null || data.data == clip.getItemAt(0).uri) { "Ambiguous file result" }
        return uri
    }

    fun shareIntent(context: Context, source: ExportFile): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", source.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = source.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(source.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun write(resolver: ContentResolver, source: ExportFile, uri: Uri) {
        require(uri.scheme == "content")
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { ExportFiles.copy(source, it) }
    }

    @RequiresApi(29)
    fun download(resolver: ContentResolver, source: ExportFile, collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, source.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Makudon")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Cannot create download" }
        try {
            write(resolver, source, uri)
            checkNotNull(resolver.openInputStream(uri)).use { ExportFiles.verify(source, it) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "Cannot publish download" }
            // MediaProvider can finalize a collision suffix only when publishing the pending item.
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: throw IllegalStateException("Cannot identify download")
            return name
        } catch (e: Exception) {
            try { check(resolver.delete(uri, null, null) == 1) { "Cannot clean incomplete download" } }
            catch (cleanup: Exception) { throw DownloadCleanupException(e, cleanup) }
            throw e
        }
    }

    val supportsDownloads: Boolean get() = Build.VERSION.SDK_INT >= 29
}

class DownloadCleanupException(cause: Exception, cleanup: Exception) : Exception("Incomplete download could not be removed", cause) {
    init { addSuppressed(cleanup) }
}
