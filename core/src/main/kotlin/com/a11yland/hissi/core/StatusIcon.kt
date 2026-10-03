package com.a11yland.hissi.core

// Platform-neutral icon vocabulary. The iOS port point (`symbolName`) carries
// SF Symbol names, which are dead strings on Android — the shapes are what the
// a11y contract requires (status legible without colour), so the port keeps
// the shape vocabulary and lets the UI map it onto Material icons.
enum class StatusIcon {
    CheckmarkCircle,
    WarningTriangle,
    QuestionmarkCircle,
    Star,
}
