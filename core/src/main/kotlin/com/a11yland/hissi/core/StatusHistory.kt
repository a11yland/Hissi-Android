package com.a11yland.hissi.core

import java.time.Instant

// Since when a disruption holds that the status request itself doesn't date.
// transit.accessibility.cloud serves no current span with the elevator
// (`current_status_span` comes back empty), so the targeted favorites
// request knows that a lift is out of service, not since when. The spans
// themselves say it: the one running now — started, not yet ended (upstream
// gives a running span a planned end a month ahead) — began when the status
// did. Pure and unit-tested; the spans come from TransitApi.fetchLatestSpans.
// Ported from `Shared/StatusHistory.swift`.
//
// Only disruptions are dated ("außer Betrieb" / "unbekannt"): how long a
// lift has been working is not shown anywhere, so it is not asked for.
object StatusHistory {
    // The latest-started span decides, if it runs now and agrees with the
    // status it is quoted for: its start. A span that has ended, or one of
    // another status (the elevator moved on and upstream hasn't caught up),
    // dates nothing — never a guess. A working elevator is never dated.
    fun since(
        isWorking: Boolean?,
        spans: List<AccessibilityCloudClient.StatusSpan>,
        now: Instant = Instant.now(),
    ): Instant? {
        if (isWorking == true) return null
        val latest = spans
            .filter { span -> span.start?.let { it <= now } ?: false }
            .maxByOrNull { it.start ?: Instant.MIN }
            ?: return null
        val runsNow = latest.end?.let { it > now } ?: true
        return if (runsNow && latest.isWorking == isWorking) latest.start else null
    }

    // Fills `statusSince` where it is still unknown; a known value is never
    // overwritten.
    fun applied(
        elevators: List<MonitoredElevator>,
        spans: List<AccessibilityCloudClient.StatusSpan>,
        now: Instant = Instant.now(),
    ): List<MonitoredElevator> {
        val spansByElevator = spans
            .flatMap { span -> span.elevatorIds.map { it to span } }
            .groupBy({ it.first }, { it.second })
        return elevators.map { elevator ->
            val own = spansByElevator[elevator.id]
            if (elevator.statusSinceEpochMillis != null || own == null) {
                elevator
            } else {
                elevator.copy(statusSinceEpochMillis = since(elevator.isWorking, own, now)?.toEpochMilli())
            }
        }
    }
}
