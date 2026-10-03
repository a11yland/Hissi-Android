package com.a11yland.hissi.core

// A status change worth interrupting for — ported from the iOS
// LiveStatusAlert (`Shared/LiveStatus.swift`), minus the session: Android's
// disruption alerts monitor permanently via WorkManager instead of a
// user-started Live Activity (docs/android-plan.md), but the transition rules
// are the same, so the same situation alerts the same on both platforms.
// Without a push server this is the app's only way to warn actively, so it
// has to be rare enough to stay credible: only real transitions, never a
// re-announcement of a standing disruption on every evaluation.
sealed interface DisruptionAlert {
    data class Broke(val station: String, val count: Int) : DisruptionAlert
    data class Repaired(val station: String) : DisruptionAlert

    companion object {
        // Compared per elevator id, not by counts: one elevator breaking
        // while another is repaired leaves the counts identical and still
        // deserves an alert.
        //
        // Only elevators the previous evaluation already knew about count —
        // one favorited in between that arrives broken is a fact, not an
        // event the monitoring witnessed. A fall back to unknown (source
        // failure) never alerts.
        fun transition(
            previous: List<MonitoredElevator>?,
            current: List<MonitoredElevator>,
        ): DisruptionAlert? {
            if (previous.isNullOrEmpty()) return null
            val known = previous.map { it.id }.toSet()
            val brokenBefore = previous.filter { it.isWorking == false }.map { it.id }.toSet()

            // Bad news outranks good news, same precedence as the verdict.
            val newlyBroken = current.filter {
                it.isWorking == false && it.id in known && it.id !in brokenBefore
            }
            newlyBroken.firstOrNull()?.let { return Broke(it.stationName, newlyBroken.size) }

            val repaired = current.filter { it.isWorking == true && it.id in brokenBefore }
            repaired.firstOrNull()?.let { return Repaired(it.stationName) }
            return null
        }
    }

    // German literals double as the localization keys, as everywhere in
    // :core — the notification layer maps them onto string resources.
    val title: String
        get() = when (this) {
            is Broke -> "Aufzug außer Betrieb"
            is Repaired -> "Aufzug wieder in Betrieb"
        }

    val body: String
        get() = when (this) {
            is Broke -> if (count > 1) "$station und ${count - 1} weitere" else station
            is Repaired -> station
        }
}
