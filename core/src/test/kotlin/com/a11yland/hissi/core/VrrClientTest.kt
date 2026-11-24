package com.a11yland.hissi.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// Ported from HissiTests/Tests/VRRClientTests.swift. The VRR infrastructure
// feed, parsed into the catalog's records; the fixture is a trimmed copy of
// the live answer with invented names.
class VrrClientTest {
    private val parsed = VrrClient.parse(fixtureData("vrr_infrastructure"), now = fixtureNow)!!

    private fun lift(resourceId: String) = parsed.first { it.id == "vrr:$resourceId" }

    // Escalators and ramps are left out; a stop without a lift yields none.
    @Test
    fun keepsElevatorsOnly() {
        assertEquals(
            listOf(
                "vrr:de:05112:16580:31",
                "vrr:de:05111:18250:31", "vrr:de:05111:18250:32", "vrr:de:05111:18250:33", "vrr:de:05111:18250:34",
                "vrr:de:05911:5554:31", "vrr:de:05911:5554:32",
            ),
            parsed.map { it.id },
        )
        assertTrue(parsed.all { ElevatorSource.of(it.id) == ElevatorSource.Vrr })
    }

    @Test
    fun identityStationAndRegion() {
        val working = lift("de:05112:16580:31")
        // The stop's full DHID — its last segment alone is not unique.
        assertEquals("de:05112:16580", working.stationId)
        assertEquals("Duisburg Duissern U-Bf", working.stationName)
        assertEquals("Straßenebene (nahe Eingang Duissernplatz) zum Bahnsteig", working.description)
        assertEquals(TransitRegion.NordrheinWestfalen, working.region)
        assertEquals("VRR", working.sourceName)
        assertEquals("", working.organizationName)
        // Whitespace in the feed's names is collapsed.
        assertEquals("Bochum Gesundheitscampus", lift("de:05911:5554:32").stationName)
    }

    @Test
    fun statusWords() {
        assertEquals(true, lift("de:05112:16580:31").isWorking)
        assertEquals(false, lift("de:05111:18250:31").isWorking)
        // No state at all, or a word this version doesn't know: unknown —
        // never a false "working".
        assertNull(lift("de:05111:18250:33").isWorking)
        assertNull(lift("de:05111:18250:34").isWorking)
        assertEquals(true, VrrClient.statusMeansWorking(" In Ordnung "))
        assertNull(VrrClient.statusMeansWorking(null))
    }

    // "Seit" is the state's timestamp — with a known status only, and never
    // a moment still ahead.
    @Test
    fun statusSinceIsTheStateChange() {
        assertEquals(AccessibilityCloudClient.date("2026-05-28T14:19:06.000Z"), lift("de:05112:16580:31").statusSince)
        assertEquals(AccessibilityCloudClient.date("2026-06-19T22:13:55.000Z"), lift("de:05111:18250:31").statusSince)
        assertNull(lift("de:05111:18250:34").statusSince)
        assertEquals(false, lift("de:05911:5554:32").isWorking)
        assertNull(lift("de:05911:5554:32").statusSince)
    }

    // "Datenstand" is the moment of the answer, so a lift that has simply
    // worked for weeks is not flagged as stale.
    @Test
    fun dataAgeIsTheFetchTime() {
        assertTrue(parsed.all { it.lastUpdate == fixtureNow })
    }

    // Known status codes are shown in the app's words; operator free text —
    // here a ticket note with a name — never is.
    @Test
    fun explanationComesFromAnAllowlist() {
        assertEquals("Kein Betrieb", lift("de:05111:18250:31").stateExplanation)
        assertEquals("Störung (Personenbefreiung)", lift("de:05911:5554:32").stateExplanation)
        assertEquals(false, lift("de:05111:18250:32").isWorking)
        assertNull(lift("de:05111:18250:32").stateExplanation)
        // A working lift carries no reason, whatever the feed says.
        assertNull(lift("de:05112:16580:31").stateExplanation)
        assertEquals(VrrClient.Explanation.RemoteShutdown, VrrClient.explanation("Fernabschaltung"))
        assertEquals(VrrClient.Explanation.NotReady, VrrClient.explanation("nicht betriebsbereit"))
        assertNull(VrrClient.explanation("Stoerung"))
        assertNull(VrrClient.explanation(""))
        // The app words the codes itself (string resources).
        val worded = VrrClient.parse(fixtureData("vrr_infrastructure"), fixtureNow) { "<${it.name}>" }!!
        assertEquals("<NoOperation>", worded.first { it.id == "vrr:de:05111:18250:31" }.stateExplanation)
    }

    @Test
    fun coordinateIsTheLiftsOwnWhenPlausible() {
        val own = lift("de:05112:16580:31")
        assertEquals(51.43889, own.latitude)
        assertEquals(6.78278, own.longitude)
        // None of its own: the stop's.
        val missing = lift("de:05111:18250:32")
        assertEquals(51.213707, missing.latitude)
        assertEquals(6.774519, missing.longitude)
        // Far from its stop (the feed has lifts in the Norwegian Sea): the
        // stop's.
        val misplaced = lift("de:05111:18250:33")
        assertEquals(51.213707, misplaced.latitude)
        assertEquals(6.774519, misplaced.longitude)
    }

    // The feed lists a lift twice now and then; the fuller description wins.
    @Test
    fun duplicateResourceIdsCollapse() {
        val records = parsed.filter { it.id == "vrr:de:05911:5554:31" }
        assertEquals(1, records.size)
        assertEquals("Run11 - Zugang Uni-Center zum Bahnsteig", records.first().description)
    }

    @Test
    fun unexpectedDataIsAFailureNotAnEmptyList() {
        assertNull(VrrClient.parse("nonsense"))
        assertNull(VrrClient.parse("""{"error":"Authorization required"}"""))
        assertEquals(emptyList(), VrrClient.parse("[]"))
    }
}

// No Swift counterpart (URLSession is untestable there): the request itself.
class VrrApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun sendsTheKeyAndParsesTheAnswer() = runTest {
        var key: String? = null
        var url: String? = null
        val engine = MockEngine { request ->
            key = request.headers["x-api-key"]
            url = request.url.toString()
            respond(fixtureData("vrr_infrastructure"), headers = jsonHeaders)
        }
        val lifts = VrrApi(engine).fetchAll(now = fixtureNow)
        assertEquals(7, lifts?.size)
        assertEquals(VrrApi.API_KEY, key)
        assertEquals(VrrApi.INFRASTRUCTURE_URL, url)
    }

    @Test
    fun failuresAreNull() = runTest {
        assertNull(VrrApi(MockEngine { respondError(HttpStatusCode.Unauthorized) }).fetchAll())
        assertNull(VrrApi(MockEngine { respondError(HttpStatusCode.InternalServerError) }).fetchAll())
        assertNull(VrrApi(MockEngine { respond("<html>", headers = jsonHeaders) }).fetchAll())
        assertNull(VrrApi(MockEngine { throw java.io.IOException("offline") }).fetchAll())
    }
}
