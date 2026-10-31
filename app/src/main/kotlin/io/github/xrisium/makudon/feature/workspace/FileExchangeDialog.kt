package io.github.xrisium.makudon.feature.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.AndroidFileAccess

@Composable
fun FileExchangeDialog(model: WorkspaceViewModel) {
    val exchange = model.files
    val state = exchange.ui
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        exchange.imported(result.resultCode, result.data)?.let { (request, uri) ->
            when (request.kind) {
                ImportKind.Text -> model.import(uri, request.id, request.parent)
                ImportKind.Archive -> model.backup.read(uri)
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        exchange.created(result.resultCode, result.data)
    }
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = exchange::close,
        title = { Text(stringResource(if (state.importing) R.string.file_select_app else R.string.file_export_method)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val body = state.error?.let { stringResource(it) } ?: state.message ?: state.export?.name
                body?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.importing && state.export == null && state.error != null) TextButton(onClick = exchange::retryPreparation, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
                if (state.importing) {
                    for (app in state.apps) {
                        TextButton(onClick = { exchange.launchImport(AndroidFileAccess.importIntent().setComponent(app.component), importer::launch) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            val bitmap = remember(app.component) { app.icon.toBitmap(40, 40).asImageBitmap() }
                            Image(bitmap, contentDescription = null, Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, Modifier.weight(1f))
                        }
                    }
                    TextButton(onClick = { exchange.launchImport(AndroidFileAccess.importIntent(Intent.ACTION_OPEN_DOCUMENT), importer::launch) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Image(painterResource(R.drawable.ic_android_robot), contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.file_system_picker), Modifier.weight(1f))
                    }
                } else if (state.export != null) {
                    if (AndroidFileAccess.supportsDownloads) Button(onClick = exchange::download, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_save_downloads)) }
                    OutlinedButton(onClick = { exchange.share(null, context::startActivity) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_share_other)) }
                    OutlinedButton(onClick = { exchange.create(exporter::launch) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_system_save)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = exchange::close, enabled = !state.busy) { Text(stringResource(R.string.close)) } },
    )
}
