package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals

// "Hbf" and "Hauptbahnhof" find each other — DB names abbreviate, the stop
// places spell out. Ported from
// StationAliasTests in HissiTests/Tests/EquipmentCatalogTests.swift.
class StationAliasesTest {
    private fun station(id: String, name: String) = AccessibilityCloudClient.Equipment(
        id = id, stationId = id, stationName = name, description = "",
    )

    private val catalog = listOf(
        station("1", "München Hbf"),
        station("2", "Berlin Hauptbahnhof"),
        station("3", "Zoologischer Garten Bahnhof (Berlin)"),
        station("4", "Hbfx"),
    )

    private fun ids(query: String) = AccessibilityCloudClient.matchStations(query, catalog).map { it.id }

    @Test
    fun fullWordFindsTheAbbreviation() {
        assertEquals(listOf("1", "2"), ids("Hauptbahnhof"))
        assertEquals(listOf("1"), ids("münchen hauptbahnhof"))
        // "Hauptbahnhof" contains "Bahnhof" — spelled out or abbreviated.
        assertEquals(listOf("1", "2", "3"), ids("Bahnhof"))
    }

    @Test
    fun abbreviationFindsTheFullWord() {
        // "Hbfx" contains the input as typed — that match stays.
        assertEquals(listOf("1", "2", "4"), ids("hbf"))
        assertEquals(listOf("2"), ids("Berlin Hbf"))
    }

    // Typing on keeps every partial match; whole words only — "Hbfx" is no
    // "Hbf".
    @Test
    fun partialInputAndWordBoundaries() {
        assertEquals(listOf("1", "4"), ids("hb"))
        assertEquals(listOf("1", "2"), ids("haupt"))
        assertEquals("hbfx", StationAliases.expanded("hbfx"))
        assertEquals("stolberg(rheinl)hauptbahnhof", StationAliases.expanded("stolberg(rheinl)hbf"))
    }

    @Test
    fun highlightCoversTheOtherSpelling() {
        assertEquals(8..10, SearchHighlight.range("München Hbf", "Hauptbahnhof"))
        assertEquals(7..18, SearchHighlight.range("Berlin Hauptbahnhof", "hbf"))
    }
}
