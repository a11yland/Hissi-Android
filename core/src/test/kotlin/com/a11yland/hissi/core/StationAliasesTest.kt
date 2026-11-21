package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals

// "Hbf" and "Hauptbahnhof" (and "Bhf"/"Bf" and "Bahnhof") find each other —
// DB names abbreviate, the stop places spell out. Ported from
// StationAliasTests in HissiTests/Tests/EquipmentCatalogTests.swift.
class StationAliasesTest {
    private fun station(id: String, name: String) = AccessibilityCloudClient.Equipment(
        id = id, stationId = id, stationName = name, description = "",
    )

    private val catalog = listOf(
        station("1", "München Hbf"),
        station("2", "Berlin Hauptbahnhof"),
        station("3", "Zoologischer Garten Bahnhof (Berlin)"),
        station("4", "Kirchheim/Rohrbach, Bf"),
        station("5", "Abfahrt"),
    )

    private fun ids(query: String) = AccessibilityCloudClient.matchStations(query, catalog).map { it.id }

    @Test
    fun fullWordFindsTheAbbreviation() {
        assertEquals(listOf("1", "2"), ids("Hauptbahnhof"))
        assertEquals(listOf("1"), ids("münchen hauptbahnhof"))
        // "Hauptbahnhof" contains "Bahnhof" — spelled out or abbreviated.
        assertEquals(listOf("1", "2", "3", "4"), ids("Bahnhof"))
    }

    @Test
    fun abbreviationFindsTheFullWord() {
        assertEquals(listOf("1", "2"), ids("hbf"))
        assertEquals(listOf("2"), ids("Berlin Hbf"))
        assertEquals(listOf("3"), ids("Zoologischer Garten Bhf"))
        assertEquals(listOf("4"), ids("Kirchheim/Rohrbach, Bahnhof"))
    }

    // Typing on keeps every partial match; whole words only — "Abfahrt" is
    // no "Bf".
    @Test
    fun partialInputAndWordBoundaries() {
        assertEquals(listOf("1"), ids("hb"))
        assertEquals(listOf("1", "2"), ids("haupt"))
        assertEquals("abfahrt", StationAliases.expanded("abfahrt"))
        assertEquals("stolberg(rheinl)hauptbahnhof", StationAliases.expanded("stolberg(rheinl)hbf"))
    }

    @Test
    fun highlightCoversTheOtherSpelling() {
        assertEquals(8..10, SearchHighlight.range("München Hbf", "Hauptbahnhof"))
        assertEquals(7..18, SearchHighlight.range("Berlin Hauptbahnhof", "hbf"))
    }
}
