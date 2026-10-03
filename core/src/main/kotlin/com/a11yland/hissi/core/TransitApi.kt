package com.a11yland.hissi.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Last seen totalPages per collection, so the next build can speculate. A
// guess is never trusted for correctness — page 1's envelope stays the source
// of truth. The app provides a persistent implementation (SharedPreferences);
// the default only lives for the process.
interface PageCountStore {
    fun hint(resource: String): Int?
    fun remember(count: Int, resource: String)
}

class InMemoryPageCountStore : PageCountStore {
    private val counts = mutableMapOf<String, Int>()
    override fun hint(resource: String): Int? = counts[resource]?.takeIf { it > 1 }
    override fun remember(count: Int, resource: String) {
        counts[resource] = count
    }
}

// The network half of the transit.accessibility.cloud client, ported from
// `Shared/AccessibilityCloudClient.swift`: the three flat lists with
// speculative pagination for the catalog build, and the targeted
// where[id][in] fetch for the favorites refresh. All parsing and joining is
// delegated to AccessibilityCloudClient (pure, fixture-tested).
//
// Two timeout profiles mirror the two URLSessions on iOS: the targeted fetch
// fails fast (background-refresh budget), catalog list pages legitimately
// take ~20–25 s at limit=1000 and get their own headroom — without it every
// page times out and the app silently falls back to the seed.
class TransitApi(
    engine: HttpClientEngine,
    private val token: String,
    private val pageCounts: PageCountStore = InMemoryPageCountStore(),
    private val appLocalization: () -> String? = { Locale.getDefault().toLanguageTag() },
    targetedTimeoutMillis: Long = 20_000,
    private val catalogTimeoutMillis: Long = 45_000,
    // Failed requests degrade silently by design (seed fallback) — without
    // this hook they would also be undiagnosable.
    private val logger: (String) -> Unit = {},
) {
    companion object {
        const val HOST = "https://transit.accessibility.cloud"
        const val ROOT = "/api"
        const val ELEVATORS = "elevators"
        const val STATUS_SPANS = "status-spans"
        const val STOP_PLACES = "stop-places"
    }

    private val client = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = targetedTimeoutMillis
            // The socket (read) timeout must cover the server's silent
            // thinking time — catalog pages send nothing for ~20-25 s.
            // Without this, OkHttp's 10 s default kills every page.
            socketTimeoutMillis = targetedTimeoutMillis
        }
        expectSuccess = false
    }

    // Fetch the full catalog: Elevators, StatusSpans and StopPlaces in
    // parallel, then join by id. Elevators and StopPlaces are required — a
    // partial fetch would silently degrade every favorite, so either failing
    // fails the whole catalog and callers keep previous values. StatusSpans
    // only enrich (disruption text, status-change date), so a failed span
    // fetch degrades gracefully. Only spans still active are requested — the
    // full collection is the outage history.
    //
    // Every request trims the response via select[] (the docs' "golden rule").
    suspend fun fetchCatalog(): List<AccessibilityCloudClient.Equipment>? = coroutineScope {
        val nowISO = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val elevators = async {
            fetchAllPages(
                ELEVATORS,
                extraQuery = select(
                    listOf("function", "short_visual"), listOf("location", "site"),
                    listOf("operational_status", "operational_status"),
                    listOf("operational_status", "current_status_span"),
                    listOf("linked_data", "operator_inventory_id"),
                    listOf("elevator_type"), listOf("stop_place_name"),
                    listOf("internal_description"), listOf("updatedAt"),
                ),
                parse = AccessibilityCloudClient::parseElevators,
            )
        }
        val spans = async {
            fetchAllPages(
                STATUS_SPANS,
                extraQuery = select(
                    listOf("elevator"), listOf("operational_status"),
                    listOf("start_date"), listOf("end_date"), listOf("description"),
                ) + listOf(
                    "where[or][0][end_date][exists]" to "false",
                    "where[or][1][end_date][greater_than]" to nowISO,
                ),
                // An empty page of active spans is legitimate (nothing broken).
                emptyFirstPageIsFailure = false,
                parse = AccessibilityCloudClient::parseStatusSpans,
            )
        }
        val stops = async {
            fetchAllPages(
                STOP_PLACES,
                extraQuery = select(
                    listOf("normalized_name"), listOf("main_identifier"),
                    listOf("modes", "servicedTransportModes"),
                ),
                parse = AccessibilityCloudClient::parseStopPlaces,
            )
        }
        val elevatorList = elevators.await() ?: return@coroutineScope null
        val stopList = stops.await() ?: return@coroutineScope null
        AccessibilityCloudClient.join(
            elevators = elevatorList,
            statusSpans = spans.await().orEmpty(),
            stopPlaces = stopList,
        )
    }

    // Resolve specific elevators in one request instead of building the full
    // catalog: where[id][in] narrows to the requested ids, depth=1 resolves
    // current_status_span inline, populate[] trims the span to the fields the
    // DTO reads. StopPlaces are not needed — the cached stop_place_name
    // covers the station name. null = request failed; ids the response
    // doesn't answer for are simply absent from the result.
    suspend fun fetchEquipment(ids: List<String>): List<AccessibilityCloudClient.Equipment>? {
        if (ids.isEmpty()) return emptyList()
        val query = select(
            listOf("function", "short_visual"),
            listOf("operational_status", "operational_status"),
            listOf("operational_status", "current_status_span"),
            listOf("linked_data", "operator_inventory_id"),
            listOf("elevator_type"), listOf("stop_place_name"),
            listOf("internal_description"), listOf("updatedAt"),
        ) + spanPopulate + listOf("where[id][in]" to ids.joinToString(","))
        val data = fetchPage(ELEVATORS, extraQuery = query, page = 1, depth = 1, timeoutMillis = null)
            ?: return null
        return AccessibilityCloudClient.joinTargeted(AccessibilityCloudClient.parseElevators(data).items)
    }

    // The populated span carries every span field by default — trim it to
    // what the StatusSpan DTO reads.
    private val spanPopulate = listOf(
        "elevator", "operational_status", "start_date", "end_date", "description", "updatedAt",
    ).map { "populate[status-spans][$it]" to "true" }

    // select[a][b]=true query items — only request the fields the DTOs read.
    private fun select(vararg paths: List<String>): List<Pair<String, String>> =
        paths.map { path -> "select" + path.joinToString("") { "[$it]" } to "true" }

    // Page 1 tells the page count (Payload envelope), the remaining pages
    // load in parallel. Server time is fixed cost per request, so waiting for
    // page 1 before fetching the rest doubles the build — the remembered page
    // count of the previous build lets all expected pages go out at once
    // (speculation: a stale count self-corrects below, at the price of a
    // wasted or late request).
    internal suspend fun <T> fetchAllPages(
        resource: String,
        extraQuery: List<Pair<String, String>> = emptyList(),
        emptyFirstPageIsFailure: Boolean = true,
        parse: (String) -> AccessibilityCloudClient.Page<T>,
    ): List<T>? {
        val speculative = AccessibilityCloudClient.speculated(pageCounts.hint(resource))
        val fetched = fetchPages(listOf(1) + speculative, resource, extraQuery).toMutableMap()
        val firstData = fetched[1] ?: run {
            logger("$resource: page 1 failed, list build aborted")
            return null
        }
        val first = parse(firstData)
        // A 200 that parses to nothing on the first page is indistinguishable
        // from a schema mismatch — treat it as failure rather than caching an
        // empty catalog.
        if (first.items.isEmpty()) {
            if (emptyFirstPageIsFailure) {
                logger("$resource: page 1 parsed to nothing, list build aborted")
                return null
            }
            return emptyList()
        }
        val all = first.items.toMutableList()
        val totalPages = first.totalPages
        if (totalPages != null) {
            pageCounts.remember(totalPages, resource)
            if (totalPages > 1) {
                // Second wave: pages the speculation missed, plus one retry
                // for speculative fetches that failed. Overshot pages (count
                // shrank) simply go unused.
                val missing = (2..totalPages).filter { fetched[it] == null }
                fetched.putAll(fetchPages(missing, resource, extraQuery))
                for (page in 2..totalPages) {
                    // All-or-nothing: a lost page would silently drop elevators.
                    val data = fetched[page] ?: run {
                        logger("$resource: page $page missing after retry, list build aborted")
                        return null
                    }
                    all.addAll(parse(data).items)
                }
            }
        } else if (first.hasNextPage ?: (first.items.size == AccessibilityCloudClient.PAGE_SIZE)) {
            // No page count in the envelope — sequential fallback (ignores
            // speculative results; this path shouldn't occur with Payload).
            var page = 2
            while (true) {
                val data = fetchPage(resource, extraQuery, page, timeoutMillis = catalogTimeoutMillis)
                    ?: break
                val parsed = parse(data)
                all.addAll(parsed.items)
                val more = parsed.hasNextPage ?: (parsed.items.size == AccessibilityCloudClient.PAGE_SIZE)
                if (!more || parsed.items.isEmpty()) break
                page += 1
            }
        }
        return all
    }

    private suspend fun fetchPages(
        pages: List<Int>,
        resource: String,
        extraQuery: List<Pair<String, String>>,
    ): Map<Int, String?> = coroutineScope {
        pages.map { page ->
            async {
                page to fetchPage(resource, extraQuery, page, timeoutMillis = catalogTimeoutMillis)
            }
        }.awaitAll().toMap()
    }

    private suspend fun fetchPage(
        resource: String,
        extraQuery: List<Pair<String, String>>,
        page: Int,
        depth: Int = 0,
        timeoutMillis: Long?,
    ): String? = try {
        val response = client.get("$HOST$ROOT/$resource") {
            // Lists stay flat (relations = numeric ids, upstream guidance);
            // only the targeted fetch resolves the current span inline.
            parameter("depth", depth.toString())
            // Published records only (recommended defaults). Only the app's
            // language instead of locale=all; missing translations fall back
            // to German server-side, per field.
            parameter("draft", "false")
            parameter("trash", "false")
            parameter("locale", AccessibilityCloudClient.requestLocale(appLocalization()))
            parameter("fallback-locale", "de")
            parameter("limit", AccessibilityCloudClient.PAGE_SIZE.toString())
            parameter("page", page.toString())
            for ((name, value) in extraQuery) parameter(name, value)
            bearerAuth(token)
            if (timeoutMillis != null) {
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
        }
        if (response.status == HttpStatusCode.OK) {
            response.bodyAsText()
        } else {
            logger("$resource p$page: HTTP ${response.status.value}")
            null
        }
    } catch (exception: HttpRequestTimeoutException) {
        logger("$resource p$page: timeout (${exception.message})")
        null
    } catch (exception: java.io.IOException) {
        logger("$resource p$page: ${exception.javaClass.simpleName} ${exception.message}")
        null
    }
}
