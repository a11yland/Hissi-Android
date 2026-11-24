package com.a11yland.hissi.core

import java.time.Instant

// Since when a status holds that no current span dates — above all "in
// Betrieb": a working elevator has no active span, the moment it came back is
// the end of the span before. Pure and unit-tested; the spans come from
// TransitApi.fetchLatestSpans. Ported from `Shared/StatusHistory.swift`.
object StatusHistory {
    // The latest-started span decides. Closed with another status than the
    // current one ⇒ its end is the transition (upstream chains spans, often
    // through `unknown`: out_of_service → unknown → in_service). Still open ⇒
    // null: had it agreed with the status, the join would have dated it; as
    // it doesn't, the order of events is unclear. Closed with the same status
    // ⇒ null, the gap after it is unaccounted for. Never a guess.
    fun since(
        isWorking: Boolean?,
        spans: List<AccessibilityCloudClient.StatusSpan>,
        now: Instant = Instant.now(),
    ): Instant? {
        val latest = spans
            .filter { span -> span.start?.let { it <= now } ?: false }
            .maxByOrNull { it.start ?: Instant.MIN }
            ?: return null
        val end = latest.end ?: return null
        return if (end <= now && latest.isWorking != isWorking) end else null
    }

    // Fills `statusSince` where it is still unknown; a known value is never
    // overwritten (the current span's start is the better witness).
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
