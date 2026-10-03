package com.a11yland.hissi.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported 1:1 from HissiTests/Tests/AccessibilityCloudTests.swift.

class ParseElevatorsTest {
    @Test
    fun parsesPayloadEnvelope() {
        val (elevators, hasNextPage, totalPages) = AccessibilityCloudClient.parseElevators(fixtureData("elevators"))
        assertEquals(5, elevators.size)
        assertEquals(false, hasNextPage)
        assertEquals(1, totalPages)
    }

    @Test
    fun normalizesNumericIdsToStrings() {
        val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items
        assertContentEquals(listOf("101", "102", "900", "903", "103"), elevators.map { it.id })
        assertEquals("11", elevators.first().stopPlaceId)
    }

    @Test
    fun carriesInventoryAndStatusSpanReferences() {
        val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items
        val first = assertNotNull(elevators.firstOrNull { it.id == "101" })
        assertEquals("10315619", first.inventoryId)
        assertEquals("501", first.currentStatusSpanId)
        val second = assertNotNull(elevators.firstOrNull { it.id == "102" })
        assertNull(second.inventoryId)
        assertNull(second.currentStatusSpanId)
    }

    @Test
    fun decodesLocalizedAndPlainDescriptions() {
        val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items
        assertEquals(
            "S-Bahnsteig Gl. 3/4 ⟷ Zugang Adlergestell",
            elevators.firstOrNull { it.id == "101" }?.description,
        )
        assertEquals("nur einfache Beschreibung", elevators.firstOrNull { it.id == "102" }?.description)
    }

    @Test
    fun decodesTheOperationalStatusEnum() {
        val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items
        assertEquals(false, elevators.firstOrNull { it.id == "101" }?.isWorking)
        assertEquals(true, elevators.firstOrNull { it.id == "102" }?.isWorking)
        // "unknown" must stay unknown, not become broken.
        assertNull(elevators.firstOrNull { it.id == "103" }?.isWorking)
    }

    @Test
    fun decodesCoordinatesFromGeometryAndFlatFields() {
        val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items
        // GeoJSON order is [longitude, latitude].
        val geo = assertNotNull(elevators.firstOrNull { it.id == "101" })
        assertEquals(52.43, geo.latitude)
        assertEquals(13.54, geo.longitude)
        val flat = assertNotNull(elevators.firstOrNull { it.id == "102" })
        assertEquals(52.44, flat.latitude)
    }

    // DB-feed elevators without a stop-place link have empty function maps
    // and only an internal_description.
    @Test
    fun fallsBackToInternalDescription() {
        val json = """
        { "docs": [ { "id": 7149,
            "function": { "short_visual": { "de": null, "en": null } },
            "internal_description": "zu Gleis 1/1a" } ] }
        """
        val elevators = AccessibilityCloudClient.parseElevators(json).items
        assertEquals("zu Gleis 1/1a", elevators.firstOrNull()?.description)
    }

    @Test
    fun acceptsBareArrays() {
        val json = """[ { "id": 7, "elevator_type": "elevator" } ]"""
        val (elevators, hasNextPage, totalPages) = AccessibilityCloudClient.parseElevators(json)
        assertContentEquals(listOf("7"), elevators.map { it.id })
        assertNull(hasNextPage)
        assertNull(totalPages)
    }

    @Test
    fun unparseableDataYieldsEmpty() {
        assertTrue(AccessibilityCloudClient.parseElevators("nonsense").items.isEmpty())
    }
}

class ParseStatusSpansTest {
    @Test
    fun mapsOperationalStatusOntoIsWorking() {
        val spans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_spans")).items
        assertEquals(4, spans.size)
        assertEquals(false, spans.firstOrNull { it.id == "501" }?.isWorking)
        assertEquals(true, spans.firstOrNull { it.id == "502" }?.isWorking)
    }

