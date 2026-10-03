package com.a11yland.hissi.core

// Region and network classification behind the grouped search results. Pure
// string classifiers, ported from `Shared/TransitClassification.swift`.

// Berlin vs Brandenburg. accessibility.cloud's originalPlaceInfoId carries the
// AGS ("de:11000:…" = Berlin, "de:12xxx:…" = a Brandenburg district); records
// without one (BVG puts the station name there) fall back to the seed's
// region, then to the name heuristic.
enum class TransitRegion(val rawValue: String) {
    Berlin("berlin"),
    Brandenburg("brandenburg");

    val label: String
        get() = when (this) {
            Berlin -> "Berlin"
            Brandenburg -> "Brandenburg"
        }

    companion object {
        fun fromRawValue(raw: String?): TransitRegion? = entries.firstOrNull { it.rawValue == raw }

        fun fromOriginalPlaceInfoId(id: String?): TransitRegion? = when {
            id == null -> null
            id.startsWith("de:11") -> Berlin
            id.startsWith("de:12") -> Brandenburg
            else -> null
        }

        // U-Bahn exists only in Berlin; VBB suffixes Berlin stations with
        // "(Berlin)", DB prefixes them with "Berlin".
        fun inferred(stationName: String): TransitRegion {
            val name = stationName.trim()
            return if (name.contains("(Berlin)") || name.startsWith("Berlin")
                || name.startsWith("U ") || name.startsWith("S+U")
            ) Berlin else Brandenburg
        }
    }
}

// Which network an individual elevator serves. Declared in display order —
// the ordinal doubles as the sort key for the subgroups within a station.
enum class TransitNetwork {
    UBahn, SBahn, Regional,
    // Street/mezzanine elevators at S+U stations serve every network; forcing
    // them into one would be wrong, so they get their own bucket.
    Access;

    // German literals double as the localization keys, as on iOS.
    val label: String
        get() = when (this) {
            UBahn -> "U-Bahn"
            SBahn -> "S-Bahn"
            Regional -> "Regionalverkehr"
            Access -> "Zugang"
        }

    companion object {
        // transit.accessibility.cloud transport-mode entities, served on the
        // stop place as modes.servicedTransportModes (CMS ids, verified live
        // 2026-08-03: 1 = S-Bahn, 2 = U-Bahn, 3 = Regional- und Fernbahn).
        fun fromTransportModeId(id: Int): TransitNetwork? = when (id) {
            1 -> SBahn
            2 -> UBahn
            3 -> Regional
            else -> null
        }

        private val uPlatform = Regex("""U-?\s?Bahnsteig|Bahnsteig U\d|\bU\d\b""")
        private val sPlatform = Regex("""S-?\s?Bahnsteig|Bahnsteig S|S-Bahn""")

        // The source feed settles most records; at S+U stations the BVG feed
        // also carries the S-Bahn platform elevators (e.g. Pankow), so the
        // description has to tell U from S there. `stationModes` are the
        // networks the whole station serves (from the stop place's transport
        // modes): they settle DB records at unprefixed S-Bahn stations
        // (Waßmannsdorf, Hoppegarten, …) and unmarked elevators at
        // single-network stations. Seed-only records carry no modes — there
        // the old name/description fallbacks remain.
        fun classify(
            description: String,
            stationName: String,
            sourceName: String,
            stationModes: Set<TransitNetwork> = emptySet(),
        ): TransitNetwork {
            if (sourceName.contains("S-Bahn")) return SBahn        // VBB Anlagen (S-Bahn)
            if (sourceName.contains("DB Regio")) return Regional   // VBB-Anlagen (DB Regio)
            if (sourceName == "DB FaSta") {
                if (stationName.startsWith("S ") || stationModes == setOf(SBahn)) return SBahn
                return Regional
            }
            // BVG / brokenlifts.
            if (uPlatform.containsMatchIn(description)) return UBahn
            if (sPlatform.containsMatchIn(description)) return SBahn
            if (stationName.startsWith("U ")) return UBahn
            if (stationName.startsWith("S ")) return SBahn
            if (sourceName.startsWith("BVG") && !stationName.startsWith("S+U")) return UBahn
            // A station serving exactly one network: its unmarked elevators
            // serve that network too. Multi-network stations keep the
            // "Zugang" bucket.
            stationModes.singleOrNull()?.let { return it }
            return Access
        }
    }
}
