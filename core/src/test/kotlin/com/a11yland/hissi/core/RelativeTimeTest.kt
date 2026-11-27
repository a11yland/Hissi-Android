package com.a11yland.hissi.core

import java.time.Instant
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

    // Times are always relative, never a date: after two weeks of days come
    // weeks, months and years.
    @Test
    fun daysRunForTwoWeeks() {
        assertEquals("vor 13 Tagen", MonitoredElevator.relativeTime(ago(minutes = 13 * 1440)))
        assertEquals("vor 2 Wochen", MonitoredElevator.relativeTime(ago(minutes = 14 * 1440)))
    }

    @Test
    fun weeksMonthsAndYears() {
        assertEquals("vor 8 Wochen", MonitoredElevator.relativeTime(ago(minutes = 59 * 1440)))
        assertEquals("vor 2 Monaten", MonitoredElevator.relativeTime(ago(minutes = 60 * 1440)))
        assertEquals("vor 13 Monaten", MonitoredElevator.relativeTime(ago(minutes = 400 * 1440)))
        assertEquals("vor 2 Jahren", MonitoredElevator.relativeTime(ago(minutes = 730 * 1440)))
    }

    // TalkBack gets the short units written out.
    @Test
    fun spokenFormWritesTheUnitsOut() {
        assertEquals("vor 1 Minute", MonitoredElevator.relativeTime(ago(minutes = 1), spoken = true))
        assertEquals("vor 5 Minuten", MonitoredElevator.relativeTime(ago(minutes = 5), spoken = true))
        assertEquals("vor 1 Stunde", MonitoredElevator.relativeTime(ago(minutes = 60), spoken = true))
        assertEquals("vor 8 Stunden", MonitoredElevator.relativeTime(ago(minutes = 8 * 60), spoken = true))
        assertEquals("vor 3 Tagen", MonitoredElevator.relativeTime(ago(minutes = 3 * 1440), spoken = true))
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
