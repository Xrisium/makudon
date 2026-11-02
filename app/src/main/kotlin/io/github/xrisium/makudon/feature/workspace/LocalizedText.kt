package io.github.xrisium.makudon.feature.workspace

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.NameSuffix
import io.github.xrisium.makudon.domain.Workspace

internal fun Context.copyNaming() = NameSuffix(getString(R.string.copy_suffix), getString(R.string.copy_suffix_number))
internal fun Context.restoreNaming() = NameSuffix(getString(R.string.restore_copy_suffix), getString(R.string.restore_copy_suffix_number))

/** Only the synthetic root label is translated; persistent names and paths stay untouched. */
@Composable
internal fun displayPath(workspace: Workspace, id: String): String =
    if (id == Workspace.ROOT) stringResource(R.string.library) else workspace.path(id)

@Composable
internal fun displayDate(timestamp: Long): String {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val date = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
    val day = java.text.SimpleDateFormat(date, locale).format(java.util.Date(timestamp))
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(timestamp))
    return stringResource(R.string.recent_date_time, day, time)
}
