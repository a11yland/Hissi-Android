package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from HissiTests/Tests/EquipmentCatalogTests.swift — the pure parts.
// The stateful catalog suites (persistence, TTL, in-flight sharing) follow
// with the app-phase port of the catalog cache; the match-highlighting tests
// follow with the Compose search UI.

// The pure name filter behind every search phase (seed preview, snapshot,
// fresh catalog).
class MatchStationsTest {
    private val catalog = listOf(
        AccessibilityCloudClient.Equipment(
            id = "1", stationId = "1", stationName = "Schönhauser Allee (Berlin)",
            description = "", isWorking = null,
        ),
        AccessibilityCloudClient.Equipment(
            id = "2", stationId = "2", stationName = "S Ostkreuz (Berlin)",
            description = "", isWorking = null,
        ),
    )

    // Umlauts fold ("schonhauser" finds Schönhauser); note "ß" is its own
    // letter, not a diacritic — "weissensee" would NOT match Weißensee.
    @Test
    fun matchesCaseAndDiacriticInsensitive() {
        assertContentEquals(
            listOf("1"),
            AccessibilityCloudClient.matchStations("schonhauser", catalog).map { it.id },
        )
        assertContentEquals(
            listOf("1"),
            AccessibilityCloudClient.matchStations("SCHÖNHAUSER", catalog).map { it.id },
        )
        assertContentEquals(
            listOf("2"),
            AccessibilityCloudClient.matchStations("OSTKREUZ", catalog).map { it.id },
        )
    }

    @Test
    fun matchesSubstringsAnywhereInTheName() {
        assertContentEquals(
            listOf("2"),
            AccessibilityCloudClient.matchStations("kreuz", catalog).map { it.id },
        )
    }

    @Test
    fun blankQueryMatchesNothing() {
        assertTrue(AccessibilityCloudClient.matchStations("  ", catalog).isEmpty())
        assertTrue(AccessibilityCloudClient.matchStations("Pankow", catalog).isEmpty())
    }
}

// Favorites created from a format-1 seed preview carry its non-numeric ids;
// the seed's legacyIds map them onto live ids.
class SeedFavoriteBridgeTest {
    @Test
    fun bridgesLegacyIdsOnly() {
        val map = EquipmentCatalog.bridgedLiveIds(
            ids = setOf("fasta-42", "z87ZFWJs5aB2seuYf", "brokenlifts-900009103-0", "7"),
            legacyIds = mapOf("fasta-42" to "456", "z87ZFWJs5aB2seuYf" to "457", "7" to "8"),
        )
        assertEquals(mapOf("fasta-42" to "456", "z87ZFWJs5aB2seuYf" to "457"), map)
    }
}
