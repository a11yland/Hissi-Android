package com.a11yland.hissi.core

import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// The pure half of the transit.accessibility.cloud client: DTO parsing, the
// local id join and the small request helpers. Ported from
// `Shared/AccessibilityCloudClient.swift`; the network machinery (pagination,
// targeted fetch) lives in TransitApi so an HTTP engine never leaks in here.
//
// The API is a Payload CMS: three flat collections — Elevators, StatusSpans,
// StopPlaces — with numeric ids and id references between them. The lists are
// fetched with minimal nesting (depth=0), joined locally; the live status is
// the elevator's own `operational_status.operational_status`, StatusSpans are
// the disruption history and contribute the human-readable reason and the
// status-change timestamp. Decoding stays lenient (Int-or-String ids,
// localized-map-or-plain strings) as a hedge against schema drift.
object AccessibilityCloudClient {
    const val PAGE_SIZE = 1000

    // MARK: Domain model

    // Serializable so the equipment catalog can persist the joined live
    // catalog across launches (stale-while-revalidate for the search).
    @kotlinx.serialization.Serializable
    data class Equipment(
        val id: String,           // numeric transit.accessibility.cloud id, as string
        // linked_data.operator_inventory_id — the operator's inventory number
        // (BVG "Fabriknummer", DB FaSta equipment number). Matches records to
        // the bundled seed.
        val inventoryId: String? = null,
        val stationId: String,    // national station number, e.g. "900193002"
        val stationName: String,
        // Networks the whole station serves (stop-place transport modes);
        // refines TransitNetwork.classify. Empty on seed-only records.
        val stationNetworks: Set<TransitNetwork> = emptySet(),
        val description: String,
        val isWorking: Boolean? = null,
        val lastUpdateEpochMillis: Long? = null,
        val sourceName: String = "",
        val organizationName: String = "",
        val stateExplanation: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        // DB FaSta equipment number from the bundled seed catalog.
        val fastaEquipmentNumber: Int? = null,
        // Position on the brokenlifts.org station page; only on
        // "brokenlifts-…" seed records. Becomes the favorite's elevatorIndex.
        val brokenliftsIndex: Int? = null,
        // Berlin/Brandenburg, from the stop place's DHID or the seed; null when
        // neither knows (search then falls back to the name heuristic).
        val region: TransitRegion? = null,
    ) {
        val lastUpdate: Instant? get() = lastUpdateEpochMillis?.let(Instant::ofEpochMilli)
    }

    // MARK: DTOs (lenient)

