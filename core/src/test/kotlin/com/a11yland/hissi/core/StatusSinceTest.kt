package com.a11yland.hissi.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.time.ZoneId
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

// "In Betrieb seit": the end of the span before, read off the span history.
class StatusHistoryTest {
    private val spans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_history")).items

    private fun spansOf(id: String) = spans.filter { id in it.elevatorIds }

    // out_of_service → unknown → in service: the latest span's end is the
    // moment the elevator came back.
    @Test
    fun closedLatestSpanOfAnotherStatusGivesItsEnd() {
        assertEquals(
            instant("2026-06-24T12:44:23.502Z"),
            StatusHistory.since(isWorking = true, spans = spansOf("7349"), now = fixtureNow),
        )
    }

    // An open outage span while the elevator reports working: unclear, so
    // no date — never the end of an older span.
    @Test
    fun openLatestSpanGivesNothing() {
        assertNull(StatusHistory.since(isWorking = true, spans = spansOf("7400"), now = fixtureNow))
    }

    @Test
    fun closedLatestSpanOfTheSameStatusGivesNothing() {
        assertNull(StatusHistory.since(isWorking = true, spans = spansOf("7401"), now = fixtureNow))
    }

    @Test
    fun spansThatHaveNotStartedAreIgnored() {
        val past = AccessibilityCloudClient.StatusSpan(
            elevatorIds = listOf("1"), isWorking = false,
            start = instant("2026-06-01T00:00:00Z"), end = instant("2026-06-02T00:00:00Z"),
        )
        val planned = AccessibilityCloudClient.StatusSpan(
            elevatorIds = listOf("1"), isWorking = false, start = fixtureNow.plusSeconds(86_400),
        )
        assertEquals(past.end, StatusHistory.since(isWorking = true, spans = listOf(planned, past), now = fixtureNow))
    }

    @Test
    fun noHistoryGivesNothing() {
        assertNull(StatusHistory.since(isWorking = true, spans = emptyList(), now = fixtureNow))
    }

    @Test
    fun appliedFillsOnlyWhatIsUndated() {
        fun favorite(id: String, since: Instant? = null) = MonitoredElevator(
            id = id, stationId = "1", elevatorId = id, stationName = "S", elevatorDescription = "",
            isWorking = true, statusSinceEpochMillis = since?.toEpochMilli(),
        )
        val known = instant("2026-01-01T00:00:00Z")
        val result = StatusHistory.applied(
            listOf(favorite("7349"), favorite("7349", since = known), favorite("7402")),
            spans = spans, now = fixtureNow,
        )
        assertEquals(
            listOf(instant("2026-06-24T12:44:23.502Z"), known, null),
            result.map { it.statusSince },
        )
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

    // The catalog cache carries no history — an undated record must not
    // erase what a targeted refresh found out.
    @Test
    fun undatedRecordKeepsTheDateWhileTheStatusHolds() {
        assertEquals(since, StatusMerge.apply(equipment(true, null), favorite(true, since)).statusSince)
    }

    @Test
    fun changedStatusDropsTheOldDate() {
        assertNull(StatusMerge.apply(equipment(true, null), favorite(false, since)).statusSince)
    }

    @Test
    fun dateSurvivesTheIdMigration() {
        val merged = StatusMerge.apply(equipment(true, null), favorite(true, since, id = "fasta-1"))
        assertEquals("101", merged.id)
        assertEquals(since, merged.statusSince)
    }
}

// The history costs at most one extra request, and none in the steady state.
class StatusSinceRefreshTest {
    private val requests = mutableListOf<Set<String>>()
    private val cameBack = instant("2026-06-24T12:44:23.502Z")
    private val known = instant("2026-06-01T00:00:00Z")

    private fun favorite(id: String, since: Instant? = null) = MonitoredElevator(
        id = id, stationId = "1", elevatorId = id, stationName = "S", elevatorDescription = "",
        isWorking = true, statusSinceEpochMillis = since?.toEpochMilli(),
    )

    private fun equipment(id: String, since: Instant? = null) = AccessibilityCloudClient.Equipment(
        id = id, stationId = "1", stationName = "S", description = "", isWorking = true,
        statusSinceEpochMillis = since?.toEpochMilli(),
    )

    private val history = listOf(
        AccessibilityCloudClient.StatusSpan(
            elevatorIds = listOf("7349"), isWorking = false,
            start = instant("2026-06-23T08:34:36Z"), end = cameBack,
        ),
    )

    private fun sources(
        equipment: List<AccessibilityCloudClient.Equipment>,
        spans: List<AccessibilityCloudClient.StatusSpan>? = history,
        asksHistory: Boolean = true,
    ) = StatusSources(
        equipment = { _ -> equipment.associateBy { it.id } },
        latestSpans = if (asksHistory) {
            { ids ->
                requests.add(ids)
                spans
            }
        } else {
            null
        },
    )

