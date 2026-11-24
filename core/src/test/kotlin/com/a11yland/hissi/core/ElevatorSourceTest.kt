package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// Ported from HissiTests/Tests/ElevatorSourceTests.swift. The id names the
// source — a VRR id is never mistaken for a pre-migration one. (The iOS
// legacy-favorites cleanup has no Android counterpart.)
class ElevatorSourceTest {
    private val vrrId = "vrr:de:05112:16580:31"

    @Test
    fun numericIdsAreAccessibilityCloud() {
        assertEquals(ElevatorSource.AccessibilityCloud, ElevatorSource.of("7368"))
    }

    @Test
    fun prefixedIdsAreVrr() {
        assertEquals(ElevatorSource.Vrr, ElevatorSource.of(vrrId))
        assertEquals(vrrId, ElevatorSource.vrrId("de:05112:16580:31"))
    }

    @Test
    fun everythingElseIsLegacy() {
        listOf("fasta-10315619", "brokenlifts-900100003-2", "23ZEJX7dztP3iQnYN", "", "vrr:").forEach {
            assertEquals(ElevatorSource.Legacy, ElevatorSource.of(it), it)
        }
    }

    @Test
    fun onlyLegacyIdsAreNotLive() {
        assertTrue(ElevatorSource.AccessibilityCloud.isLive)
        assertTrue(ElevatorSource.Vrr.isLive)
        assertFalse(ElevatorSource.Legacy.isLive)
    }

    private fun favorite(id: String) = MonitoredElevator(
        id = id, stationId = "1", elevatorId = id, stationName = "S", elevatorDescription = "",
        isWorking = true,
    )

    private fun equipment(id: String) = AccessibilityCloudClient.Equipment(
        id = id, stationId = "1", stationName = "S", description = "", isWorking = true,
    )

    // The seed's legacyIds bridge is for pre-migration ids only.
    @Test
    fun bridgeIgnoresVrrIds() {
        val bridged = EquipmentCatalog.bridgedLiveIds(
            setOf(vrrId, "fasta-1", "7368"),
            legacyIds = mapOf(vrrId to "1", "fasta-1" to "2", "7368" to "3"),
        )
        assertEquals(mapOf("fasta-1" to "2"), bridged)
    }

    // A legacy favorite adopts the identity of the live record it resolves
    // to; a legacy record never replaces an identity.
    @Test
    fun statusMergeAdoptsLiveIdentitiesOnly() {
        assertEquals("101", StatusMerge.apply(equipment("101"), favorite("fasta-1")).id)
        assertEquals(vrrId, StatusMerge.apply(equipment(vrrId), favorite("fasta-1")).id)
        assertEquals("101", StatusMerge.apply(equipment("fasta-2"), favorite("101")).id)
    }

    // Only accessibility.cloud keeps a span history: an undated VRR favorite
    // is not asked about.
    @Test
    fun historyIsNotRequestedForVrrIds() = runTest {
        val requests = mutableListOf<Set<String>>()
        val sources = StatusSources(
            equipment = { emptyMap() },
            latestSpans = { ids ->
                requests.add(ids)
                emptyList()
            },
        )
        ElevatorRefresher.withHistory(listOf(favorite("7368"), favorite(vrrId)), sources)
        assertEquals(listOf(setOf("7368")), requests)
        ElevatorRefresher.withHistory(listOf(favorite(vrrId)), sources)
        assertEquals(1, requests.size)
    }

    // The toggle's filter: a VRR record only counts while the feature is on.
    @Test
    fun toggleFiltersVrrIdsOnly() {
        assertTrue(ElevatorSource.includes("7368", vrrEnabled = false))
        assertTrue(ElevatorSource.includes("fasta-1", vrrEnabled = false))
        assertFalse(ElevatorSource.includes(vrrId, vrrEnabled = false))
        assertTrue(ElevatorSource.includes(vrrId, vrrEnabled = true))
    }
}
