package io.github.xrisium.makudon.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xrisium.makudon.R

internal data class LicenseEntry(@param:StringRes val title: Int, @param:StringRes val license: Int, @param:StringRes val description: Int?, @param:RawRes val resource: Int)

internal val licenseEntries = listOf(
    LicenseEntry(R.string.notice_noto, R.string.notice_ofl, R.string.notice_noto_description, R.raw.notice_noto),
    LicenseEntry(R.string.notice_noto_serif, R.string.notice_ofl, null, R.raw.notice_noto_serif),
    LicenseEntry(R.string.notice_wenkai, R.string.notice_ofl, null, R.raw.notice_wenkai),
    LicenseEntry(R.string.notice_lucide, R.string.notice_isc_mit, R.string.notice_lucide_description, R.raw.notice_lucide),
    LicenseEntry(R.string.notice_fluent, R.string.notice_mit, R.string.notice_fluent_description, R.raw.notice_fluent),
    LicenseEntry(R.string.notice_fredoka, R.string.notice_ofl, R.string.notice_fredoka_description, R.raw.notice_fredoka),
    LicenseEntry(R.string.notice_coroutines, R.string.notice_apache, null, R.raw.notice_coroutines),
    LicenseEntry(R.string.notice_androidx, R.string.notice_apache, null, R.raw.notice_androidx),
    LicenseEntry(R.string.notice_android_robot, R.string.notice_cc, null, R.raw.notice_android_robot),
)

@Composable
internal fun LicensesPage(model: WorkspaceViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val entry = licenseEntries.getOrNull(selected)
    val active = model.ui.page == Page.Licenses && !model.ui.transitioning
    BackHandler(enabled = active && entry != null) { selected = -1 }
    Column(Modifier.fillMaxSize()) {
        PageBar(if (entry == null) stringResource(R.string.licenses) else stringResource(entry.title), back = {
            if (entry == null) model.back() else if (active) selected = -1
        }, maxTitleLines = Int.MAX_VALUE)
        if (entry == null) {
            LazyColumn(state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(licenseEntries, key = { _, item -> item.resource }) { index, item ->
                    Surface(onClick = { selected = index }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(item.license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Glyph(R.drawable.ic_chevron_right)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(stringResource(entry.license), style = MaterialTheme.typography.titleMedium)
                entry.description?.let { Text(stringResource(it), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                Text(model.licenseTexts.getOrNull(selected).orEmpty().trim(), Modifier.padding(top = 20.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp))
            }
        }
    }
}
