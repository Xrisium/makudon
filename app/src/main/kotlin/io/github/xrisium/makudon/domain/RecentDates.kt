package io.github.xrisium.makudon.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class RecentPeriod { Today, WithinThreeDays, WithinSevenDays, Earlier }
enum class RecentDateLabel { Today, Yesterday, DayBeforeYesterday, SameYear, OtherYear }

/** One system-calendar snapshot shared by grouping and every card in a render. */
data class RecentCalendar(val today: LocalDate, val zone: ZoneId) {
    fun date(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    fun period(timestamp: Long): RecentPeriod = when (ChronoUnit.DAYS.between(date(timestamp), today)) {
        in Long.MIN_VALUE..0L -> RecentPeriod.Today // Clock rollback must not hide future-dated drafts.
        in 1L..2L -> RecentPeriod.WithinThreeDays
        in 3L..6L -> RecentPeriod.WithinSevenDays
        else -> RecentPeriod.Earlier
    }

    fun label(timestamp: Long): RecentDateLabel {
        val date = date(timestamp)
        return when (ChronoUnit.DAYS.between(date, today)) {
            0L -> RecentDateLabel.Today
            1L -> RecentDateLabel.Yesterday
            2L -> RecentDateLabel.DayBeforeYesterday
            else -> if (date.year == today.year) RecentDateLabel.SameYear else RecentDateLabel.OtherYear
        }
    }

    companion object {
        fun current(): RecentCalendar {
            val zone = ZoneId.systemDefault()
            return RecentCalendar(LocalDate.now(zone), zone)
        }
    }
}

fun Workspace.recentGroups(calendar: RecentCalendar): Map<RecentPeriod, List<Entry>> = entries
    .filter { !it.folder && it.opened > 0 }
    .sortedByDescending { it.opened }
    .groupBy { calendar.period(it.opened) }
