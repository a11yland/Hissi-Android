package com.a11yland.hissi.core

// Region and network classification behind the grouped search results. Pure
// string classifiers, ported from `Shared/TransitClassification.swift`.

// The federal state a station lies in — the top level of the grouped search
// results. A stop place's DHID carries the AGS ("de:11000:…" = Berlin,
// "de:09…" = Bayern); the generator stamps every seed record with its
// station's state, and records without either fall back to the name
// heuristic. Declared in display order: the home network first, then
// alphabetical. The raw values are the seed's `region` values — never rename
// them, the persisted catalog encodes them too. `label` is test vocabulary;
// the UI goes through string resources (`regionLabel`).
enum class TransitRegion(val rawValue: String, val label: String) {
    Berlin("berlin", "Berlin"),
    Brandenburg("brandenburg", "Brandenburg"),
    BadenWuerttemberg("baden-wuerttemberg", "Baden-Württemberg"),
    Bayern("bayern", "Bayern"),
    Bremen("bremen", "Bremen"),
    Hamburg("hamburg", "Hamburg"),
    Hessen("hessen", "Hessen"),
    MecklenburgVorpommern("mecklenburg-vorpommern", "Mecklenburg-Vorpommern"),
    Niedersachsen("niedersachsen", "Niedersachsen"),
    NordrheinWestfalen("nordrhein-westfalen", "Nordrhein-Westfalen"),
    RheinlandPfalz("rheinland-pfalz", "Rheinland-Pfalz"),
    Saarland("saarland", "Saarland"),
    Sachsen("sachsen", "Sachsen"),
    SachsenAnhalt("sachsen-anhalt", "Sachsen-Anhalt"),
    SchleswigHolstein("schleswig-holstein", "Schleswig-Holstein"),
    Thueringen("thueringen", "Thüringen");

    companion object {
        fun fromRawValue(raw: String?): TransitRegion? = entries.firstOrNull { it.rawValue == raw }

        // The AGS state key ("de:11…") of a DHID.
        fun fromOriginalPlaceInfoId(id: String?): TransitRegion? {
            if (id == null || !id.startsWith("de:") || id.length < 5) return null
            return byStateKey[id.substring(3, 5)]
        }

        private val byStateKey = mapOf(
            "01" to SchleswigHolstein, "02" to Hamburg, "03" to Niedersachsen, "04" to Bremen,
            "05" to NordrheinWestfalen, "06" to Hessen, "07" to RheinlandPfalz,
            "08" to BadenWuerttemberg, "09" to Bayern, "10" to Saarland, "11" to Berlin,
            "12" to Brandenburg, "13" to MecklenburgVorpommern, "14" to Sachsen,
            "15" to SachsenAnhalt, "16" to Thueringen,
        )

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

        private val dbSBahnPlatform = Regex("""S-?\s?Bahnsteig|\bS-Bahn\b""")
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
            organizationName: String = "",
            stationModes: Set<TransitNetwork> = emptySet(),
        ): TransitNetwork {
            if (sourceName.contains("S-Bahn")) return SBahn        // VBB Anlagen (S-Bahn)
            if (sourceName.contains("DB Regio")) return Regional   // VBB-Anlagen (DB Regio)
            // DB elevators: the FaSta feed, or — for the ~300 the platform
            // carries without a feed — the operator.
            if (sourceName == "DB FaSta" || organizationName == "DB InfraGO") {
                // DB marks S-Bahn platforms in the text ("Gl. 3/4 (S-Bahn)",
                // "S-Bahnsteig Gl. 22") — the only hint at stations that
                // serve both, and at the assigned stations, which carry no
                // modes.
                if (stationName.startsWith("S ") || stationModes == setOf(SBahn) ||
                    dbSBahnPlatform.containsMatchIn(description)
                ) {
                    return SBahn
                }
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