    @Test
    fun decodesTheHasManyElevatorRelation() {
        val spans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_spans")).items
        assertContentEquals(listOf("101"), spans.firstOrNull { it.id == "501" }?.elevatorIds)
        val json = """
        { "docs": [ { "id": 9, "elevator": [1, 2], "operational_status": "operational" } ] }
        """
        val multi = AccessibilityCloudClient.parseStatusSpans(json).items
        assertContentEquals(listOf("1", "2"), multi.firstOrNull()?.elevatorIds)
    }

    @Test
    fun unknownStatusWordStaysUnknownNotBroken() {
        assertEquals(true, AccessibilityCloudClient.statusMeansWorking("in_service"))
        assertEquals(true, AccessibilityCloudClient.statusMeansWorking("operational"))
        assertEquals(false, AccessibilityCloudClient.statusMeansWorking("OUT_OF_SERVICE"))
        // Partially operational is not reliably usable — never a false all-clear.
        assertEquals(false, AccessibilityCloudClient.statusMeansWorking("partially_operational"))
        assertNull(AccessibilityCloudClient.statusMeansWorking("unknown"))
        assertNull(AccessibilityCloudClient.statusMeansWorking("mystery-state"))
    }

    @Test
    fun decodesLocalizedReason() {
        val spans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_spans")).items
        val broken = assertNotNull(spans.firstOrNull { it.id == "501" })
        assertEquals("Wird repariert", broken.reason)
    }

    @Test
    fun parsesFractionalAndPlainTimestamps() {
        val spans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_spans")).items
        val broken = assertNotNull(spans.firstOrNull { it.id == "501" })
        assertEquals(Instant.parse("2026-06-02T08:40:01.962Z"), broken.lastUpdate)
        assertEquals(Instant.parse("2026-06-01T06:00:00Z"), broken.start)
    }

    @Test
    fun unparseableTimestampBecomesNil() {
        val json = """
        { "docs": [ { "id": 1, "elevator": [5], "operational_status": "operational", "updatedAt": "not-a-date" } ] }
        """
        val span = AccessibilityCloudClient.parseStatusSpans(json).items.firstOrNull()
        assertEquals(true, span?.isWorking)    // the span still parses
        assertNull(span?.lastUpdate)           // only the bad timestamp drops out
    }
}

class CurrentSpanTest {
    private fun span(start: String?, end: String?, isWorking: Boolean) =
        AccessibilityCloudClient.StatusSpan(
            elevatorIds = listOf("1"),
            isWorking = isWorking,
            start = start?.let(Instant::parse),
            end = end?.let(Instant::parse),
        )

    @Test
    fun openSpanCoveringNowWins() {
        val current = AccessibilityCloudClient.currentSpan(
            listOf(
                span("2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", isWorking = true),
                span("2026-06-01T00:00:00Z", null, isWorking = false),
            ),
            now = fixtureNow,
        )
        assertEquals(false, current?.isWorking)
    }

    @Test
    fun endedAndFutureSpansAreIgnored() {
        val current = AccessibilityCloudClient.currentSpan(
            listOf(
                span("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", isWorking = false),
                span("2026-12-01T00:00:00Z", null, isWorking = false),
            ),
            now = fixtureNow,
        )
        assertNull(current)
    }

    @Test
    fun latestStartWinsAmongOverlappingSpans() {
        val current = AccessibilityCloudClient.currentSpan(
            listOf(
                span(null, null, isWorking = true),
                span("2026-06-15T00:00:00Z", null, isWorking = false),
            ),
            now = fixtureNow,
        )
        assertEquals(false, current?.isWorking)
    }
}

class JoinTest {
    @Test
    fun dropsEscalatorsAndMovingWalkways() {
        val equipment = joinedFixtures()
        assertEquals(3, equipment.size)
        assertTrue(equipment.all { it.id != "900" && it.id != "903" })
    }

    @Test
    fun resolvesStopPlaceIntoStationFields() {
        val first = assertNotNull(joinedFixtures().firstOrNull { it.id == "101" })
        assertEquals("900193002", first.stationId)
        assertEquals("Adlershof (Berlin)", first.stationName)
        assertEquals(TransitRegion.Berlin, first.region)
        // The stop place's transport modes become the station's networks.
        assertEquals(setOf(TransitNetwork.SBahn), first.stationNetworks)
    }

