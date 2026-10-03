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

// No Swift counterpart: URLSession kept this orchestration untestable on iOS.
// The mock engine makes the pagination contract cheap to pin down here —
// deliberately more coverage than the port point, not less.
class TransitApiTest {
    private fun elevatorsPage(ids: List<Int>, totalPages: Int) = """
        { "docs": [ ${ids.joinToString(",") { """{ "id": $it, "elevator_type": "elevator" }""" }} ],
          "hasNextPage": false, "totalPages": $totalPages }
    """

    private fun api(engine: MockEngine, pageCounts: PageCountStore = InMemoryPageCountStore()) =
        TransitApi(engine = engine, token = "trtok_test", pageCounts = pageCounts)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun fetchesAllPagesAndRemembersTheCount() = runTest {
        val engine = MockEngine { request ->
            when (request.url.parameters["page"]) {
                "1" -> respond(elevatorsPage(listOf(1), totalPages = 3), headers = jsonHeaders)
                "2" -> respond(elevatorsPage(listOf(2), totalPages = 3), headers = jsonHeaders)
                "3" -> respond(elevatorsPage(listOf(3), totalPages = 3), headers = jsonHeaders)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val pageCounts = InMemoryPageCountStore()
        val items = api(engine, pageCounts).fetchAllPages(
            TransitApi.ELEVATORS,
            parse = AccessibilityCloudClient::parseElevators,
        )
        assertContentEquals(listOf("1", "2", "3"), items.orEmpty().map { it.id })
        // The next build speculates with the remembered count.
        assertEquals(3, pageCounts.hint(TransitApi.ELEVATORS))
    }

    @Test
    fun speculativePagesGoOutAlongsidePageOne() = runTest {
        val pageCounts = InMemoryPageCountStore().apply { remember(3, TransitApi.ELEVATORS) }
        val requestedPages = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPages.add(request.url.parameters["page"] ?: "?")
            respond(
                elevatorsPage(listOf(requestedPages.size), totalPages = 3),
                headers = jsonHeaders,
            )
        }
        api(engine, pageCounts).fetchAllPages(
            TransitApi.ELEVATORS,
            parse = AccessibilityCloudClient::parseElevators,
        )
        // Pages 2 and 3 were requested speculatively — no second wave needed.
        assertEquals(listOf("1", "2", "3"), requestedPages.sorted())
        assertEquals(3, requestedPages.size)
    }

    // All-or-nothing: a lost page would silently drop elevators.
    @Test
    fun aFailedPageFailsTheWholeList() = runTest {
        val engine = MockEngine { request ->
            when (request.url.parameters["page"]) {
                "1" -> respond(elevatorsPage(listOf(1), totalPages = 2), headers = jsonHeaders)
                else -> respondError(HttpStatusCode.InternalServerError)
            }
        }
        assertNull(
            api(engine).fetchAllPages(
                TransitApi.ELEVATORS,
                parse = AccessibilityCloudClient::parseElevators,
            ),
        )
    }

    @Test
    fun emptyFirstPageIsAFailureUnlessLegitimate() = runTest {
        val engine = MockEngine {
            respond("""{ "docs": [], "totalPages": 1 }""", headers = jsonHeaders)
        }
        assertNull(
            api(engine).fetchAllPages(TransitApi.ELEVATORS, parse = AccessibilityCloudClient::parseElevators),
        )
        // An empty page of active status spans is legitimate (nothing broken).
        val spans = api(engine).fetchAllPages(
            TransitApi.STATUS_SPANS,
            emptyFirstPageIsFailure = false,
            parse = AccessibilityCloudClient::parseStatusSpans,
        )
        assertTrue(spans.orEmpty().isEmpty())
    }

    @Test
    fun targetedFetchJoinsInlineSpans() = runTest {
        var query: String? = null
        val engine = MockEngine { request ->
            query = request.url.parameters["where[id][in]"]
            respond(fixtureData("elevators_targeted"), headers = jsonHeaders)
        }
        val equipment = api(engine).fetchEquipment(listOf("7187", "5373", "888"))
        assertEquals("7187,5373,888", query)
        assertContentEquals(listOf("7187", "5373", "888"), equipment.orEmpty().map { it.id })
        assertEquals(false, equipment.orEmpty().first { it.id == "7187" }.isWorking)
    }

    @Test
    fun httpErrorMeansNullNotEmpty() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        assertNull(api(engine).fetchEquipment(listOf("7187")))
    }
}
