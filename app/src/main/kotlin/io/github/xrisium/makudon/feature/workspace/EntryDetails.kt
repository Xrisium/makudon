package io.github.xrisium.makudon.feature.workspace

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val detailDateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)

internal fun detailTimestamp(timestamp: Long, zone: ZoneId): String =
    detailDateFormat.format(Instant.ofEpochMilli(timestamp).atZone(zone))
