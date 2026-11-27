package com.a11yland.hissi.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

private fun instant(iso: String): Instant = Instant.parse(iso)

// Ported from HissiTests/Tests/StatusSinceTests.swift. "Seit": the join dates
// a status from its current span — and from nothing else.
class StatusSinceJoinTest {
    @Test
    fun brokenElevatorIsDatedFromItsSpanStart() {
        assertEquals(instant("2026-06-01T06:00:00Z"), joinedFixtures().first { it.id == "101" }.statusSince)
    }

    // Upstream does keep a few open "operational" spans — they date a working
    // elevator just the same.
    @Test
    fun openOperationalSpanDatesAWorkingElevator() {
        val equipment = joinedFixtures()
        assertEquals(instant("2026-05-01T00:00:00Z"), equipment.first { it.id == "102" }.statusSince)
        assertEquals(instant("2026-06-10T00:00:00Z"), equipment.first { it.id == "103" }.statusSince)
    }

    // The targeted fetch resolves the span by id — its start counts even
    // though its planned end has passed while the elevator is still broken.
    @Test
    fun targetedJoinDatesFromTheInlineSpan() {
        val equipment = AccessibilityCloudClient.joinTargeted(
            AccessibilityCloudClient.parseElevators(fixtureData("elevators_targeted")).items, now = fixtureNow,
        )
        assertEquals(instant("2026-06-06T22:03:29.874Z"), equipment.first { it.id == "7187" }.statusSince)
        // No span: the record's updatedAt is when it was touched, not when
        // the status began.
        val working = equipment.first { it.id == "5373" }
        assertNotNull(working.lastUpdate)
        assertNull(working.statusSince)
    }

    @Test
    fun spanThatDisagreesWithTheStatusDatesNothing() {
        // Back in service, the outage span not closed upstream yet.
        val span = AccessibilityCloudClient.StatusSpan(
            id = "9", elevatorIds = listOf("1"), isWorking = false, start = instant("2026-06-01T00:00:00Z"),
        )
        val equipment = AccessibilityCloudClient.join(
            elevators = listOf(AccessibilityCloudClient.Elevator(id = "1", isWorking = true, currentStatusSpanId = "9")),
            statusSpans = listOf(span), stopPlaces = emptyList(), now = fixtureNow,
        )
        assertEquals(true, equipment.first().isWorking)
        assertNull(equipment.first().statusSince)
    }

    @Test
    fun spanWithoutStartOrNotYetStartedDatesNothing() {
        val noStart = AccessibilityCloudClient.StatusSpan(elevatorIds = listOf("1"), isWorking = false, lastUpdate = fixtureNow)
        val future = AccessibilityCloudClient.StatusSpan(
            elevatorIds = listOf("1"), isWorking = false, start = fixtureNow.plusSeconds(60),
        )
        assertNull(AccessibilityCloudClient.statusSince(isWorking = false, span = noStart, now = fixtureNow))
        assertNull(AccessibilityCloudClient.statusSince(isWorking = false, span = future, now = fixtureNow))
        assertNull(AccessibilityCloudClient.statusSince(isWorking = true, span = null, now = fixtureNow))
    }

    // An unknown elevator takes its status from the span, so they agree.
    @Test
    fun unknownStatusIsDatedFromAnUnknownSpan() {
        val start = instant("2026-06-20T00:00:00Z")
        val span = AccessibilityCloudClient.StatusSpan(elevatorIds = listOf("1"), isWorking = null, start = start)
        val equipment = AccessibilityCloudClient.join(
            elevators = listOf(AccessibilityCloudClient.Elevator(id = "1")),
            statusSpans = listOf(span), stopPlaces = emptyList(), now = fixtureNow,
        )
        assertNull(equipment.first().isWorking)
        assertEquals(start, equipment.first().statusSince)
    }

    @Test
    fun recordsStoredBeforeTheFieldStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val equipment = json.decodeFromString<AccessibilityCloudClient.Equipment>(
            """{"id":"1","stationId":"","stationName":"S","description":""}""",
        )
        assertNull(equipment.statusSince)
        val favorite = json.decodeFromString<MonitoredElevator>(
            """{"id":"1","stationId":"1","elevatorId":"1","stationName":"S","elevatorDescription":""}""",
        )
        assertNull(favorite.statusSince)
    }
}

