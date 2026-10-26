package io.github.xrisium.makudon

import io.github.xrisium.makudon.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecentDatesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 26)
    private val calendar = RecentCalendar(today, zone)
    private fun timestamp(date: LocalDate, hour: Int = 12, zone: ZoneId = this.zone) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun groupsUseCalendarBoundariesAndNonoverlappingWindows() {
        val expected = listOf(RecentPeriod.Today, RecentPeriod.WithinThreeDays, RecentPeriod.WithinThreeDays,
            RecentPeriod.WithinSevenDays, RecentPeriod.WithinSevenDays, RecentPeriod.WithinSevenDays,
            RecentPeriod.WithinSevenDays, RecentPeriod.Earlier)
        expected.forEachIndexed { days, period ->
            for (hour in listOf(0, 23)) assertEquals("days=$days hour=$hour", period, calendar.period(timestamp(today.minusDays(days.toLong()), hour)))
        }
        assertEquals(RecentPeriod.Today, calendar.period(timestamp(today.plusDays(1))))
    }

    @Test fun dateLabelsPreferRelativeDaysBeforeYearRules() {
        val newYear = RecentCalendar(LocalDate.of(2026, 1, 1), zone)
        assertEquals(RecentDateLabel.Today, newYear.label(timestamp(LocalDate.of(2026, 1, 1))))
        assertEquals(RecentDateLabel.Yesterday, newYear.label(timestamp(LocalDate.of(2025, 12, 31))))
        assertEquals(RecentDateLabel.DayBeforeYesterday, newYear.label(timestamp(LocalDate.of(2025, 12, 30))))
        assertEquals(RecentDateLabel.OtherYear, newYear.label(timestamp(LocalDate.of(2025, 12, 29))))
        assertEquals(RecentDateLabel.SameYear, calendar.label(timestamp(LocalDate.of(2026, 1, 1))))
        assertEquals(RecentDateLabel.OtherYear, calendar.label(timestamp(LocalDate.of(2025, 10, 26))))
        assertEquals(RecentDateLabel.SameYear, calendar.label(timestamp(today.plusDays(1))))
    }

    @Test fun timezoneChangesReinterpretBothTodayAndStoredInstant() {
        val instant = Instant.parse("2026-10-25T23:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-10-26T01:00:00Z")
        val shanghai = RecentCalendar(now.atZone(zone).toLocalDate(), zone)
        val utc = RecentCalendar(now.atZone(ZoneId.of("UTC")).toLocalDate(), ZoneId.of("UTC"))
        assertEquals(RecentPeriod.Today, shanghai.period(instant))
        assertEquals(RecentPeriod.WithinThreeDays, utc.period(instant))
        assertEquals(RecentDateLabel.Today, shanghai.label(instant))
        assertEquals(RecentDateLabel.Yesterday, utc.label(instant))
    }

    @Test fun daylightSavingUsesDaysInsteadOfDurations() {
        val newYork = ZoneId.of("America/New_York")
        for (date in listOf(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 11, 2))) {
            val local = RecentCalendar(date, newYork)
            assertEquals(RecentDateLabel.Yesterday, local.label(timestamp(date.minusDays(1), 0, newYork)))
            assertEquals(RecentPeriod.WithinThreeDays, local.period(timestamp(date.minusDays(2), 0, newYork)))
            assertEquals(RecentPeriod.WithinSevenDays, local.period(timestamp(date.minusDays(6), 23, newYork)))
            assertEquals(RecentPeriod.Earlier, local.period(timestamp(date.minusDays(7), 23, newYork)))
        }
    }

    @Test fun midnightMovesCardsAndRelativeDatesWithoutChangingMetadata() {
        val opened = timestamp(today, 23)
        val tomorrow = calendar.copy(today = today.plusDays(1))
        assertEquals(RecentPeriod.Today, calendar.period(opened))
        assertEquals(RecentPeriod.WithinThreeDays, tomorrow.period(opened))
        assertEquals(RecentDateLabel.Yesterday, tomorrow.label(opened))
        assertEquals(RecentPeriod.WithinSevenDays, calendar.copy(today = today.plusDays(3)).period(opened))
        assertEquals(RecentPeriod.Earlier, calendar.copy(today = today.plusDays(7)).period(opened))
    }

    @Test fun recentGroupsKeepLastOpenedOrderExcludeFoldersAndHideEmptyGroups() {
        fun document(id: String, days: Long) = Entry(id, id, Workspace.ROOT, DocumentKind.Plain,
            timestamp(today), opened = timestamp(today.minusDays(days)), modified = timestamp(today))
        val newest = document("newest", 0)
        val old = document("old", 7)
        val yesterday = document("yesterday", 1)
        val twoDays = document("twoDays", 2)
        val workspace = Workspace(listOf(old, twoDays, newest.copy(id = "folder", kind = DocumentKind.Folder),
            yesterday, newest.copy(id = "unopened", opened = 0), newest))
        val groups = workspace.recentGroups(calendar)
        assertEquals(listOf(RecentPeriod.Today, RecentPeriod.WithinThreeDays, RecentPeriod.Earlier), groups.keys.toList())
        assertEquals(listOf(newest, yesterday, twoDays, old), groups.values.flatten())
        assertTrue(Workspace(emptyList()).recentGroups(calendar).isEmpty())
    }
}
