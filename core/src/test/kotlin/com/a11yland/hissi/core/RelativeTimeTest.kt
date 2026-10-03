package com.a11yland.hissi.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/RelativeTimeTests.swift.

private fun elevator(checked: Instant? = null, updated: Instant? = null) = MonitoredElevator(
    id = "e1",
    stationId = "900193002",
    elevatorId = "e1",
    stationName = "S Adlershof (Berlin)",
    elevatorDescription = "",
    isWorking = true,
    lastCheckedEpochMillis = checked?.toEpochMilli(),
    lastUpdatedEpochMillis = updated?.toEpochMilli(),
)

// A few seconds of slack keeps the minute buckets stable regardless of test
// execution time.
private fun ago(minutes: Int): Instant = Instant.now().minusSeconds(minutes * 60L + 5)

class RelativeTimeTest {
    @Test
    fun underOneMinuteIsGeradeEben() {
        assertEquals("gerade eben", MonitoredElevator.relativeTime(Instant.now().minusSeconds(10)))
    }

    @Test
    fun minutesBucket() {
        assertEquals("vor 5 Min.", MonitoredElevator.relativeTime(ago(minutes = 5)))
    }

    @Test
    fun lastMinuteBeforeHours() {
        assertEquals("vor 59 Min.", MonitoredElevator.relativeTime(ago(minutes = 59)))
    }

    @Test
    fun sixtyMinutesRollsOverToHours() {
        assertEquals("vor 1 Std.", MonitoredElevator.relativeTime(ago(minutes = 60)))
    }

    @Test
    fun hoursBucket() {
        assertEquals("vor 2 Std.", MonitoredElevator.relativeTime(ago(minutes = 150)))
    }

    @Test
    fun lastHourBeforeDays() {
        assertEquals("vor 23 Std.", MonitoredElevator.relativeTime(ago(minutes = 23 * 60)))
    }

    @Test
    fun twentyFourHoursRollsOverToSingularDay() {
        assertEquals("vor 1 Tag", MonitoredElevator.relativeTime(ago(minutes = 24 * 60)))
    }

    @Test
    fun daysBucketIsPlural() {
        assertEquals("vor 3 Tagen", MonitoredElevator.relativeTime(ago(minutes = 3 * 24 * 60)))
    }

    // The date follows the user's locale; the German "am" prefix mirrors the
    // source-language key, as in the Swift package without a string catalog.
    @Test
    fun beyondAWeekIsAbsoluteLocalizedDate() {
        val date = Instant.now().minusSeconds(400L * 24 * 60 * 60)
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        assertEquals("am ${formatter.format(date)}", MonitoredElevator.relativeTime(date))
    }
}

class ElevatorLabelTest {
    @Test
    fun checkedLabelFallsBackWhenNil() {
        assertEquals("Noch nie geprüft", elevator(checked = null).lastCheckedLabel)
    }

    @Test
    fun checkedLabelPrefixesRelativeTime() {
        assertEquals("Geprüft vor 3 Min.", elevator(checked = ago(minutes = 3)).lastCheckedLabel)
    }

    @Test
    fun updatedLabelFallsBackWhenNil() {
        assertEquals("Stand unbekannt", elevator(updated = null).lastUpdatedLabel)
    }

    @Test
    fun updatedLabelPrefixesRelativeTime() {
        assertEquals("Stand vor 3 Min.", elevator(updated = ago(minutes = 3)).lastUpdatedLabel)
    }
}

class StaleDataTest {
    @Test
    fun freshDataIsNotStale() {
        assertFalse(elevator(updated = ago(minutes = 60)).isDataStale)
    }

    @Test
    fun justUnderThresholdIsNotStale() {
        assertFalse(
            elevator(updated = ago(minutes = (MonitoredElevator.STALE_AFTER_DAYS * 1440) - 10)).isDataStale,
        )
    }

    @Test
    fun beyondThresholdIsStale() {
        assertTrue(
            elevator(updated = ago(minutes = (MonitoredElevator.STALE_AFTER_DAYS + 1) * 1440)).isDataStale,
        )
    }

    @Test
    fun missingUpdateDateIsNotStale() {
        assertFalse(elevator(updated = null).isDataStale)
    }
}