// The targeted favorites request as upstream really answers it: no
// `current_status_span`, but the elevator's newest spans inline. The span
// running now gives a disruption its text and its start — in the one
// request, on every refresh.
class TargetedSpansTest {
    private val elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators_targeted_spans")).items
    private val joined = AccessibilityCloudClient.joinTargeted(elevators, now = fixtureNow)

    private fun equipment(id: String) = joined.first { it.id == id }

    // Upstream gives a running span a planned end a month ahead; it runs now.
    @Test
    fun brokenElevatorGetsReasonAndStartFromItsRunningSpan() {
        val broken = equipment("5564")
        assertEquals(false, broken.isWorking)
        assertEquals(instant("2026-06-27T12:00:00.000Z"), broken.statusSince)
        assertEquals("Aufzug außer Betrieb – Technik ist informiert.", broken.stateExplanation)
        assertEquals("Oranienburger Straße (Berlin)", broken.stationName)
    }

    @Test
    fun unknownStatusIsDatedFromAnUnknownSpan() {
        val unknown = equipment("7377")
        assertNull(unknown.isWorking)
        assertEquals(instant("2026-06-27T02:19:09.401Z"), unknown.statusSince)
        // A reason is a disruption's; "unknown" carries none.
        assertNull(unknown.stateExplanation)
    }

    // Only ended spans: nothing runs now, so nothing is dated and no old
    // outage text is shown.
    @Test
    fun workingElevatorTakesNothingFromEndedSpans() {
        val working = equipment("7349")
        assertEquals(true, working.isWorking)
        assertNull(working.statusSince)
        assertNull(working.stateExplanation)
    }

    @Test
    fun brokenElevatorWithoutSpansStaysUndated() {
        val bare = equipment("6622")
        assertEquals(false, bare.isWorking)
        assertNull(bare.statusSince)
        assertNull(bare.stateExplanation)
    }

    // The running span disagrees with the elevator (out of service vs.
    // unknown): its start says nothing about when the outage began.
    @Test
    fun disagreeingRunningSpanDatesNothing() {
        assertNull(equipment("7400").statusSince)
    }

    // Bare ids in the join (a depth=0 answer) are not spans.
    @Test
    fun bareSpanIdsAreIgnored() {
        assertTrue(elevators.first { it.id == "7401" }.inlineSpans.isEmpty())
        assertEquals(3, elevators.first { it.id == "5564" }.inlineSpans.size)
        assertNull(equipment("7401").statusSince)
    }
}

class StatusSinceMergeTest {
    private val since = instant("2026-06-01T06:00:00Z")

    private fun favorite(isWorking: Boolean?, since: Instant?, id: String = "101") = MonitoredElevator(
        id = id, stationId = "1", elevatorId = id, stationName = "S", elevatorDescription = "",
        isWorking = isWorking, statusSinceEpochMillis = since?.toEpochMilli(),
    )

    private fun equipment(isWorking: Boolean?, since: Instant?) = AccessibilityCloudClient.Equipment(
        id = "101", stationId = "1", stationName = "S", description = "", isWorking = isWorking,
        statusSinceEpochMillis = since?.toEpochMilli(),
    )

    @Test
    fun aDatedRecordWins() {
        val newer = since.plusSeconds(3600)
        val merged = StatusMerge.apply(equipment(false, newer), favorite(false, since))
        assertEquals(newer, merged.statusSince)
    }

    // Every path dates a disruption from the span running now, so a record
    // without a date means there is none: an old date is not kept alive.
    @Test
    fun undatedRecordClearsTheDate() {
        assertNull(StatusMerge.apply(equipment(false, null), favorite(false, since)).statusSince)
    }

    @Test
    fun dateComesWithTheRecordThroughTheIdMigration() {
        val merged = StatusMerge.apply(equipment(false, since), favorite(false, null, id = "fasta-1"))
        assertEquals("101", merged.id)
        assertEquals(since, merged.statusSince)
    }

    // Source and operator: with the seed overlaid the record is complete,
    // an empty name replaces one the favorite carries from an older seed.
    @Test
    fun seedBackedRecordReplacesAStaleSourceName() {
        val stale = favorite(false, null).copy(sourceName = "VBB Anlagen (S-Bahn)", organizationName = "Alter Betreiber")
        val record = equipment(false, null).copy(sourceName = "", organizationName = "DB InfraGO")
        val replaced = StatusMerge.apply(record, stale, replacingMetadata = true)
        assertEquals("", replaced.sourceName)
        assertEquals("DB InfraGO", replaced.organizationName)
        val kept = StatusMerge.apply(record, stale)
        assertEquals("VBB Anlagen (S-Bahn)", kept.sourceName)
        assertEquals("DB InfraGO", kept.organizationName)
    }
}

