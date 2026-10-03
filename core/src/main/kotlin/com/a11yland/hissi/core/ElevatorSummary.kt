package com.a11yland.hissi.core

// The single aggregate verdict every glanceable surface gives: "is my route
// okay?", reduced from all favorites before anything is rendered. Ported from
// `Shared/ElevatorSummary.swift` — the widget and the disruption alerts speak
// this vocabulary, so the same situation reads the same on every platform.
//
// Pure and platform-free so it stays unit-testable; colours and layout stay
// surface-local. Status is carried by the icon's *shape* and by words, never
// by colour alone.
data class ElevatorSummary(
    val verdict: Verdict,
    val brokenCount: Int,
    val unknownCount: Int,
    val total: Int,
    // Stations behind a non-clear verdict, broken before unknown and
    // deduplicated in input order — surfaces with room show one name.
    val affectedStations: List<String>,
) {
    // Precedence order: broken outranks unknown outranks all-clear, and "no
    // favorites" is its own case — with nothing to monitor, "Alle in Betrieb"
    // would be a false all-clear. The names are a wire format (persisted by
    // the disruption alerts) and must stay stable.
    enum class Verdict {
        NoFavorites,
        Broken,
        Unknown,
        AllWorking,
        // iOS-watch-only on the port point; kept so the vocabulary stays
        // identical across platforms (it can never fall out of an elevator
        // list here either).
        AwaitingSync,
    }

    companion object {
        fun of(elevators: List<MonitoredElevator>): ElevatorSummary {
            val broken = elevators.filter { it.isWorking == false }
            val unknown = elevators.filter { it.isWorking == null }
            val verdict = when {
                elevators.isEmpty() -> Verdict.NoFavorites
                broken.isNotEmpty() -> Verdict.Broken
                unknown.isNotEmpty() -> Verdict.Unknown
                else -> Verdict.AllWorking
            }
            val seen = mutableSetOf<String>()
            val affected = (broken + unknown)
                .map { it.stationName }
                .filter { it.isNotEmpty() && seen.add(it) }
            return ElevatorSummary(
                verdict = verdict,
                brokenCount = broken.size,
                unknownCount = unknown.size,
                total = elevators.size,
                affectedStations = affected,
            )
        }
    }

    val icon: StatusIcon
        get() = when (verdict) {
            Verdict.NoFavorites -> StatusIcon.Star
            Verdict.Broken -> ElevatorStatus.Broken.icon
            Verdict.Unknown, Verdict.AwaitingSync -> ElevatorStatus.Unknown.icon
            Verdict.AllWorking -> ElevatorStatus.Working.icon
        }

    // Full sentence — widget list header and screen readers. German literals
    // double as the localization keys, as on iOS.
    val label: String
        get() = when (verdict) {
            Verdict.NoFavorites -> "Keine Favoriten"
            Verdict.Broken -> "$brokenCount außer Betrieb"
            Verdict.Unknown -> "$unknownCount unbekannt"
            Verdict.AllWorking -> "Alle in Betrieb"
            Verdict.AwaitingSync -> "Status unbekannt"
        }

    // Compact form for the space-constrained surfaces.
    val shortLabel: String
        get() = when (verdict) {
            Verdict.NoFavorites -> "Keine Favoriten"
            Verdict.Broken -> "$brokenCount defekt"
            Verdict.Unknown, Verdict.AwaitingSync -> "?"
            Verdict.AllWorking -> "OK"
        }

    // Surfaces that are otherwise anonymous have to carry the app name
    // themselves.
    val identifiedLabel: String get() = "Hissi: $label"
    val identifiedShortLabel: String get() = "Hissi: $shortLabel"

    // The one extra line surfaces with room show: which station is actually
    // affected.
    val detailLine: String? get() = affectedStations.firstOrNull()

    // Ranking on glanceable surfaces. A broken elevator is the whole point of
    // the app and should surface on its own; unknown is worth a nudge; an
    // all-clear stays at baseline.
    val relevanceScore: Float
        get() = when (verdict) {
            Verdict.Broken -> 100f
            Verdict.Unknown -> 25f
            Verdict.AllWorking, Verdict.NoFavorites, Verdict.AwaitingSync -> 0f
        }
}
