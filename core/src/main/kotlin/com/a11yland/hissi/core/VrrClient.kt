package com.a11yland.hissi.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The second elevator source: the VRR infrastructure API ("MIP", published
// on https://www.opendata-oepnv.de/ht/de/api, CC BY 4.0) — the elevators of
// the municipal networks in Nordrhein-Westfalen that
// transit.accessibility.cloud doesn't carry. One request returns every stop
// with its infrastructure and each lift's current state; there is nothing to
// join. This is the pure parser (the request is VrrApi). Ported from
// `Shared/VRRClient.swift`.
object VrrClient {
    const val SOURCE_NAME = "VRR"

    // A lift's own coordinate counts only this close to its stop: the feed
    // places some lifts kilometres away (a few in the Norwegian Sea). Same
    // rule and radius as the seed generator.
    const val PLAUSIBLE_LIFT_DISTANCE_METERS = 300.0

    // The known status codes of the feed's free-text state description. The
    // text itself is never shown — it also carries ticket notes with staff
    // names; the app words these codes itself (string resources), `german`
    // is the test vocabulary, as with the other :core labels.
    enum class Explanation(val german: String) {
        NoOperation("Kein Betrieb"),
        NotReady("Nicht betriebsbereit"),
        RemoteShutdown("Ferngesteuert abgeschaltet"),
        PassengerRescue("Störung (Personenbefreiung)"),
    }

    // Elevators only — the feed also lists escalators and ramps. null when
    // the data is not the expected list (a failure, not an empty answer).
    fun parse(
        data: String,
        now: Instant = Instant.now(),
        explain: (Explanation) -> String = { it.german },
    ): List<AccessibilityCloudClient.Equipment>? {
        val stops = runCatching { json.decodeFromString<List<Stop>>(data) }.getOrNull() ?: return null
        val byId = LinkedHashMap<String, AccessibilityCloudClient.Equipment>()
        for (stop in stops) {
            val dhid = stop.ifopt?.trim().orEmpty()
            if (dhid.isEmpty()) continue
            for (lift in stop.infrastructure.orEmpty()) {
                if (lift.type != "Aufzug") continue
                val resourceId = lift.resourceId?.trim().orEmpty()
                if (resourceId.isEmpty()) continue
                val equipment = equipment(lift, resourceId, stop, dhid, now, explain)
                // The feed lists a lift twice now and then — the fuller
                // description wins.
                val previous = byId[equipment.id]
                if (previous == null || equipment.description.length > previous.description.length) {
                    byId[equipment.id] = equipment
                }
            }
        }
        return byId.values.toList()
    }

    private fun equipment(
        lift: Infrastructure,
        resourceId: String,
        stop: Stop,
        dhid: String,
        now: Instant,
        explain: (Explanation) -> String,
    ): AccessibilityCloudClient.Equipment {
        val isWorking = statusMeansWorking(lift.state?.state)
        // The state's timestamp is the time of the last state change —
        // exactly "seit". Only with a known status, and never from the
        // future (a clock ahead of ours).
        val changed = AccessibilityCloudClient.date(lift.state?.timestamp)
        val coordinate = coordinate(lift, stop)
        return AccessibilityCloudClient.Equipment(
            id = ElevatorSource.vrrId(resourceId),
            stationId = dhid,
            // The raw stop name ("D-Kirchplatz U"); the seed carries the
            // normalised one and wins in the overlay.
            stationName = collapsed(stop.name),
            // The area label ("Allgemeiner Bereich", "SWB_O") says nothing;
            // the description tells two lifts apart.
            description = collapsed(lift.description),
            isWorking = isWorking,
            // "Datenstand" is the moment of the answer: this is the
            // operator's live system, its answer a current assertion. The
            // transition time would flag every lift that has simply worked
            // for weeks as stale.
            lastUpdateEpochMillis = now.toEpochMilli(),
            statusSinceEpochMillis = changed?.takeIf { isWorking != null && it <= now }?.toEpochMilli(),
            sourceName = SOURCE_NAME,
            stateExplanation = if (isWorking == false) explanation(lift.state?.description)?.let(explain) else null,
            latitude = coordinate?.first,
            longitude = coordinate?.second,
            region = TransitRegion.fromOriginalPlaceInfoId(dhid),
        )
    }

    // "in Ordnung" / "Störung"; anything else — a missing state, a word this
    // version doesn't know — is unknown, never a false "working".
    fun statusMeansWorking(state: String?): Boolean? = when (state?.trim()?.lowercase()) {
        "in ordnung" -> true
        "störung", "stoerung" -> false
        else -> null
    }

    // Only the known codes; operator free text is left out.
    fun explanation(description: String?): Explanation? = when (collapsed(description).lowercase()) {
        "kein betrieb" -> Explanation.NoOperation
        "nicht betriebsbereit" -> Explanation.NotReady
        "fernabschaltung" -> Explanation.RemoteShutdown
        "stoerung personbefreiung", "störung personenbefreiung" -> Explanation.PassengerRescue
        else -> null
    }

    // The lift's own coordinate if it is plausible, else the stop's.
    private fun coordinate(lift: Infrastructure, stop: Stop): Pair<Double, Double>? {
        val stopCoordinate = stop.latitude?.let { latitude -> stop.longitude?.let { latitude to it } }
        val latitude = lift.latitude ?: return stopCoordinate
        val longitude = lift.longitude ?: return stopCoordinate
        if (stopCoordinate == null) return latitude to longitude
        val distance = NearbyStations.distanceMeters(latitude, longitude, stopCoordinate.first, stopCoordinate.second)
        return if (distance <= PLAUSIBLE_LIFT_DISTANCE_METERS) latitude to longitude else stopCoordinate
    }

    // "Bochum  Gesundheitscampus" → "Bochum Gesundheitscampus".
    private fun collapsed(text: String?): String = text.orEmpty().trim().split(whitespace).joinToString(" ")

    private val whitespace = Regex("\\s+")
    private val json = Json { ignoreUnknownKeys = true }

    // Every field optional: one odd record must not cost the whole list.
    @Serializable
    private data class Stop(
        val ifopt: String? = null,
        val name: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val infrastructure: List<Infrastructure>? = null,
    )

    @Serializable
    private data class Infrastructure(
        val resourceId: String? = null,
        val type: String? = null,
        val description: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val state: State? = null,
    )

    @Serializable
    private data class State(
        val timestamp: String? = null,
        val state: String? = null,
        val description: String? = null,
    )
}