// No Swift counterpart (URLSession is untestable there): the one targeted
// request asks for the spans inline and its answer is joined.
class TargetedSpansRequestTest {
    @Test
    fun oneRequestCarriesTheSpansInline() = runTest {
        val requests = mutableListOf<Map<String, String?>>()
        val engine = MockEngine { request ->
            requests.add(
                listOf("select[operational_status][status_spans]", "where[id][in]", "depth")
                    .associateWith { request.url.parameters[it] } + ("path" to request.url.encodedPath),
            )
            respond(
                fixtureData("elevators_targeted_spans"),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = TransitApi(engine = engine, token = "trtok_test", pageCounts = InMemoryPageCountStore())
        val equipment = api.fetchEquipment(listOf("5564", "7349"))
        assertEquals(1, requests.size)
        assertEquals("/api/elevators", requests.first()["path"])
        assertEquals("true", requests.first()["select[operational_status][status_spans]"])
        assertEquals("5564,7349", requests.first()["where[id][in]"])
        assertEquals("1", requests.first()["depth"])
        // The join itself is covered with a fixed clock (TargetedSpansTest);
        // here only that the answer is parsed and joined at all.
        assertEquals(false, equipment?.first { it.id == "5564" }?.isWorking)
        assertEquals(6, equipment?.size)
    }
}

// "Seit": how long a status has held — always relative. Fixed `now`.
class SinceLabelTest {
    private val now = instant("2026-11-24T12:00:00Z")

    private fun label(minutes: Double, spoken: Boolean = false) = MonitoredElevator.sinceLabel(
        now.minusSeconds((minutes * 60).toLong()), now = now, spoken = spoken,
    )

    @Test
    fun minutes() {
        assertEquals("seit 5 Min.", label(5.0))
        assertEquals("seit 59 Min.", label(59.9))
    }

    // Under a minute, or a start ahead of this device's clock, is one minute.
    @Test
    fun neverLessThanOneMinute() {
        assertEquals("seit 1 Min.", label(0.2))
        assertEquals("seit 1 Min.", label(-3.0))
    }

    @Test
    fun hours() {
        assertEquals("seit 1 Std.", label(60.0))
        assertEquals("seit 23 Std.", label(23 * 60 + 59.0))
    }

    @Test
    fun days() {
        assertEquals("seit 1 Tag", label(24 * 60.0))
        assertEquals("seit 3 Tagen", label(3 * 1440.0))
        assertEquals("seit 13 Tagen", label(14 * 1440 - 1.0))
    }

    // Never a date: weeks, then months, then years.
    @Test
    fun weeksMonthsAndYears() {
        assertEquals("seit 2 Wochen", label(14 * 1440.0))
        assertEquals("seit 5 Wochen", label(40 * 1440.0))
        assertEquals("seit 2 Monaten", label(60 * 1440.0))
        assertEquals("seit 13 Monaten", label(400 * 1440.0))
        assertEquals("seit 2 Jahren", label(730 * 1440.0))
    }

    @Test
    fun spokenFormWritesTheUnitsOut() {
        assertEquals("seit 1 Minute", label(1.0, spoken = true))
        assertEquals("seit 5 Minuten", label(5.0, spoken = true))
        assertEquals("seit 1 Stunde", label(60.0, spoken = true))
        assertEquals("seit 3 Stunden", label(180.0, spoken = true))
        assertEquals("seit 3 Tagen", label(3 * 1440.0, spoken = true))
    }

    // The duration is shown for disruptions only — never for a lift that
    // works, however long it has.
    @Test
    fun durationIsShownForDisruptionsOnly() {
        fun dated(isWorking: Boolean?) = MonitoredElevator(
            id = "1", stationId = "1", elevatorId = "1", stationName = "S", elevatorDescription = "",
            isWorking = isWorking, statusSinceEpochMillis = now.toEpochMilli(),
        )
        assertEquals(now, dated(false).shownStatusSince)
        assertEquals(now, dated(null).shownStatusSince)
        assertNull(dated(true).shownStatusSince)
    }
}
