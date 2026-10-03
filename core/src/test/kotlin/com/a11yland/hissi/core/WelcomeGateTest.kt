package com.a11yland.hissi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported 1:1 from HissiTests/Tests/WelcomeGateTests.swift: welcome once per
// install; afterwards "Was ist neu" only for releases with curated notes,
// once per version.
class WelcomeGateTest {
    @Test
    fun freshInstallGetsTheWelcome() {
        val sheet = WelcomeGate.sheet(
            hasSeenWelcome = false,
            shownWhatsNewVersion = null,
            currentVersion = "3.0",
            curatedVersions = setOf("3.0"),
        )
        assertEquals(WelcomeGate.Sheet.Welcome, sheet)
    }

    // Existing installs from before the welcome existed also see it once —
    // it doubles as that release's what's-new.
    @Test
    fun welcomeWinsEvenWhenNotesExist() {
        val sheet = WelcomeGate.sheet(
            hasSeenWelcome = false,
            shownWhatsNewVersion = "2.1",
            currentVersion = "3.0",
            curatedVersions = setOf("3.0"),
        )
        assertEquals(WelcomeGate.Sheet.Welcome, sheet)
    }

    @Test
    fun curatedUpdateShowsWhatsNewOnce() {
        val first = WelcomeGate.sheet(
            hasSeenWelcome = true,
            shownWhatsNewVersion = "2.1",
            currentVersion = "3.0",
            curatedVersions = setOf("3.0"),
        )
        assertEquals(WelcomeGate.Sheet.WhatsNew(version = "3.0"), first)

        val again = WelcomeGate.sheet(
            hasSeenWelcome = true,
            shownWhatsNewVersion = "3.0",
            currentVersion = "3.0",
            curatedVersions = setOf("3.0"),
        )
        assertNull(again)
    }

    // Bugfix releases without curated notes stay silent.
    @Test
    fun uncuratedVersionsShowNothing() {
        val sheet = WelcomeGate.sheet(
            hasSeenWelcome = true,
            shownWhatsNewVersion = "3.0",
            currentVersion = "3.0.1",
            curatedVersions = setOf("3.0"),
        )
        assertNull(sheet)
    }
}