    // The elevator's own operational_status is the live status; the current
    // status span contributes the human-readable reason.
    @Test
    fun elevatorStatusDecidesAndSpanExplains() {
        val broken = assertNotNull(joinedFixtures().firstOrNull { it.id == "101" })
        assertEquals(false, broken.isWorking)
        assertEquals("Wird repariert", broken.stateExplanation)
        val working = assertNotNull(joinedFixtures().firstOrNull { it.id == "102" })
        assertEquals(true, working.isWorking)
        // The reason must only surface while broken.
        assertNull(working.stateExplanation)
    }

    // "Stand": when the status last changed — the span's start_date, not the
    // record's updatedAt.
    @Test
    fun spanStartBecomesLastUpdate() {
        val broken = assertNotNull(joinedFixtures().firstOrNull { it.id == "101" })
        assertEquals(Instant.parse("2026-06-01T06:00:00Z"), broken.lastUpdate)
    }

    // An elevator whose own status is "unknown" may still be settled by a
    // span covering now; its cached stop_place_name fills in for a missing
    // stop place reference.
    @Test
    fun unknownElevatorStatusFallsBackToActiveSpan() {
        val standalone = assertNotNull(joinedFixtures().firstOrNull { it.id == "103" })
        assertEquals(true, standalone.isWorking)
        assertEquals("U Museumsinsel (Berlin)", standalone.stationName)
    }

    @Test
    fun noSpanAndNoElevatorStatusIsUnknown() {
        val equipment = AccessibilityCloudClient.join(
            elevators = listOf(AccessibilityCloudClient.Elevator(id = "1", description = "Aufzug")),
            statusSpans = emptyList(),
            stopPlaces = emptyList(),
            now = fixtureNow,
        )
        assertNull(equipment.firstOrNull()?.isWorking)
    }

    // The operator inventory number is the bridge to the seed catalog (and
    // through it to pre-migration favorites) — it must survive the join.
    @Test
    fun inventoryIdSurvivesTheJoin() {
        val first = assertNotNull(joinedFixtures().firstOrNull { it.id == "101" })
        assertEquals("10315619", first.inventoryId)
    }
}

// The targeted favorites fetch resolves the current status span inline
// (depth=1) instead of joining a separately fetched span list.
class TargetedFetchTest {
    private fun parsedFixture(): List<AccessibilityCloudClient.Elevator> =
        AccessibilityCloudClient.parseElevators(fixtureData("elevators_targeted")).items

    @Test
    fun decodesThePopulatedSpanInline() {
        val broken = assertNotNull(parsedFixture().firstOrNull { it.id == "7187" })
        val span = assertNotNull(broken.inlineStatusSpan)
        assertEquals("2459", span.id)
        assertEquals(false, span.isWorking)
        assertEquals("Achtung! Aufzug außer Betrieb – Technik ist informiert.", span.reason)
        assertEquals(AccessibilityCloudClient.date("2026-06-06T22:03:29.874Z"), span.start)
    }

    // A bare id (depth=0 shape) or null must not masquerade as a span.
    @Test
    fun bareIdAndNullSpanReferencesStayNil() {
        val elevators = parsedFixture()
        assertNull(elevators.firstOrNull { it.id == "888" }?.inlineStatusSpan)
        assertEquals("999", elevators.firstOrNull { it.id == "888" }?.currentStatusSpanId)
        assertNull(elevators.firstOrNull { it.id == "5373" }?.inlineStatusSpan)
    }

