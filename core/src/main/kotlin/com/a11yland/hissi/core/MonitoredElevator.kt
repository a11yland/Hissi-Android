package com.a11yland.hissi.core

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

// Ported from `Shared/MonitoredElevator.swift`. @Serializable with default
// values keeps decoding tolerant of caches written before newer fields existed
// (the Swift port point does the same by hand in `init(from:)`).
@Serializable
data class MonitoredElevator(
    val id: String,
    val stationId: String,
    val elevatorId: String,
    // Position of this elevator within its station page (lift order) —
    // brokenlifts seed records refresh by page position.
    val elevatorIndex: Int = 0,
    val stationName: String,
    val elevatorDescription: String,
    val isWorking: Boolean? = null,
    // When the app last fetched this elevator (our poll time), epoch millis.
    val lastCheckedEpochMillis: Long? = null,
    // When the status was last updated at the source. Distinct from
    // lastChecked: a fresh poll can still return stale source data.
    val lastUpdatedEpochMillis: Long? = null,
    // When the current status began ("seit …") — a real transition time or
    // null, never a guess (AccessibilityCloudClient.statusSince, in `join`).
    val statusSinceEpochMillis: Long? = null,
    // accessibility.cloud source/organization for this record, e.g.
    // "BVG Elevators (2025)" / "BVG Berliner Verkehrsbetriebe AöR".
    val sourceName: String = "",
    val organizationName: String = "",
    // Human-readable disruption reason from the source. Null when no
    // disruption is on record.
    val stateExplanation: String? = null,
    // Station coordinates, for the map snippet in the detail views.
    val latitude: Double? = null,
    val longitude: Double? = null,
    // DB FaSta equipment number (from the seed catalog) — the bridge between
    // seed records and their live counterparts.
    val fastaEquipmentNumber: Int? = null,
    // When the user favorited this elevator — what the default "Zuletzt
    // hinzugefügt" order sorts by. Null on favorites stored before the field
    // existed; they sort after the timestamped ones (FavoritesOrdering).
    val addedAtEpochMillis: Long? = null,
) {
    val lastChecked: Instant? get() = lastCheckedEpochMillis?.let(Instant::ofEpochMilli)
    val lastUpdated: Instant? get() = lastUpdatedEpochMillis?.let(Instant::ofEpochMilli)
    val statusSince: Instant? get() = statusSinceEpochMillis?.let(Instant::ofEpochMilli)

    // Individual records can silently decouple from their disruption feed
    // (observed with VBB S-Bahn: frozen since 2024 while sibling records
    // update). Beyond this age the status is flagged as possibly outdated —
    // a warning, not a downgrade to unknown, since an old lastUpdate can
    // also just mean a long disruption-free stretch.
    val isDataStale: Boolean
        get() {
            val updated = lastUpdated ?: return false
            return Duration.between(updated, Instant.now()) > Duration.ofDays(STALE_AFTER_DAYS.toLong())
        }

    // Poll time — "when did we last check". Self-contained for standalone use.
    val lastCheckedLabel: String
        get() = lastChecked?.let { "Geprüft ${relativeTime(since = it)}" } ?: "Noch nie geprüft"

    // Source freshness — "how old is the status at the source".
    val lastUpdatedLabel: String
        get() = lastUpdated?.let { "Stand ${relativeTime(since = it)}" } ?: "Stand unbekannt"

    // Since when the current status holds, as far as it is shown: only for
    // a status that is not "in Betrieb". How long an outage has lasted is
    // what matters; a working lift needs no duration. Null also while the
    // source doesn't say when the status began.
    val shownStatusSince: Instant? get() = if (isWorking == true) null else statusSince

    // An age in the coarsest unit that still says something. Times are
    // always shown relative — never as a date: days for two weeks, then
    // weeks, months and years. The UI layer localizes each case via string
    // resources; the German labels below are the test vocabulary.
    sealed interface Age {
        data class Minutes(val count: Int) : Age
        data class Hours(val count: Int) : Age
        data class Days(val count: Int) : Age
        data class Weeks(val count: Int) : Age
        data class Months(val count: Int) : Age
        data class Years(val count: Int) : Age

        companion object {
            fun of(minutes: Int): Age {
                val days = minutes / 1440
                return when {
                    minutes < 60 -> Minutes(minutes)
                    minutes < 1440 -> Hours(minutes / 60)
                    days < 14 -> Days(days)
                    days < 60 -> Weeks(days / 7)
                    days < 730 -> Months(days / 30)
                    else -> Years(days / 365)
                }
            }
        }
    }

    companion object {
        const val STALE_AFTER_DAYS: Int = 14

        // How long ago a moment was; null = under a minute ("gerade eben").
        fun relativeAge(since: Instant, now: Instant = Instant.now()): Age? {
            val minutes = Duration.between(since, now).toMinutes().toInt()
            return if (minutes < 1) null else Age.of(minutes)
        }

        // How long a status has held. Under a minute — or a start slightly
        // ahead of this device's clock — counts as one minute: "seit 0 Min."
        // says nothing.
        fun sinceAge(since: Instant, now: Instant = Instant.now()): Age =
            Age.of(maxOf(1, Duration.between(since, now).toMinutes().toInt()))

        // "gerade eben" / "vor 3 Min." / "vor 2 Std." / "vor 3 Tagen" / "vor 5
        // Wochen" / "vor 4 Monaten" / "vor 2 Jahren"; `spoken` writes the
        // short units out for TalkBack.
        fun relativeTime(since: Instant, now: Instant = Instant.now(), spoken: Boolean = false): String =
            when (val age = relativeAge(since, now)) {
                null -> "gerade eben"
                is Age.Minutes -> when {
                    !spoken -> "vor ${age.count} Min."
                    age.count == 1 -> "vor 1 Minute"
                    else -> "vor ${age.count} Minuten"
                }
                is Age.Hours -> when {
                    !spoken -> "vor ${age.count} Std."
                    age.count == 1 -> "vor 1 Stunde"
                    else -> "vor ${age.count} Stunden"
                }
                is Age.Days -> if (age.count == 1) "vor 1 Tag" else "vor ${age.count} Tagen"
                is Age.Weeks -> "vor ${age.count} Wochen"
                is Age.Months -> "vor ${age.count} Monaten"
                is Age.Years -> "vor ${age.count} Jahren"
            }

        // "seit 5 Min." / "seit 3 Std." / "seit 1 Tag" / "seit 3 Tagen" /
        // "seit 5 Wochen" / "seit 4 Monaten" / "seit 2 Jahren".
        fun sinceLabel(since: Instant, now: Instant = Instant.now(), spoken: Boolean = false): String =
            when (val age = sinceAge(since, now)) {
                is Age.Minutes -> when {
                    !spoken -> "seit ${age.count} Min."
                    age.count == 1 -> "seit 1 Minute"
                    else -> "seit ${age.count} Minuten"
                }
                is Age.Hours -> when {
                    !spoken -> "seit ${age.count} Std."
                    age.count == 1 -> "seit 1 Stunde"
                    else -> "seit ${age.count} Stunden"
                }
                is Age.Days -> if (age.count == 1) "seit 1 Tag" else "seit ${age.count} Tagen"
                is Age.Weeks -> "seit ${age.count} Wochen"
                is Age.Months -> "seit ${age.count} Monaten"
                is Age.Years -> "seit ${age.count} Jahren"
            }
    }
}
