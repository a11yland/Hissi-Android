package com.a11yland.hissi.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// No Swift counterpart (URLSession keeps the orchestration untestable there):
// the request contract of the Aufzugsstatus API client — paths, query
// parameters, the User-Agent fair use asks for, and how failures degrade.
class AufzugsstatusApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine) = AufzugsstatusApi(engine = engine, userAgent = "Hissi-Android/test (hissi.app)")

    private fun lift(id: String, source: String, station: String?, state: String = "ACTIVE", lat: Double? = 53.55, lon: Double? = 10.0) = """
        { "id": "$id", "source": "$source", "source_id": "${id.substringAfter(':')}", "type": "ELEVATOR",
          "station_number": 2514, "station_name": ${station?.let { "\"$it\"" } ?: "null"},
          "description": "zu Gleis 1", "lat": ${lat ?: "null"}, "lon": ${lon ?: "null"}, "operator": "DB InfraGO",
          "state": "$state", "state_explanation": "available", "state_since": "2026-11-20T17:00:00+00:00",
          "last_seen": "${java.time.Instant.now()}", "updated_at": "${java.time.Instant.now()}", "station": "x" }
    """

    @Test
    fun searchSendsTheQueryWithTheUserAgent() = runTest {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond("[${lift("fasta:1", "fasta", "Hamburg Hbf")}, ${lift("bvg:2", "bvg", "S+U Pankow")}]", headers = jsonHeaders)
        }
        val results = api(engine).searchLifts("  Hamburg Hbf ")
        val request = seen!!
        assertEquals("/lifts", request.url.encodedPath)
        assertEquals("Hamburg Hbf", request.url.parameters["q"])
        assertEquals("300", request.url.parameters["limit"])
        assertEquals("Hissi-Android/test (hissi.app)", request.headers[HttpHeaders.UserAgent])
        // The VBB feed is dropped on the way out.
        assertContentEquals(listOf("fasta:1"), results.orEmpty().map { it.id })
    }

    @Test
    fun shortQueriesNeverHitTheNetwork() = runTest {
        var requests = 0
        val engine = MockEngine { requests++; respond("[]", headers = jsonHeaders) }
        assertEquals(emptyList(), api(engine).searchLifts("Pi"))
        assertEquals(0, requests)
    }

    @Test
    fun failuresReadAsNullNotAsEmpty() = runTest {
        val failing = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        assertNull(api(failing).searchLifts("Hamburg"))
        val garbage = MockEngine { respond("<html>", headers = jsonHeaders) }
        assertNull(api(garbage).searchLifts("Hamburg"))
    }

    // One GET per id — ids with colons travel in the path as they are, and an
    // id the API doesn't know is simply absent (the caller keeps its values).
    @Test
    fun fetchesEachFavoriteByItsNamespacedId() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath) {
                "/lifts/hvv:Master:11950-E" -> respond(lift("hvv:Master:11950-E", "hvv", "Jungfernstieg", state = "INACTIVE", lat = null, lon = null), headers = jsonHeaders)
                "/lifts/fasta:10804843" -> respond(lift("fasta:10804843", "fasta", "Hamburg Hbf"), headers = jsonHeaders)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val records = api(engine).fetchLifts(setOf("hvv:Master:11950-E", "fasta:10804843", "fasta:1"))
        assertEquals(setOf("/lifts/hvv:Master:11950-E", "/lifts/fasta:10804843", "/lifts/fasta:1"), paths.toSet())
        assertEquals(setOf("hvv:Master:11950-E", "fasta:10804843"), records.keys)
        assertEquals(false, records["hvv:Master:11950-E"]?.isWorking)
        assertEquals(true, records["fasta:10804843"]?.isWorking)
        assertTrue(api(engine).fetchLifts(emptySet()).isEmpty())
    }

    // The nearby lookup sends a coarsened coordinate with the wider radius,
    // then asks for each named station's lifts and sorts them over the true
    // position — the placeholder "Bahnhof <n>" entry is never looked up.
    @Test
    fun nearbyAsksWithACoarsenedPositionAndSortsOverTheTrueOne() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests.add(request)
            when {
                request.url.encodedPath == "/stations/near" -> respond(
                    """[{"name":"Hamburg Hbf","station_number":2514,"lifts":1,"defekt":0,"distance_km":0.4,"lat":53.5531,"lon":10.0065},
                        {"name":"Bahnhof 2514","station_number":2514,"lifts":1,"defekt":1,"distance_km":0.44,"lat":53.553,"lon":10.0059},
                        {"name":"Hammerbrook","station_number":2533,"lifts":1,"defekt":0,"distance_km":1.3,"lat":53.5458,"lon":10.0243}]""",
                    headers = jsonHeaders,
                )
                request.url.parameters["station_number"] == "2514" -> respond("[${lift("fasta:1", "fasta", "Hamburg Hbf", lat = 53.5531, lon = 10.0065)}]", headers = jsonHeaders)
                request.url.parameters["station_number"] == "2533" -> respond("[${lift("fasta:2", "fasta", "Hammerbrook", lat = 53.5458, lon = 10.0243)}]", headers = jsonHeaders)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val stations = api(engine).nearbyStations(53.55318, 10.00616)
        val near = requests.first { it.url.encodedPath == "/stations/near" }
        assertEquals("53.55", near.url.parameters["lat"])
        assertEquals("10.01", near.url.parameters["lon"])
        assertEquals("2.0", near.url.parameters["radius_km"])
        // Two named stations, two lift lookups; no request for the placeholder.
        assertEquals(2, requests.count { it.url.encodedPath == "/lifts" })
        // Hammerbrook lies beyond the walking radius of the true position.
        assertContentEquals(listOf("Hamburg Hbf"), stations.orEmpty().map { it.name })
        assertEquals(ElevatorStatus.Working, stations!!.first().status)
    }

    @Test
    fun nearbyFailureIsNullAndNoStationIsEmpty() = runTest {
        assertNull(api(MockEngine { respondError(HttpStatusCode.BadGateway) }).nearbyStations(53.55, 10.0))
        assertEquals(emptyList(), api(MockEngine { respond("[]", headers = jsonHeaders) }).nearbyStations(53.55, 10.0))
    }
}
