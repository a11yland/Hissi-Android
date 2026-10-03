package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported from HissiTests/Tests/EquipmentCatalogTests.swift
// (HighlightedStationNameTests), adapted from AttributedString runs to the
// matched range — the UI layer turns the range into an AnnotatedString.
class SearchHighlightTest {
    private fun highlighted(name: String, query: String): String? =
        SearchHighlight.range(name, query)?.let { name.substring(it.first, it.last + 1) }

    @Test
    fun boldsTheMatchedSubstringOnly() {
        assertEquals("kreuz", highlighted("S Ostkreuz (Berlin)", "kreuz"))
    }

    // The range must live in the original spelling even when the match
    // succeeded only via case/diacritic folding.
    @Test
    fun caseAndDiacriticInsensitiveMatchBoldsOriginalSpelling() {
        assertEquals("Schönhauser", highlighted("Schönhauser Allee (Berlin)", "schonhauser"))
        assertEquals("Ostkreuz", highlighted("S Ostkreuz (Berlin)", "OSTKREUZ"))
    }

    @Test
    fun noMatchOrBlankQueryStaysPlain() {
        assertNull(highlighted("S Ostkreuz (Berlin)", "Pankow"))
        assertNull(highlighted("S Ostkreuz (Berlin)", "   "))
    }
}
