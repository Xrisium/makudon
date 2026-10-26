package io.github.xrisium.makudon.feature.workspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.domain.RecentCalendar
import io.github.xrisium.makudon.domain.RecentDateLabel
import io.github.xrisium.makudon.domain.RecentPeriod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

@Composable
internal fun rememberRecentCalendar(): RecentCalendar {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var calendar by remember { mutableStateOf(RecentCalendar.current()) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { calendar = RecentCalendar.current() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // These are system-only broadcasts; the legacy overload also supports API 26.
        context.registerReceiver(receiver, filter)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) calendar = RecentCalendar.current()
        }
        lifecycle.addObserver(observer)
        calendar = RecentCalendar.current()
        onDispose { context.unregisterReceiver(receiver); lifecycle.removeObserver(observer) }
    }
    return calendar
}

internal fun periodLabel(period: RecentPeriod): Int = when (period) {
    RecentPeriod.Today -> R.string.date_today
    RecentPeriod.WithinThreeDays -> R.string.recent_three_days
    RecentPeriod.WithinSevenDays -> R.string.recent_seven_days
    RecentPeriod.Earlier -> R.string.recent_earlier
}

@Composable
internal fun recentDate(timestamp: Long, calendar: RecentCalendar): String {
    val locale = LocalConfiguration.current.locales[0]
    val label = calendar.label(timestamp)
    val date = when (label) {
        RecentDateLabel.Today -> stringResource(R.string.date_today)
        RecentDateLabel.Yesterday -> stringResource(R.string.date_yesterday)
        RecentDateLabel.DayBeforeYesterday -> stringResource(R.string.date_day_before_yesterday)
        else -> SimpleDateFormat(DateFormat.getBestDateTimePattern(locale,
            if (label == RecentDateLabel.SameYear) "MMMd" else "yMMMd"), locale)
            .apply { timeZone = TimeZone.getTimeZone(calendar.zone) }.format(Date(timestamp))
    }
    val time = DateFormat.getTimeFormat(LocalContext.current)
        .apply { timeZone = TimeZone.getTimeZone(calendar.zone) }.format(Date(timestamp))
    return stringResource(R.string.recent_date_time, date, time)
}