    data class Elevator(
        val id: String,
        val stopPlaceId: String? = null,          // location.site
        val description: String = "",             // function.short_visual
        val elevatorType: String? = null,         // elevator | escalator | moving_walkway
        val isWorking: Boolean? = null,           // operational_status.operational_status
        val currentStatusSpanId: String? = null,  // operational_status.current_status_span
        // The current span as a populated object — only in targeted (depth=1)
        // responses; null when the relation is a bare id.
        val inlineStatusSpan: StatusSpan? = null,
        val inventoryId: String? = null,          // linked_data.operator_inventory_id
        val stopPlaceName: String? = null,        // cached stop name, fallback only
        val lastUpdate: Instant? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    data class StatusSpan(
        val id: String? = null,
        // `elevator` is a hasMany relation — one span can affect several
        // elevators.
        val elevatorIds: List<String>,
        val isWorking: Boolean?,
        val start: Instant? = null,
        val end: Instant? = null,
        val lastUpdate: Instant? = null,
        // Human-readable disruption reason, e.g. "Wird repariert".
        val reason: String? = null,
    )

    data class StopPlace(
        val id: String,
        val name: String,
        // DHID/IFOPT id (main_identifier), e.g. "de:11000:900193002" — source
        // of the national station number and the Berlin/Brandenburg region.
        val originalId: String? = null,
        // modes.servicedTransportModes ids (1 = S-Bahn, 2 = U-Bahn,
        // 3 = Regional- und Fernbahn) — the networks the station serves.
        val modeIds: List<Int> = emptyList(),
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    data class Page<T>(val items: List<T>, val hasNextPage: Boolean?, val totalPages: Int?)

    // MARK: Pure parsing (unit-tested)

    fun parseElevators(data: String): Page<Elevator> = parseList(data, ::parseElevator)

    fun parseStatusSpans(data: String): Page<StatusSpan> = parseList(data) { parseStatusSpan(it) }

    fun parseStopPlaces(data: String): Page<StopPlace> = parseList(data, ::parseStopPlace)

    // Accepts the Payload CMS envelope ({"docs": […], "hasNextPage": …,
    // "totalPages": …}) or a bare array. A parser returning null (record
    // without id) fails the whole page, mirroring Swift's throwing decoders —
    // an unparseable page must not silently drop elevators.
    private fun <T> parseList(data: String, parseItem: (JsonObject) -> T?): Page<T> {
        val empty = Page<T>(emptyList(), null, null)
        val root = runCatching { Json.parseToJsonElement(data) }.getOrNull() ?: return empty
        val (docs, envelope) = when {
            root is JsonArray -> root to null
            root is JsonObject && root["docs"] is JsonArray -> root["docs"] as JsonArray to root
            else -> return empty
        }
        val items = ArrayList<T>(docs.size)
        for (element in docs) {
            val item = (element as? JsonObject)?.let(parseItem) ?: return empty
            items.add(item)
        }
        return Page(
            items = items,
            hasNextPage = envelope?.let { Lenient.bool(it, listOf("hasNextPage")) },
            totalPages = envelope?.let { Lenient.int(it, listOf("totalPages")) },
        )
    }

    private fun parseElevator(json: JsonObject): Elevator? {
        val id = Lenient.id(json, listOf("id")) ?: return null
        // Groups are nested objects even at depth=0; only relations collapse
        // to ids.
        val location = json["location"] as? JsonObject
        val function = json["function"] as? JsonObject
        val status = json["operational_status"] as? JsonObject
        val linked = json["linked_data"] as? JsonObject
        val point = Lenient.point(json)
        return Elevator(
            id = id,
            stopPlaceId = location?.let { Lenient.id(it, listOf("site")) },
            // DB-feed elevators without a stop-place link carry their only
            // text in internal_description ("zu Gleis 1/1a").
            description = function?.let { Lenient.localizedText(it, listOf("short_visual", "short_tts")) }
                ?: Lenient.localizedText(json, listOf("internal_description", "description", "name"))
                ?: "",
            elevatorType = Lenient.string(json, listOf("elevator_type")),
            isWorking = status
                ?.let { Lenient.string(it, listOf("operational_status")) }
                ?.let(::statusMeansWorking),
            currentStatusSpanId = status?.let { Lenient.id(it, listOf("current_status_span")) },
            // A keyed object is a populated (depth=1) relation — a bare id
            // must not masquerade as a span.
            inlineStatusSpan = (status?.get("current_status_span") as? JsonObject)?.let(::parseStatusSpan),
            inventoryId = linked?.let { Lenient.string(it, listOf("operator_inventory_id")) },
            stopPlaceName = Lenient.localizedText(json, listOf("stop_place_name")),
            lastUpdate = date(Lenient.string(json, listOf("updatedAt"))),
            latitude = point?.first,
            longitude = point?.second,
        )
    }

    private fun parseStatusSpan(json: JsonObject): StatusSpan = StatusSpan(
        id = Lenient.id(json, listOf("id")),
        elevatorIds = Lenient.idArray(json, listOf("elevator")),
        isWorking = Lenient.string(json, listOf("operational_status"))?.let(::statusMeansWorking),
        start = date(Lenient.string(json, listOf("start_date"))),
        end = date(Lenient.string(json, listOf("end_date"))),
        lastUpdate = date(Lenient.string(json, listOf("updatedAt"))),
        reason = Lenient.localizedText(json, listOf("description", "title")),
    )

    private fun parseStopPlace(json: JsonObject): StopPlace? {
        val id = Lenient.id(json, listOf("id")) ?: return null
        val modes = json["modes"] as? JsonObject
        val point = Lenient.point(json)
        return StopPlace(
            id = id,
            // Stop places carry no display "name" field — normalized_name is
            // the human-readable one ("Seestraße (Berlin)").
            name = Lenient.localizedText(json, listOf("normalized_name", "name")) ?: "",
            originalId = Lenient.string(json, listOf("main_identifier")),
            modeIds = modes?.let { Lenient.idArray(it, listOf("servicedTransportModes")) }
                .orEmpty()
                .mapNotNull(String::toIntOrNull),
            latitude = point?.first,
            longitude = point?.second,
        )
    }

    // MARK: Join (pure, unit-tested)

    // Resolve the numeric id references between the three lists into the flat
    // Equipment model the app runs on. The elevator's own operational_status
    // is the live status (upstream polls the operator feeds every ~2 min);
    // the current status span supplies the disruption text and the
    // status-change date. `unknown` (or an unrecognized word) stays unknown —
    // never a false "working"; a span covering `now` may still settle it.
    fun join(
        elevators: List<Elevator>,
        statusSpans: List<StatusSpan>,
        stopPlaces: List<StopPlace>,
        now: Instant = Instant.now(),
    ): List<Equipment> {
        val stopsById = firstWins(stopPlaces.map { it.id to it })
        val spansById = firstWins(statusSpans.mapNotNull { span -> span.id?.let { it to span } })
        val spansByElevator = statusSpans
            .flatMap { span -> span.elevatorIds.map { it to span } }
            .groupBy({ it.first }, { it.second })

        return elevators.mapNotNull { elevator ->
            // Only elevators are tracked — the collection also carries
            // escalators and moving walkways.
            if (elevator.elevatorType != null && elevator.elevatorType != "elevator") return@mapNotNull null
            val stop = elevator.stopPlaceId?.let(stopsById::get)
            val span = elevator.currentStatusSpanId?.let(spansById::get)
                ?: currentSpan(spansByElevator[elevator.id].orEmpty(), now)
            val isWorking = elevator.isWorking ?: span?.isWorking
            Equipment(
                id = elevator.id,
                inventoryId = elevator.inventoryId,
                stationId = stationNumber(stop?.originalId),
                stationName = stop?.name ?: elevator.stopPlaceName ?: "",
                stationNetworks = stop?.modeIds.orEmpty()
                    .mapNotNull(TransitNetwork::fromTransportModeId)
                    .toSet(),
                description = elevator.description,
                isWorking = isWorking,
                // "Stand": when the status last changed (span start), not
                // when the record was last touched.
                lastUpdateEpochMillis = (span?.start ?: span?.lastUpdate ?: elevator.lastUpdate)
                    ?.toEpochMilli(),
                stateExplanation = if (isWorking == false) span?.reason else null,
                latitude = elevator.latitude ?: stop?.latitude,
                longitude = elevator.longitude ?: stop?.longitude,
                region = TransitRegion.fromOriginalPlaceInfoId(stop?.originalId),
            )
        }
    }

    // Join for a targeted (depth=1) response: the spans come inline on the
    // elevators instead of from a separate list. Pure, unit-tested.
    fun joinTargeted(elevators: List<Elevator>, now: Instant = Instant.now()): List<Equipment> = join(
        elevators = elevators,
        statusSpans = elevators.mapNotNull { it.inlineStatusSpan },
        stopPlaces = emptyList(),
        now = now,
    )

    // The span that covers `now`: already started (or no start) and not yet
    // ended (or no end). Several matches ⇒ the latest start wins.
    fun currentSpan(spans: List<StatusSpan>, now: Instant = Instant.now()): StatusSpan? = spans
        .filter { span ->
            (span.start?.let { it <= now } ?: true) && (span.end?.let { it > now } ?: true)
        }
        .maxByOrNull { it.start ?: Instant.MIN }

    // "de:11000:900193002" → "900193002"; falls back to the raw id.
    fun stationNumber(mainIdentifier: String?): String {
        if (mainIdentifier == null) return ""
        return mainIdentifier.split(":").lastOrNull { it.isNotEmpty() } ?: mainIdentifier
    }

    fun date(string: String?): Instant? {
        if (string == null) return null
        // ISO_INSTANT accepts both fractional and plain timestamps.
        return try {
            Instant.parse(string)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // Maps an operational_status word onto isWorking. Elevators use
    // in_service / out_of_service / partially_operational / unknown, status
    // spans use operational / out_of_service / unknown. A partially
    // operational elevator is not reliably usable ⇒ broken. Unknown words ⇒
    // null, not false: an unrecognized status must read "unbekannt", not
    // "defekt".
    fun statusMeansWorking(raw: String): Boolean? = when (raw.lowercase().replace("_", "-")) {
        "in-service", "operational", "working", "ok", "active", "in-operation", "functional" -> true
        "out-of-service", "partially-operational", "broken", "out-of-order",
        "outoforder", "defect", "defective", "disruption", "closed",
        "inaccessible", "not-working", "notworking", "maintenance",
        "under-maintenance" -> false
        else -> null
    }

    // Maps the app's resolved localization onto the API's locales (de/en, the
    // same pair the app ships). Everything non-English — including null in
    // contexts without a locale — is German, the development and canonical
    // source language. Pure, unit-tested.
    fun requestLocale(appLocalization: String?): String =
        if (appLocalization?.startsWith("en") == true) "en" else "de"

    // The extra pages worth requesting alongside page 1, from the remembered
    // count. Pure, unit-tested. Capped: a corrupt hint must not fan out into
    // dozens of wasted requests.
    fun speculated(pageCountHint: Int?): List<Int> {
        if (pageCountHint == null || pageCountHint <= 1) return emptyList()
        return (2..minOf(pageCountHint, 20)).toList()
    }

    // MARK: Search (pure name filter, shared by both search phases)

    fun matchStations(query: String, catalog: List<Equipment>): List<Equipment> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        return catalog.filter { normalize(it.stationName).contains(needle) }
    }

    // Case- and diacritic-insensitive folding ("schonhauser" matches
    // Schönhauser); "ß" is its own letter, not a diacritic, so it stays.
    private val combiningMarks = Regex("\\p{Mn}+")

    private fun normalize(string: String): String =
        combiningMarks.replace(Normalizer.normalize(string, Normalizer.Form.NFD), "")
            .lowercase()

    // Swift's Dictionary(_, uniquingKeysWith: { first, _ in first }) — Kotlin's
    // associate would silently keep the *last* duplicate instead.
    private fun <K, V> firstWins(pairs: List<Pair<K, V>>): Map<K, V> {
        val map = LinkedHashMap<K, V>(pairs.size)
        for ((key, value) in pairs) map.putIfAbsent(key, value)
        return map
    }

    // MARK: Lenient decoding helpers

    // A value that is either a localized map like { "de": "…" } or a plain
    // string (depends on the request's locale parameter). Individual
    // languages may be null. Prefers the user's language; German is the
    // canonical source language, and any value beats none.
    internal object Lenient {
        fun string(json: JsonObject, keys: List<String>): String? {
            for (key in keys) {
                val primitive = json[key] as? JsonPrimitive ?: continue
                if (primitive.isString) return primitive.content
            }
            return null
        }

        // A relation id: Int or String at depth=0, an object with an id at
        // depth>0. Normalized to String.
        fun id(json: JsonObject, keys: List<String>): String? {
            for (key in keys) {
                when (val value = json[key]) {
                    is JsonPrimitive -> {
                        value.longOrNull?.let { return it.toString() }
                        if (value.isString) return value.content
                    }
                    is JsonObject -> id(value, listOf("id"))?.let { return it }
                    else -> {}
                }
            }
            return null
        }

        // A hasMany relation: an array of ids (or populated objects), or a
        // single one.
        fun idArray(json: JsonObject, keys: List<String>): List<String> {
            for (key in keys) {
                val array = json[key] as? JsonArray
                if (array != null) {
                    return array.mapNotNull { element ->
                        when (element) {
                            is JsonPrimitive -> element.longOrNull?.toString()
                                ?: element.takeIf { it.isString }?.content
                            is JsonObject -> id(element, listOf("id"))
                            else -> null
                        }
                    }
                }
                id(json, listOf(key))?.let { return listOf(it) }
            }
            return emptyList()
        }

        fun int(json: JsonObject, keys: List<String>): Int? {
            for (key in keys) {
                val primitive = json[key] as? JsonPrimitive ?: continue
                if (!primitive.isString) primitive.intOrNull?.let { return it }
            }
            return null
        }

        fun bool(json: JsonObject, keys: List<String>): Boolean? {
            for (key in keys) {
                val primitive = json[key] as? JsonPrimitive ?: continue
                primitive.booleanOrNull?.let { return it }
            }
            return null
        }

        fun localizedText(json: JsonObject, keys: List<String>): String? {
            for (key in keys) {
                val value = json[key]
                if (value is JsonObject && value.values.all { it is JsonNull || (it as? JsonPrimitive)?.isString == true }) {
                    val values = value.mapNotNull { (language, element) ->
                        (element as? JsonPrimitive)?.takeIf { it.isString }?.let { language to it.content }
                    }.toMap()
                    val preferred = Locale.getDefault().language
                    values[preferred]?.let { return it }
                    (values["de"] ?: values.values.firstOrNull())?.let { return it }
                }
                if (value is JsonPrimitive && value.isString) return value.content
            }
            return null
        }

        // Coordinates as GeoJSON geometry ([longitude, latitude]) or flat
        // latitude/longitude fields. Returns (latitude, longitude).
        fun point(json: JsonObject): Pair<Double, Double>? {
            val coordinates = ((json["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            if (coordinates?.size == 2) return coordinates[1] to coordinates[0]
            val latitude = (json["latitude"] as? JsonPrimitive)?.doubleOrNull
            val longitude = (json["longitude"] as? JsonPrimitive)?.doubleOrNull
            if (latitude != null && longitude != null) return latitude to longitude
            return null
        }
    }
}
