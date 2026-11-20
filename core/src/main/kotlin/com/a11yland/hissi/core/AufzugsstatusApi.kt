package com.a11yland.hissi.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// The network half of the Aufzugsstatus API client (the pure half is
// AufzugsstatusClient), ported from the request section of
// `Shared/AufzugsstatusClient.swift`: station search, the per-id favorites
// refresh and the nearby lookup. No API key; fair use upstream asks for a
// descriptive User-Agent and ~60 requests/min per IP. Fails fast enough for
// a WorkManager refresh; live status never comes from a cache.
class AufzugsstatusApi(
    engine: HttpClientEngine,
    // "Hissi-Android/1.3 (hissi.app)" — app, version, contact.
    private val userAgent: String,
    timeoutMillis: Long = 20_000,
    private val logger: (String) -> Unit = {},
) {
    private val client = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
        expectSuccess = false
    }

    // One retry on 429 after the server's Retry-After (AufzugsstatusClient
    // .retryDelaySeconds), every other failure is null right away.
    private suspend fun fetch(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        retrying: Boolean = true,
    ): String? = try {
        val response = client.get("${AufzugsstatusClient.HOST}/$path") {
            for ((name, value) in query) parameter(name, value)
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json")
        }
        when {
            response.status == HttpStatusCode.OK -> response.bodyAsText()
            response.status == HttpStatusCode.TooManyRequests && retrying -> {
                val seconds = AufzugsstatusClient.retryDelaySeconds(response.headers[HttpHeaders.RetryAfter])
                logger("Aufzugsstatus $path: HTTP 429, retrying in $seconds s")
                delay((seconds * 1000).toLong())
                fetch(path, query, retrying = false)
            }
            else -> {
                logger("Aufzugsstatus $path: HTTP ${response.status.value}")
                null
            }
        }
    } catch (e: Exception) {
        logger("Aufzugsstatus $path: ${e.message}")
        null
    }

    // Station search: the lifts whose station name contains the query, as
    // catalog records. null = request failed; empty = too short to ask, or
    // no match. Callers drop what the local catalog already shows
    // (AufzugsstatusClient.excludingKnown).
    suspend fun searchLifts(query: String): List<AccessibilityCloudClient.Equipment>? {
        val trimmed = query.trim()
        if (trimmed.length < AufzugsstatusClient.MINIMUM_QUERY_LENGTH) return emptyList()
        val data = fetch(
            AufzugsstatusClient.LIFTS,
            listOf("q" to trimmed, "limit" to AufzugsstatusClient.SEARCH_LIMIT.toString()),
        ) ?: return null
        val lifts = AufzugsstatusClient.parseLifts(data) ?: return null
        return AufzugsstatusClient.equipment(lifts)
    }

    // The favorites refresh for national ids: one GET per id, in parallel —
    // the API has no batch endpoint. Ids it doesn't answer for (failure,
    // 404) are absent from the result; the caller keeps their previous
    // values.
    suspend fun fetchLifts(ids: Set<String>): Map<String, AccessibilityCloudClient.Equipment> {
        if (ids.isEmpty()) return emptyMap()
        val records = coroutineScope {
            ids.map { id ->
                async {
                    val data = fetch("${AufzugsstatusClient.LIFTS}/$id") ?: return@async null
                    val lift = AufzugsstatusClient.parseLift(data) ?: return@async null
                    AufzugsstatusClient.equipment(listOf(lift)).firstOrNull()
                }
            }.awaitAll()
        }
        return records.filterNotNull().associateBy { it.id }
    }

    // The lifts of the stations around a position, as catalog records with
    // live statuses — the same input NearbyStations.nearest takes from the
    // local catalog. Only the coarsened coordinate leaves the device; the
    // walking-distance cut happens in the caller's sort, over the true one.
    // null = the lookup failed; empty = no station around. Callers ask only
    // when the local catalog found nothing in reach, so a Berlin position
    // never reaches this server at all.
    suspend fun nearbyEquipment(latitude: Double, longitude: Double): List<AccessibilityCloudClient.Equipment>? {
        val data = fetch(
            AufzugsstatusClient.NEAR,
            listOf(
                "lat" to AufzugsstatusClient.coarsened(latitude).toString(),
                "lon" to AufzugsstatusClient.coarsened(longitude).toString(),
                "radius_km" to AufzugsstatusClient.NEARBY_REQUEST_RADIUS_KM.toString(),
                "limit" to AufzugsstatusClient.NEARBY_REQUEST_LIMIT.toString(),
            ),
        ) ?: return null
        val stations = AufzugsstatusClient.parseNearbyStations(data) ?: return null
        val named = stations.filter { !AufzugsstatusClient.isPlaceholderName(it.name) }
        if (named.isEmpty()) return emptyList()

        // The lifts of every candidate, in parallel — a handful of stations
        // within two kilometres. By station number where there is one (DB),
        // by name otherwise (city operators).
        val lifts = coroutineScope {
            named.map { station ->
                async {
                    val query = station.stationNumber?.let { listOf("station_number" to it.toString()) }
                        ?: listOf("q" to station.name)
                    val body = fetch(AufzugsstatusClient.LIFTS, query + ("limit" to "200"))
                        ?: return@async emptyList()
                    val parsed = AufzugsstatusClient.parseLifts(body) ?: return@async emptyList()
                    AufzugsstatusClient.placed(parsed, station)
                }
            }.awaitAll().flatten()
        }
        // Deduplicated: two candidates sharing a lift must not count it twice.
        return AufzugsstatusClient.equipment(lifts.distinctBy { it.id })
    }

    // The catalog to sort the nearby list from: the local one as it is while a
    // VBB station is in reach, otherwise the local one plus whatever the
    // national API adds — the city operators' stations and DB stations the
    // bundled list doesn't know yet (records the catalog already shows are
    // dropped). The API's records carry live statuses; the caller's status
    // refresh answers numeric ids only and leaves them as they are. A failed
    // lookup returns the local catalog unchanged.
    suspend fun nearbyCatalog(
        latitude: Double,
        longitude: Double,
        catalog: List<AccessibilityCloudClient.Equipment>,
    ): List<AccessibilityCloudClient.Equipment> {
        if (!AufzugsstatusClient.needsNationalLookup(latitude, longitude, catalog)) return catalog
        val national = nearbyEquipment(latitude, longitude) ?: return catalog
        return catalog + AufzugsstatusClient.excludingKnown(national, AufzugsstatusClient.knownInventoryNumbers(catalog))
    }

    // Stations within walking distance according to the national API alone,
    // as the nearby list's rows — live statuses included. null = lookup
    // failed. UIs go through nearbyCatalog.
    suspend fun nearbyStations(
        latitude: Double,
        longitude: Double,
        limit: Int = 5,
    ): List<NearbyStations.Station>? {
        val equipment = nearbyEquipment(latitude, longitude) ?: return null
        return NearbyStations.nearest(latitude, longitude, equipment, limit = limit)
    }
}
