package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported 1:1 from the transition half of the Swift LiveStatusTests — the
// session-related suites (mode, state, end rule) have no Android counterpart.
class DisruptionAlertTest {
    private fun elevator(
        id: String,
        isWorking: Boolean?,
        station: String = "Station",
    ) = MonitoredElevator(
        id = id,
        stationId = "s",
        elevatorId = id,
        stationName = station,
        elevatorDescription = "",
        isWorking = isWorking,
    )

    @Test
    fun breakingAlerts() {
        val before = listOf(elevator("a", true, station = "S+U Pankow"))
        val after = listOf(elevator("a", false, station = "S+U Pankow"))
        assertEquals(
            DisruptionAlert.Broke(station = "S+U Pankow", count = 1),
            DisruptionAlert.transition(before, after),
        )
    }

    @Test
    fun repairingAlerts() {
        val before = listOf(elevator("a", false, station = "U Vinetastr."))
        val after = listOf(elevator("a", true, station = "U Vinetastr."))
        assertEquals(
            DisruptionAlert.Repaired(station = "U Vinetastr."),
            DisruptionAlert.transition(before, after),
        )
    }

    // A standing disruption is not an event: re-announcing it on every
    // evaluation would burn the alert's credibility within days.
    @Test
    fun standingDisruptionDoesNotReAlert() {
        val same = listOf(elevator("a", false), elevator("b", true))
        assertNull(DisruptionAlert.transition(same, same))
    }

    // Counts alone would miss this: one breaks, one is repaired, totals
    // unchanged — and the bad news still has to win.
    @Test
    fun comparesPerElevatorAndPrefersBadNews() {
        val before = listOf(elevator("a", false, station = "A"), elevator("b", true, station = "B"))
        val after = listOf(elevator("a", true, station = "A"), elevator("b", false, station = "B"))
        assertEquals(
            DisruptionAlert.Broke(station = "B", count = 1),
            DisruptionAlert.transition(before, after),
        )
    }

    @Test
    fun severalBreakingAtOnceAlertOnce() {
        val before = listOf(elevator("a", true, station = "A"), elevator("b", true, station = "B"))
        val after = listOf(elevator("a", false, station = "A"), elevator("b", false, station = "B"))
        assertEquals(
            DisruptionAlert.Broke(station = "A", count = 2),
            DisruptionAlert.transition(before, after),
        )
    }

    // Nothing to compare against yet (alerts freshly enabled) — silence
    // beats a made-up event.
    @Test
    fun withoutAPreviousStateNothingAlerts() {
        assertNull(DisruptionAlert.transition(null, listOf(elevator("a", false))))
        assertNull(DisruptionAlert.transition(emptyList(), listOf(elevator("a", false))))
    }

    // An elevator favorited in between that arrives broken is a fact the
    // user just chose to look at, not something the monitoring witnessed.
    @Test
    fun newlyAddedFavoritesDoNotAlert() {
        val before = listOf(elevator("a", true))
        val after = listOf(elevator("a", true), elevator("new", false))
        assertNull(DisruptionAlert.transition(before, after))
    }

    // A failed refresh keeps the previous values — the elevator turning
    // "unknown" would be a source problem, never an alert-worthy defect.
    @Test
    fun fallingBackToUnknownDoesNotAlert() {
        val before = listOf(elevator("a", true))
        val after = listOf(elevator("a", null))
        assertNull(DisruptionAlert.transition(before, after))
    }

    @Test
    fun alertTextsNameTheStation() {
        assertEquals("Aufzug außer Betrieb", DisruptionAlert.Broke(station = "S+U Pankow", count = 1).title)
        assertEquals("S+U Pankow", DisruptionAlert.Broke(station = "S+U Pankow", count = 1).body)
        assertEquals("S+U Pankow und 2 weitere", DisruptionAlert.Broke(station = "S+U Pankow", count = 3).body)
        assertEquals("Aufzug wieder in Betrieb", DisruptionAlert.Repaired(station = "U Stadtmitte").title)
    }
}
