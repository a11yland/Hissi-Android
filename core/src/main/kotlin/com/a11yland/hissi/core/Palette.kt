package com.a11yland.hissi.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The app's fixed colours and the WCAG arithmetic that keeps them honest —
// ported from `Shared/Palette.swift` plus the iOS colour catalog
// (`Shared/Colors.xcassets`, the "Creme-Lila" palette from the design
// package). Every pair is (light, dark). The tint is the palette's button
// lilac: WCAG AAA (7:1) as text on every ground it is drawn on — the
// design's lighter #69548D would be 5.9:1 on cream. Hex strings rather than
// Colors so the pure test module can do the maths on the same values the
// views use.
object Palette {
    // Tint (iOS AccentColor): buttons, links, the favorite star, the map pin.
    const val TINT_LIGHT = "#503C74"
    const val TINT_DARK = "#D4BBFC"

    // Label on a filled (tint-coloured) button — white on the pale dark tint
    // would be 1.7:1.
    const val ON_TINT_LIGHT = "#FBF3E4"
    const val ON_TINT_DARK = "#2A1F3A"

    // Grounds: the screen (cream / deep purple), the rows' surface, and the
    // design's second surface tone.
    const val BACKGROUND_LIGHT = "#FBF3E4"
    const val BACKGROUND_DARK = "#120E17"
    const val SURFACE_LIGHT = "#FFFBF4"
    const val SURFACE_DARK = "#221B2B"
    const val SURFACE_HIGH_LIGHT = "#F3E8D6"
    const val SURFACE_HIGH_DARK = "#2E2539"
    const val SEPARATOR_LIGHT = "#E3D6C0"
    const val SEPARATOR_DARK = "#3A3046"
    const val TEXT_SECONDARY_LIGHT = "#5A4E66"
    const val TEXT_SECONDARY_DARK = "#B9AEC2"

    // Status colours follow the mark: ping green / amber / red. Each comes as
    // a symbol colour (the icon) and a darker text variant that stays AA on
    // every ground; the symbol alone never carries the status — its shape
    // does (ElevatorStatus.icon).
    const val STATUS_OK_SYMBOL_LIGHT = "#00806E"
    const val STATUS_OK_SYMBOL_DARK = "#3FE0C5"
    const val STATUS_OK_TEXT_LIGHT = "#006B5C"
    const val STATUS_OK_TEXT_DARK = "#3FE0C5"
    const val STATUS_UNKNOWN_SYMBOL_LIGHT = "#D97706"
    const val STATUS_UNKNOWN_SYMBOL_DARK = "#FBBF24"
    const val STATUS_UNKNOWN_TEXT_LIGHT = "#8A4B08"
    const val STATUS_UNKNOWN_TEXT_DARK = "#FBBF24"
    const val STATUS_DOWN_SYMBOL_LIGHT = "#E11D48"
    const val STATUS_DOWN_SYMBOL_DARK = "#FB7185"
    const val STATUS_DOWN_TEXT_LIGHT = "#B4123A"
    const val STATUS_DOWN_TEXT_DARK = "#FB7185"

    // The mark's own colours (frame, chevrons/dot) — lilac and green on light,
    // cream and mint on dark.
    const val MARK_FRAME_LIGHT = "#503C74"
    const val MARK_FRAME_DARK = "#FBF3E4"
    const val MARK_ACCENT_LIGHT = "#00806E"
    const val MARK_ACCENT_DARK = "#3FE0C5"

    // Network badges: a white letter on the network's colour. Brand-adjacent
    // darkenings of U-Bahn blue, S-Bahn green and DB red — the originals put
    // white text well below 7:1.
    const val U_BAHN_BADGE = "#0040A0"
    const val S_BAHN_BADGE = "#006400"
    const val REGIONAL_BADGE = "#B00000"
    const val ACCESS_BADGE = "#555555"
    val badges: List<String> = listOf(U_BAHN_BADGE, S_BAHN_BADGE, REGIONAL_BADGE, ACCESS_BADGE)

    // Grounds the tint and the text colours are drawn on.
    val lightGrounds: List<String> = listOf(BACKGROUND_LIGHT, SURFACE_LIGHT, SURFACE_HIGH_LIGHT)
    val darkGrounds: List<String> = listOf(BACKGROUND_DARK, SURFACE_DARK, SURFACE_HIGH_DARK)

    // The text colours that must read on every ground: the status words and
    // the secondary text (AA, 4.5:1 — WCAG 2.2 SC 1.4.3).
    val lightTextColors: List<String> =
        listOf(STATUS_OK_TEXT_LIGHT, STATUS_UNKNOWN_TEXT_LIGHT, STATUS_DOWN_TEXT_LIGHT, TEXT_SECONDARY_LIGHT)
    val darkTextColors: List<String> =
        listOf(STATUS_OK_TEXT_DARK, STATUS_UNKNOWN_TEXT_DARK, STATUS_DOWN_TEXT_DARK, TEXT_SECONDARY_DARK)

    // The tint at low opacity over the screen ground — the tonal button and
    // "primaryContainer" fill the tint's own text sits on.
    const val TINT_FILL_ALPHA = 0.12
    val tintFillLight: String get() = WcagContrast.blend(TINT_LIGHT, BACKGROUND_LIGHT, TINT_FILL_ALPHA)
    val tintFillDark: String get() = WcagContrast.blend(TINT_DARK, BACKGROUND_DARK, TINT_FILL_ALPHA)

    // WCAG 2.2 SC 1.4.6 (AAA) and SC 1.4.3 (AA) for normal text.
    const val ENHANCED_TEXT_CONTRAST = 7.0
    const val MINIMUM_TEXT_CONTRAST = 4.5
}

object WcagContrast {
    // Relative luminance per WCAG 2.x, sRGB.
    fun luminance(hex: String): Double {
        val (r, g, b) = rgb(hex)
        fun channel(c: Double): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // `alpha` of `fg` over `bg`.
    fun blend(fg: String, bg: String, alpha: Double): String {
        val f = rgb(fg)
        val b = rgb(bg)
        fun mix(x: Double, y: Double): Int = ((alpha * x + (1 - alpha) * y) * 255 + 0.5).toInt()
        return "#%02X%02X%02X".format(mix(f.first, b.first), mix(f.second, b.second), mix(f.third, b.third))
    }

    fun rgb(hex: String): Triple<Double, Double, Double> {
        val value = hex.removePrefix("#").toLong(16)
        return Triple(
            ((value shr 16) and 0xFF) / 255.0,
            ((value shr 8) and 0xFF) / 255.0,
            (value and 0xFF) / 255.0,
        )
    }

    // ARGB with full alpha, for `androidx.compose.ui.graphics.Color(Long)`.
    fun argb(hex: String): Long = 0xFF000000L or hex.removePrefix("#").toLong(16)
}
