package com.a11yland.hissi.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from HissiTests/Tests/PaletteTests.swift: the tint stays at WCAG
// AAA and the status words at AA as text wherever they are drawn, and the
// badge letters at AAA on their badge.
class PaletteTest {
    @Test
    fun tintIsEnhancedContrastTextOnEveryGround() {
        for (ground in Palette.lightGrounds) {
            assertTrue(
                WcagContrast.ratio(Palette.TINT_LIGHT, ground) >= Palette.ENHANCED_TEXT_CONTRAST,
                "light tint on $ground",
            )
        }
        for (ground in Palette.darkGrounds) {
            assertTrue(
                WcagContrast.ratio(Palette.TINT_DARK, ground) >= Palette.ENHANCED_TEXT_CONTRAST,
                "dark tint on $ground",
            )
        }
    }

    // The tonal fill (tint at low opacity over the ground) is the tightest
    // pairing the tint's own text sits on; keep it near AAA like iOS does.
    @Test
    fun tintReadsOnItsOwnTonalFill() {
        assertTrue(WcagContrast.ratio(Palette.TINT_LIGHT, Palette.tintFillLight) >= 6.5, "light tonal fill")
        assertTrue(WcagContrast.ratio(Palette.TINT_DARK, Palette.tintFillDark) >= 6.5, "dark tonal fill")
    }

    // Filled buttons carry the palette's own label colour: cream on the light
    // tint, deep purple on the dark one — white on the pale dark tint would
    // be 1.7:1, which is why `onPrimary` is not simply white/black.
    @Test
    fun buttonLabelsReadOnTheTint() {
        assertTrue(WcagContrast.ratio(Palette.ON_TINT_LIGHT, Palette.TINT_LIGHT) >= Palette.ENHANCED_TEXT_CONTRAST)
        assertTrue(WcagContrast.ratio(Palette.ON_TINT_DARK, Palette.TINT_DARK) >= Palette.ENHANCED_TEXT_CONTRAST)
        assertTrue(
            WcagContrast.ratio("#FFFFFF", Palette.TINT_DARK) < 4.5,
            "if this passes, a white label would do",
        )
    }

    // The status words are the design's darker text variants — AA on every
    // ground, so the colour can reinforce the shape without undercutting it.
    @Test
    fun statusTextReadsOnEveryGround() {
        for (color in Palette.lightTextColors) {
            for (ground in Palette.lightGrounds) {
                assertTrue(
                    WcagContrast.ratio(color, ground) >= Palette.MINIMUM_TEXT_CONTRAST,
                    "$color light on $ground",
                )
            }
        }
        for (color in Palette.darkTextColors) {
            for (ground in Palette.darkGrounds) {
                assertTrue(
                    WcagContrast.ratio(color, ground) >= Palette.MINIMUM_TEXT_CONTRAST,
                    "$color dark on $ground",
                )
            }
        }
    }

    @Test
    fun badgesCarryWhiteTextAtEnhancedContrast() {
        for (badge in Palette.badges) {
            assertTrue(WcagContrast.ratio("#FFFFFF", badge) >= Palette.ENHANCED_TEXT_CONTRAST, badge)
        }
    }

    @Test
    fun contrastMathMatchesTheReferenceValues() {
        assertTrue(abs(WcagContrast.ratio("#000000", "#FFFFFF") - 21) < 0.01)
        assertTrue(abs(WcagContrast.ratio("#007AFF", "#FFFFFF") - 4.0) < 0.05)
        assertEquals("#808080", WcagContrast.blend("#000000", "#FFFFFF", 0.5))
        assertEquals(0xFF503C74L, WcagContrast.argb("#503C74"))
    }
}