    @Test
    fun joinResolvesInlineSpanIntoStatusAndExplanation() {
        val equipment = AccessibilityCloudClient.joinTargeted(parsedFixture(), now = fixtureNow)
        val broken = assertNotNull(equipment.firstOrNull { it.id == "7187" })
        assertEquals(false, broken.isWorking)
        assertEquals("Achtung! Aufzug außer Betrieb – Technik ist informiert.", broken.stateExplanation)
        // "Stand" = when the status changed (span start), as in the list join.
        assertEquals(AccessibilityCloudClient.date("2026-06-06T22:03:29.874Z"), broken.lastUpdate)
        assertEquals("Gleis 2/3", broken.description)
        val working = assertNotNull(equipment.firstOrNull { it.id == "5373" })
        assertEquals(true, working.isWorking)
        assertNull(working.stateExplanation)
    }

    // Without stop places in the response, the cached stop_place_name must
    // carry the station name.
    @Test
    fun stationNameFallsBackToCachedStopPlaceName() {
        val equipment = AccessibilityCloudClient.joinTargeted(parsedFixture(), now = fixtureNow)
        assertEquals("S Ostkreuz (Berlin)", equipment.firstOrNull { it.id == "5373" }?.stationName)
    }

    @Test
    fun dropsNonElevatorsAndSurvivesUnresolvableSpanRefs() {
        val equipment = AccessibilityCloudClient.joinTargeted(parsedFixture(), now = fixtureNow)
        assertContentEquals(listOf("7187", "5373", "888"), equipment.map { it.id })
        val unresolved = assertNotNull(equipment.firstOrNull { it.id == "888" })
        // The elevator's own status decides even when its span reference
        // cannot be resolved; there is just no disruption text.
        assertEquals(false, unresolved.isWorking)
        assertNull(unresolved.stateExplanation)
    }
}

// Speculative pagination: the remembered page count of the last build lets
// all expected pages go out alongside page 1.
class SpeculatedPagesTest {
    @Test
    fun remembersPagesTwoThroughHint() {
        assertContentEquals(listOf(2, 3, 4), AccessibilityCloudClient.speculated(4))
        assertContentEquals(listOf(2), AccessibilityCloudClient.speculated(2))
    }

    @Test
    fun noHintOrSinglePageMeansNoSpeculation() {
        assertTrue(AccessibilityCloudClient.speculated(null).isEmpty())
        assertTrue(AccessibilityCloudClient.speculated(1).isEmpty())
        assertTrue(AccessibilityCloudClient.speculated(0).isEmpty())
    }

    // A corrupt hint must not fan out into dozens of wasted requests.
    @Test
    fun runawayHintsAreCapped() {
        assertContentEquals((2..20).toList(), AccessibilityCloudClient.speculated(999))
    }
}

// Requests carry only the app's language (plus server-side German fallback)
// instead of locale=all.
class RequestLocaleTest {
    @Test
    fun englishLocalizationsRequestEnglish() {
        assertEquals("en", AccessibilityCloudClient.requestLocale("en"))
        assertEquals("en", AccessibilityCloudClient.requestLocale("en-GB"))
    }

    // German is the development and canonical source language — it also
    // covers contexts without a resolved localization and, defensively,
    // anything unexpected.
    @Test
    fun everythingElseFallsBackToGerman() {
        assertEquals("de", AccessibilityCloudClient.requestLocale("de"))
        assertEquals("de", AccessibilityCloudClient.requestLocale(null))
        assertEquals("de", AccessibilityCloudClient.requestLocale("fr"))
    }
}

class StationNumberTest {
    @Test
    fun extractsNumberFromDhidId() {
        assertEquals("900193002", AccessibilityCloudClient.stationNumber("de:11000:900193002"))
    }

    @Test
    fun fallsBackToRawIdWithoutColon() {
        assertEquals(
            "U Museumsinsel (Berlin)",
            AccessibilityCloudClient.stationNumber("U Museumsinsel (Berlin)"),
        )
    }

    @Test
    fun emptyForNil() {
        assertEquals("", AccessibilityCloudClient.stationNumber(null))
    }
}

