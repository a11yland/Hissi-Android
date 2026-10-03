package com.a11yland.hissi.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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

    // The bucketing behind the relative-time label, exposed so the UI layer
    // can localize each bucket via string resources while the German label
    // below stays the canonical vocabulary (and the fixture-tested one).
    sealed interface RelativeTimeBucket {
        data object JustNow : RelativeTimeBucket
        data class Minutes(val minutes: Int) : RelativeTimeBucket
        data class Hours(val hours: Int) : RelativeTimeBucket
        data class Days(val days: Int) : RelativeTimeBucket
        data class OnDate(val date: Instant) : RelativeTimeBucket
    }

    companion object {
        const val STALE_AFTER_DAYS: Int = 14

        // Beyond a week an absolute date — some sources go stale for months
        // and huge relative values are meaningless.
        fun relativeTimeBucket(since: Instant, now: Instant = Instant.now()): RelativeTimeBucket {
            val minutes = Duration.between(since, now).toMinutes().toInt()
            return when {
                minutes < 1 -> RelativeTimeBucket.JustNow
                minutes < 60 -> RelativeTimeBucket.Minutes(minutes)
                minutes < 1440 -> RelativeTimeBucket.Hours(minutes / 60)
                minutes < 7 * 1440 -> RelativeTimeBucket.Days(minutes / 1440)
                else -> RelativeTimeBucket.OnDate(since)
            }
        }

        // Localized relative time, e.g. "gerade eben" / "vor 3 Min." /
        // "vor 2 Std." / "vor 3 Tagen" / "am 12.12.2024". German literals
        // double as the localization keys, as on iOS.
        fun relativeTime(since: Instant, now: Instant = Instant.now()): String =
            when (val bucket = relativeTimeBucket(since, now)) {
                is RelativeTimeBucket.JustNow -> "gerade eben"
                is RelativeTimeBucket.Minutes -> "vor ${bucket.minutes} Min."
                is RelativeTimeBucket.Hours -> "vor ${bucket.hours} Std."
                is RelativeTimeBucket.Days ->
                    if (bucket.days == 1) "vor 1 Tag" else "vor ${bucket.days} Tagen"
                is RelativeTimeBucket.OnDate -> {
                    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                        .withLocale(Locale.getDefault())
                        .withZone(ZoneId.systemDefault())
                    "am ${formatter.format(bucket.date)}"
                }
            }
    }
}
