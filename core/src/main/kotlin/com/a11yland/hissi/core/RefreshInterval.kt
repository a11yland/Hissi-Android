package com.a11yland.hissi.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Refresh cadence shared by the app, the widget and the disruption-alert worker.
 *
 * Ported from `Shared/RefreshInterval.swift`. The iOS live-status tightening
 * (10-minute polls while a Live Activity runs) has no Android counterpart:
 * disruption alerts run permanently on the regular cadence instead of a
 * user-started session (see docs/android-plan.md).
 */
object RefreshInterval {
    const val MINUTES: Int = 30
    val interval: Duration = MINUTES.minutes

    // A widget reload landing right after something else wrote the cache
    // (the app's refresh, a worker run) answers from it instead of fetching
    // again — otherwise every reload would repeat the same request.
    val cacheReuse: Duration = 90.seconds
}