    @Test
    fun oneRequestForExactlyTheUndatedFavorites() = runTest {
        val (updated, _) = ElevatorRefresher.refreshAll(
            listOf(favorite("7349"), favorite("102", since = known), favorite("103")),
            sources(listOf(equipment("7349"), equipment("102"), equipment("103"))),
        )
        assertEquals(listOf(setOf("7349", "103")), requests)
        assertEquals(listOf(cameBack, known, null), updated.map { it.statusSince })
    }

    @Test
    fun noRequestWhenEveryStatusIsDated() = runTest {
        ElevatorRefresher.refreshAll(
            listOf(favorite("101", since = known), favorite("102")),
            sources(listOf(equipment("101"), equipment("102", since = known))),
        )
        assertTrue(requests.isEmpty())
    }

    @Test
    fun failedHistoryRequestChangesNothing() = runTest {
        val (updated, failed) = ElevatorRefresher.refreshAll(
            listOf(favorite("7349")), sources(listOf(equipment("7349")), spans = null),
        )
        assertNull(updated.first().statusSince)
        assertNotNull(updated.first().lastChecked)
        assertTrue(failed.isEmpty())
    }

    // A favorite the status request didn't answer for is not asked about.
    @Test
    fun unansweredFavoritesAreNotAskedAbout() = runTest {
        ElevatorRefresher.refreshAll(
            listOf(favorite("7349"), favorite("999")), sources(listOf(equipment("7349"))),
        )
        assertEquals(listOf(setOf("7349")), requests)
    }

    @Test
    fun sourcesWithoutHistoryNeverAsk() = runTest {
        val (updated, _) = ElevatorRefresher.refreshAll(
            listOf(favorite("7349")), sources(listOf(equipment("7349")), asksHistory = false),
        )
        assertTrue(requests.isEmpty())
        assertNull(updated.first().statusSince)
    }
}

// No Swift counterpart (URLSession is untestable there): the request itself.
class LatestSpansRequestTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine) =
        TransitApi(engine = engine, token = "trtok_test", pageCounts = InMemoryPageCountStore())

    @Test
    fun asksOnceForTheNumericIdsNewestFirst() = runTest {
        val seen = mutableListOf<Map<String, String?>>()
        val engine = MockEngine { request ->
            seen.add(
                listOf("where[elevator][in]", "sort", "where[start_date][less_than_equal]", "depth")
                    .associateWith { request.url.parameters[it] } + ("path" to request.url.encodedPath),
            )
            respond(fixtureData("status_history"), headers = jsonHeaders)
        }
        val spans = api(engine).fetchLatestSpans(setOf("7400", "7349", "fasta-1"), now = fixtureNow)
        assertEquals(6, spans?.size)
        assertEquals(1, seen.size)
        assertEquals("/api/status-spans", seen.first()["path"])
        assertEquals("7349,7400", seen.first()["where[elevator][in]"])
        assertEquals("-start_date", seen.first()["sort"])
        assertEquals("2026-07-01T12:00:00Z", seen.first()["where[start_date][less_than_equal]"])
        assertEquals("0", seen.first()["depth"])
    }

    @Test
    fun failureIsNullAndNoIdsNeedNoRequest() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            respondError(HttpStatusCode.InternalServerError)
        }
        assertNull(api(engine).fetchLatestSpans(setOf("7349")))
        assertEquals(emptyList(), api(engine).fetchLatestSpans(setOf("fasta-1")))
        assertEquals(1, calls)
    }
}

// "Seit": how long a status has held. Fixed `now` and zone.
class SinceLabelTest {
    private val now = instant("2026-11-24T12:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")

    private fun label(minutes: Double, spoken: Boolean = false) = MonitoredElevator.sinceLabel(
        now.minusSeconds((minutes * 60).toLong()), now = now, spoken = spoken, zone = zone,
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

    // From the stale threshold on, a date says more than a day count.
    @Test
    fun twoWeeksAndMoreNameTheDate() {
        assertEquals("seit dem 10. November", label(14 * 1440.0))
        assertEquals("seit dem 15. Oktober", label(40 * 1440.0))
    }

    @Test
    fun anotherYearIsNamed() {
        assertEquals("seit dem 20. Oktober 2025", label(400 * 1440.0))
    }

    @Test
    fun spokenFormWritesTheUnitsOut() {
        assertEquals("seit 1 Minute", label(1.0, spoken = true))
        assertEquals("seit 5 Minuten", label(5.0, spoken = true))
        assertEquals("seit 1 Stunde", label(60.0, spoken = true))
        assertEquals("seit 3 Stunden", label(180.0, spoken = true))
        assertEquals("seit 3 Tagen", label(3 * 1440.0, spoken = true))
    }
}
