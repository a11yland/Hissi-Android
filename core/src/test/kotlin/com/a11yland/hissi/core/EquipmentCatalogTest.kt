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

// Seed-id favorites (created from a seed preview or the offline seed
// fallback) bridge to their live records via the operator inventory number.
class SeedFavoriteBridgeTest {
    private fun seedRecord(
        id: String,
        number: Int?,
        source: String = "fasta",
    ) = SeedCatalog.Record(
        id = id, source = source, acId = null, fastaEquipmentNumber = number,
        stationNumber = null, brokenliftsStationId = null, brokenliftsIndex = null,
        stationName = "S Teststadt", description = "Straße ⟷ Bahnsteig",
        latitude = null, longitude = null, sourceName = "DB FaSta",
        organizationName = "DB", region = "berlin",
    )

    private fun liveEquipment(
        id: String,
        inventoryId: String? = null,
        number: Int? = null,
    ) = AccessibilityCloudClient.Equipment(
        id = id, inventoryId = inventoryId, stationId = "900000001",
        stationName = "S Teststadt", description = "Straße ⟷ Bahnsteig",
        isWorking = true, fastaEquipmentNumber = number,
    )

    @Test
    fun bridgesSeedIdToLiveIdViaInventoryNumber() {
        val map = EquipmentCatalog.bridgedLiveIds(
            ids = setOf("fasta-42", "7"),
            seed = listOf(seedRecord(id = "fasta-42", number = 42)),
            catalog = listOf(liveEquipment(id = "456", inventoryId = "42")),
        )
        assertEquals(mapOf("fasta-42" to "456"), map)
    }

    // overlaid() gives matched live records the seed's number even when the
    // operator inventory id is a non-numeric string — the bridge must accept
    // both spellings.
    @Test
    fun matchedOverlayRecordBridgesViaItsSeedNumber() {
        val map = EquipmentCatalog.bridgedLiveIds(
            ids = setOf("z87ZFWJs5aB2seuYf"),
            seed = listOf(seedRecord(id = "z87ZFWJs5aB2seuYf", number = 10_315_432)),
            catalog = listOf(liveEquipment(id = "456", number = 10_315_432)),
        )
        assertEquals(mapOf("z87ZFWJs5aB2seuYf" to "456"), map)
    }

    // An appended seed record carries the number under its seed id — it must
    // not bridge to itself.
    @Test
    fun appendedSeedRecordIsNoBridgeTarget() {
        val map = EquipmentCatalog.bridgedLiveIds(
            ids = setOf("fasta-42"),
            seed = listOf(seedRecord(id = "fasta-42", number = 42)),
            catalog = listOf(liveEquipment(id = "fasta-42", number = 42)),
        )
        assertTrue(map.isEmpty())
    }

    @Test
    fun unknownIdsAndMissingNumbersDoNotBridge() {
        val map = EquipmentCatalog.bridgedLiveIds(
            ids = setOf("fasta-42", "brokenlifts-900009103-0"),
            seed = listOf(seedRecord(id = "fasta-42", number = null)),
            catalog = listOf(liveEquipment(id = "456", inventoryId = "42")),
        )
        assertTrue(map.isEmpty())
    }
}
