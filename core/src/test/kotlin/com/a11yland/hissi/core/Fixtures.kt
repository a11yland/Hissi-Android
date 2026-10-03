package com.a11yland.hissi.core

import java.io.File
import java.time.Instant

// The same JSON fixtures as the Swift package (HissiTests/Tests/Fixtures/),
// wired in via a system property from build.gradle.kts — behavioral drift
// between the two ports surfaces in CI (issue #8).
fun fixtureData(name: String): String {
    val directory = requireNotNull(System.getProperty("hissi.fixtures.dir")) {
        "hissi.fixtures.dir system property missing — run via Gradle"
    }
    return File(directory, "$name.json").readText()
}

// A fixed "now" between the fixtures' span starts (June 2026) and their far
// future — keeps the span selection deterministic regardless of test date.
val fixtureNow: Instant = Instant.parse("2026-07-01T12:00:00Z")

fun joinedFixtures(): List<AccessibilityCloudClient.Equipment> = AccessibilityCloudClient.join(
    elevators = AccessibilityCloudClient.parseElevators(fixtureData("elevators")).items,
    statusSpans = AccessibilityCloudClient.parseStatusSpans(fixtureData("status_spans")).items,
    stopPlaces = AccessibilityCloudClient.parseStopPlaces(fixtureData("stop_places")).items,
    now = fixtureNow,
)
