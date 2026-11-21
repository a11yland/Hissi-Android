package com.a11yland.hissi.core

// Abbreviations station names use interchangeably with the full word — DB's
// "München Hbf" next to the stop places' "Berlin Hauptbahnhof". Applied to
// already folded (lowercased) text, whole words only, so "Hbf" expands but
// "Abfahrt" stays. Ported from `StationAliases` in
// Shared/EquipmentCatalog.swift. Pure, unit-tested.
object StationAliases {
    val pairs: List<Pair<String, String>> = listOf(
        "hbf" to "hauptbahnhof",
        "bhf" to "bahnhof",
        "bf" to "bahnhof",
    )

    // Per pair: the abbreviation (optionally with its dot) and the full word,
    // each as a whole word.
    private val forms = pairs.map { pair ->
        Triple(Regex("\\b${pair.first}\\b\\.?"), Regex("\\b${pair.second}\\b"), pair)
    }

    // Every abbreviation spelled out ("münchen hbf" → "münchen hauptbahnhof").
    fun expanded(folded: String): String =
        forms.fold(folded) { text, (shortRegex, _, pair) -> shortRegex.replace(text, pair.second) }

    // The query's other spellings, for the highlight: each full word as its
    // abbreviations and each abbreviation spelled out ("Hauptbahnhof" →
    // "hbf"). Case is folded; the highlight matches case-insensitively.
    fun variants(query: String): List<String> {
        val folded = query.lowercase()
        return forms.flatMap { (shortRegex, fullRegex, pair) ->
            listOfNotNull(
                fullRegex.takeIf { it.containsMatchIn(folded) }?.replace(folded, pair.first),
                shortRegex.takeIf { it.containsMatchIn(folded) }?.replace(folded, pair.second),
            )
        }
    }
}