class ApplyTest {
    private fun equipment(id: String = "101", lastUpdate: Instant? = null) =
        AccessibilityCloudClient.Equipment(
            id = id,
            stationId = "900193002",
            stationName = "S Adlershof (Berlin)",
            description = "Bahnsteig",
            isWorking = false,
            lastUpdateEpochMillis = lastUpdate?.toEpochMilli(),
        )

    private val placeholder = MonitoredElevator(
        id = "101",
        stationId = "900193002",
        elevatorId = "101",
        stationName = "S Adlershof (Berlin)",
        elevatorDescription = "",
        isWorking = null,
        lastCheckedEpochMillis = null,
    )

    // lastChecked reflects the poll (≈ now); lastUpdated carries the source's
    // own timestamp. A fresh poll of stale source data must keep them apart.
    @Test
    fun separatesPollTimeFromSourceFreshness() {
        val sourceDate = Instant.now().minusSeconds(3600) // an hour old
        val result = StatusMerge.apply(equipment(lastUpdate = sourceDate), placeholder)

        assertEquals(sourceDate.toEpochMilli(), result.lastUpdatedEpochMillis)
        val checked = assertNotNull(result.lastChecked)
        assertTrue(Math.abs(java.time.Duration.between(checked, Instant.now()).seconds) < 5)
    }

    @Test
    fun nilSourceTimestampLeavesUpdatedNil() {
        val result = StatusMerge.apply(equipment(lastUpdate = null), placeholder)
        assertNull(result.lastUpdated)
        assertNotNull(result.lastChecked)
    }

    // A seed-id favorite resolved through the inventory-number bridge comes
    // back as its live record — the favorite adopts the live identity.
    @Test
    fun bridgedLiveRecordReplacesTheSeedIdentity() {
        val seedFavorite = MonitoredElevator(
            id = "fasta-42", stationId = "0", elevatorId = "fasta-42",
            stationName = "Alt", elevatorDescription = "", isWorking = null,
            lastCheckedEpochMillis = null,
        )
        val result = StatusMerge.apply(equipment(), seedFavorite)
        assertEquals("101", result.id)
        assertEquals("101", result.elevatorId)
        assertEquals("900193002", result.stationId)
        assertEquals(false, result.isWorking)
        assertEquals("S Adlershof (Berlin)", result.stationName)
    }

    // Source, operator and coordinates come from the seed overlay, not from
    // the API: a refresh whose record carries none of them says nothing about
    // them and must leave what the favorite already knows alone — otherwise
    // the detail view loses its map and both name rows on the next poll.
    @Test
    fun metadatalessRefreshKeepsTheFavoritesSourceAndCoordinates() {
        val favorite = MonitoredElevator(
            id = "101", stationId = "900193002", elevatorId = "101",
            stationName = "S Adlershof (Berlin)", elevatorDescription = "Bahnsteig",
            isWorking = true, lastCheckedEpochMillis = null,
            sourceName = "VBB Anlagen (S-Bahn)", organizationName = "VBB",
            latitude = 52.435102, longitude = 13.540553,
        )
        val result = StatusMerge.apply(equipment(), favorite)
        assertEquals("VBB Anlagen (S-Bahn)", result.sourceName)
        assertEquals("VBB", result.organizationName)
        assertEquals(52.435102, result.latitude)
        assertEquals(13.540553, result.longitude)
        // The status fields are the opposite case — they always win.
        assertEquals(false, result.isWorking)
    }

    // A non-numeric equipment id is a seed record, not a live identity — the
    // favorite keeps its own id.
    @Test
    fun seedRecordDoesNotOverwriteTheFavoriteIdentity() {
        val favorite = MonitoredElevator(
            id = "fasta-42", stationId = "1", elevatorId = "fasta-42",
            stationName = "S Teststadt", elevatorDescription = "", isWorking = null,
            lastCheckedEpochMillis = null,
        )
        val result = StatusMerge.apply(equipment(id = "brokenlifts-900009103-0"), favorite)
        assertEquals("fasta-42", result.id)
        assertEquals("fasta-42", result.elevatorId)
    }
}
