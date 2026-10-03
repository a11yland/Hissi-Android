package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/ElevatorSummaryTests.swift.
class ElevatorSummaryTest {
    private fun elevator(name: String, isWorking: Boolean?) = MonitoredElevator(
        id = name,
        stationId = name,
        elevatorId = "1",
        stationName = name,
        elevatorDescription = "",
        isWorking = isWorking,
    )

    // No favorites must never read as an all-clear.
    @Test
    fun emptyIsItsOwnVerdict() {
        val summary = ElevatorSummary.of(emptyList())
        assertEquals(ElevatorSummary.Verdict.NoFavorites, summary.verdict)
        assertEquals("Keine Favoriten", summary.label)
        assertEquals(0, summary.total)
    }

    @Test
    fun brokenOutranksUnknownOutranksWorking() {
        assertEquals(
            ElevatorSummary.Verdict.Broken,
            ElevatorSummary.of(listOf(elevator("A", false), elevator("B", null), elevator("C", true))).verdict,
        )
        assertEquals(
            ElevatorSummary.Verdict.Unknown,
            ElevatorSummary.of(listOf(elevator("A", null), elevator("B", true))).verdict,
        )
        assertEquals(
            ElevatorSummary.Verdict.AllWorking,
            ElevatorSummary.of(listOf(elevator("A", true), elevator("B", true))).verdict,
        )
    }

    @Test
    fun countsEachState() {
        val summary = ElevatorSummary.of(
            listOf(elevator("A", false), elevator("B", false), elevator("C", null), elevator("D", true)),
        )
        assertEquals(2, summary.brokenCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(4, summary.total)
        assertEquals("2 außer Betrieb", summary.label)
        assertEquals("2 defekt", summary.shortLabel)
    }

    // Surfaces with room show one station name — the broken one, even when
    // unknown records come first in the list.
    @Test
    fun affectedStationsPutBrokenFirstAndDeduplicate() {
        val summary = ElevatorSummary.of(
            listOf(
                elevator("U Alt-Tegel", null),
                elevator("S+U Pankow", false),
                elevator("S+U Pankow", false),
                elevator("U Stadtmitte", true),
            ),
        )
        assertContentEquals(listOf("S+U Pankow", "U Alt-Tegel"), summary.affectedStations)
        assertEquals("S+U Pankow", summary.detailLine)
    }

    @Test
    fun allWorkingHasNoDetailLine() {
        assertNull(ElevatorSummary.of(listOf(elevator("A", true))).detailLine)
        assertNull(ElevatorSummary.of(emptyList()).detailLine)
    }

    // Monochrome surfaces flatten colour, so shape has to distinguish the
    // states on its own.
    @Test
    fun symbolsAreDistinctPerVerdict() {
        val icons = listOf(
            ElevatorSummary.of(emptyList()),
            ElevatorSummary.of(listOf(elevator("A", false))),
            ElevatorSummary.of(listOf(elevator("A", null))),
            ElevatorSummary.of(listOf(elevator("A", true))),
        ).map { it.icon }.toSet()
        assertEquals(4, icons.size)
    }

    // The anonymous surfaces carry the app name themselves.
    @Test
    fun identifiedLabelsNameTheApp() {
        val summary = ElevatorSummary.of(listOf(elevator("A", false)))
        assertEquals("Hissi: 1 außer Betrieb", summary.identifiedLabel)
        assertEquals("Hissi: 1 defekt", summary.identifiedShortLabel)
    }

    // Kept for cross-platform vocabulary parity (iOS watch): an empty mirror
    // is not an empty favorites list — it must not read as "Keine Favoriten".
    @Test
    fun awaitingSyncSpeaksUnknownNotNoFavorites() {
        val summary = ElevatorSummary(
            verdict = ElevatorSummary.Verdict.AwaitingSync,
            brokenCount = 0, unknownCount = 0, total = 0, affectedStations = emptyList(),
        )
        assertEquals("Status unbekannt", summary.label)
        assertEquals("?", summary.shortLabel)
        assertNull(summary.detailLine)
        assertEquals(0f, summary.relevanceScore)
    }

    // Glanceable ranking: a broken elevator surfaces, an all-clear does not
    // crowd anything out.
    @Test
    fun relevanceRanksBrokenHighest() {
        val broken = ElevatorSummary.of(listOf(elevator("A", false))).relevanceScore
        val unknown = ElevatorSummary.of(listOf(elevator("A", null))).relevanceScore
        val working = ElevatorSummary.of(listOf(elevator("A", true))).relevanceScore
        assertTrue(broken > unknown)
        assertTrue(unknown > working)
        assertEquals(0f, working)
        assertEquals(0f, ElevatorSummary.of(emptyList()).relevanceScore)
    }
}
