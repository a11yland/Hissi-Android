package com.a11yland.hissi.core

// Presentation-agnostic status vocabulary shared across all surfaces so the
// same icon shape and wording represent a status everywhere. Colours stay
// surface-local. Ported from `Shared/ElevatorStatus.swift`.
//
// The icon differs by *shape*, not only colour — this is what makes the status
// legible for colour-blind users and in monochrome contexts.
enum class ElevatorStatus {
    Working, Broken, Unknown;

    companion object {
        fun from(isWorking: Boolean?): ElevatorStatus = when (isWorking) {
            true -> Working
            false -> Broken
            null -> Unknown
        }
    }

    val icon: StatusIcon
        get() = when (this) {
            Working -> StatusIcon.CheckmarkCircle
            Broken -> StatusIcon.WarningTriangle
            Unknown -> StatusIcon.QuestionmarkCircle
        }

    // Full sentence, used for on-screen text and screen readers. German
    // literals double as the localization keys, mirroring the iOS convention;
    // the app layer resolves them against string resources.
    val label: String
        get() = when (this) {
            Working -> "In Betrieb"
            Broken -> "Außer Betrieb"
            Unknown -> "Status unbekannt"
        }

    // Compact form for space-constrained surfaces (widgets).
    val shortLabel: String
        get() = when (this) {
            Working -> "OK"
            Broken -> "Defekt"
            Unknown -> "?"
        }
}
