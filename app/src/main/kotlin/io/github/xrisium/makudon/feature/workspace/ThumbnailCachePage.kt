package io.github.xrisium.makudon.feature.workspace

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.ThumbnailUsage

@Composable
internal fun ThumbnailCachePage(model: WorkspaceViewModel) {
    val ui = model.ui
    val preferences = ui.workspace?.thumbnailCaching ?: return
    val enabled = (!ui.busy || ui.savingPreferences) && (!model.cacheBusy || ui.savingPreferences)
    Column(Modifier.fillMaxSize().testTag("thumbnail-cache-page")) {
        PageBar(stringResource(R.string.image_cache), model::closeCache)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.image_cache_options), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    listOf(
                        ThumbnailUsage.Directory to R.string.image_cache_directory,
                        ThumbnailUsage.Menu to R.string.image_cache_menu,
                        ThumbnailUsage.Information to R.string.image_cache_information,
                    ).forEachIndexed { index, (usage, labelId) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val label = stringResource(labelId)
                        Row(Modifier.fillMaxWidth().padding(18.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(Formatter.formatFileSize(LocalContext.current, model.cacheSizes[usage] ?: 0L), Modifier.testTag("thumbnail-cache-size-${usage.name}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(preferences.enabled(usage), onCheckedChange = { value ->
                                model.changeThumbnailCaching(usage, value)
                            }, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }.testTag("thumbnail-cache-${usage.name}"))
                        }
                    }
                }
            }
            Text(stringResource(R.string.image_cache_options_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.image_cache_usage), style = MaterialTheme.typography.titleSmall)
                    Text(Formatter.formatFileSize(LocalContext.current, model.cacheBytes), Modifier.testTag("thumbnail-cache-bytes"))
                    val otherBytes = model.cacheSizes[ThumbnailUsage.Content] ?: 0L
                    if (otherBytes > 0) Text(stringResource(R.string.image_cache_other, Formatter.formatFileSize(LocalContext.current, otherBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.image_cache_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.cacheBusy && !ui.savingPreferences) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.cacheError) Text(stringResource(R.string.image_cache_error), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = model::clearCache, enabled = enabled && model.cacheBytes > 0, modifier = Modifier.testTag("thumbnail-cache-clear")) { Text(stringResource(R.string.image_cache_clear)) }
                }
            }
        }
    }
    model.cacheDisableRequest?.let { usage ->
        AlertDialog(onDismissRequest = model::cancelCacheDisable, title = { Text(stringResource(R.string.image_cache_disable_title)) }, text = {
            Text(stringResource(R.string.image_cache_disable_message, stringResource(thumbnailUsageLabel(usage))), Modifier.verticalScroll(rememberScrollState()))
        }, confirmButton = { TextButton(onClick = model::confirmCacheDisable, enabled = !ui.busy) { Text(stringResource(R.string.image_cache_disable_confirm)) } },
            dismissButton = { TextButton(onClick = model::cancelCacheDisable, enabled = !ui.busy) { Text(stringResource(R.string.cancel)) } })
    }
}


internal fun thumbnailUsageLabel(usage: ThumbnailUsage): Int = when (usage) {
    ThumbnailUsage.Directory -> R.string.image_cache_directory
    ThumbnailUsage.Menu -> R.string.image_cache_menu
    ThumbnailUsage.Information -> R.string.image_cache_information
    ThumbnailUsage.Content -> R.string.image_cache
}
