package com.a11yland.hissi.core

// Which system an elevator id belongs to. Ids are opaque strings everywhere;
// their shape names the source: numeric = transit.accessibility.cloud,
// "vrr:<resourceId>" = the VRR infrastructure API (opendata-oepnv.de),
// anything else = an id scheme from before the transit.accessibility.cloud
// migration. Replaces the former "non-numeric means legacy" checks. Pure,
// unit-tested. Ported from `Shared/ElevatorSource.swift`.
enum class ElevatorSource {
    AccessibilityCloud, Vrr, Legacy;

    // An id a status source answers for — as opposed to a legacy id, which
    // only resolves through the seed's `legacyIds` bridge, if at all.
    val isLive: Boolean get() = this != Legacy

    companion object {
        const val VRR_PREFIX = "vrr:"

        fun of(id: String): ElevatorSource = when {
            id.startsWith(VRR_PREFIX) && id.length > VRR_PREFIX.length -> Vrr
            id.toLongOrNull() != null -> AccessibilityCloud
            else -> Legacy
        }

        // "de:05112:16580:31" → "vrr:de:05112:16580:31".
        fun vrrId(resourceId: String): String = VRR_PREFIX + resourceId

        // Whether an elevator of this id is part of the app while the VRR
        // feature toggle is on or off (iOS `FeatureToggle.includes`). The
        // toggle itself lives in :app (BuildConfig.FEATURE_VRR); the one
        // choke point is the seed.
        fun includes(id: String, vrrEnabled: Boolean): Boolean = of(id) != Vrr || vrrEnabled
    }
}
