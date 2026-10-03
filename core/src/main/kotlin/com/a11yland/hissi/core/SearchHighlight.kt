package com.a11yland.hissi.core

import java.text.Normalizer

// The pure half of the search-result highlighting (iOS builds an
// AttributedString, Android an AnnotatedString — both need the matched range
// first): the first case- and diacritic-insensitive occurrence of the query,
// as indices into the *original* spelling ("schonhauser" highlights
// "Schönhauser"). Null when no contiguous match exists or the query is blank.
object SearchHighlight {
    fun range(name: String, query: String): IntRange? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        // Fold per character and remember which original index each folded
        // character came from, so the match maps back to original indices.
        val folded = StringBuilder()
        val sourceIndex = ArrayList<Int>()
        for ((index, char) in name.withIndex()) {
            for (foldedChar in fold(char)) {
                folded.append(foldedChar)
                sourceIndex.add(index)
            }
        }
        val needle = buildString { trimmed.forEach { append(fold(it)) } }
        val start = folded.indexOf(needle)
        if (start < 0 || needle.isEmpty()) return null
        return sourceIndex[start]..sourceIndex[start + needle.length - 1]
    }

    private val combiningMarks = Regex("\\p{Mn}+")

    private fun fold(char: Char): String =
        combiningMarks.replace(Normalizer.normalize(char.toString(), Normalizer.Form.NFD), "")
            .lowercase()
}
