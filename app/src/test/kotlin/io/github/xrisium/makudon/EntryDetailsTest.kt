package io.github.xrisium.makudon

import io.github.xrisium.makudon.feature.workspace.detailTimestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryDetailsTest {
    @Test fun timestampsUseRequestedZoneAndTwentyFourHourSecondsAcrossDateBoundary() {
        val instant = Instant.parse("2026-11-02T16:05:09Z").toEpochMilli()
        assertEquals("2026/11/03 00:05:09", detailTimestamp(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026/11/02 08:05:09", detailTimestamp(instant, ZoneId.of("America/Los_Angeles")))
        assertEquals("2026/11/02 16:05:09", detailTimestamp(instant, ZoneId.of("UTC")))
    }
}
