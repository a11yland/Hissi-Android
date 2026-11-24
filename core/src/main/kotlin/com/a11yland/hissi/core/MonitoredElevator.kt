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
    // When the current status began ("seit …") — a real transition time or
    // null, never a guess (AccessibilityCloudClient.statusSince, StatusHistory).
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

    // The bucketing behind the "seit …" label, localized by the UI layer
    // like RelativeTimeBucket.
    sealed interface SinceBucket {
        data class Minutes(val minutes: Int) : SinceBucket
        data class Hours(val hours: Int) : SinceBucket
        data class Days(val days: Int) : SinceBucket
        data class OnDate(val date: Instant, val withYear: Boolean) : SinceBucket
    }

    companion object {
        const val STALE_AFTER_DAYS: Int = 14

        // How long a status has held. A day count says how serious an outage
        // is — for two weeks; past that (the stale threshold) a date says
        // more than "seit 40 Tagen", with the year when it isn't this one.
        // Under a minute — or a start slightly ahead of this device's clock —
        // counts as one minute: "seit 0 Min." says nothing.
        fun sinceBucket(
            since: Instant,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): SinceBucket {
            val minutes = maxOf(1, Duration.between(since, now).toMinutes().toInt())
            return when {
                minutes < 60 -> SinceBucket.Minutes(minutes)
                minutes < 1440 -> SinceBucket.Hours(minutes / 60)
                minutes < STALE_AFTER_DAYS * 1440 -> SinceBucket.Days(minutes / 1440)
                else -> SinceBucket.OnDate(
                    since,
                    withYear = since.atZone(zone).year != now.atZone(zone).year,
                )
            }
        }

        // "seit 5 Min." / "seit 3 Std." / "seit 1 Tag" / "seit 3 Tagen" /
        // "seit dem 12. November"; `spoken` writes the units out for
        // TalkBack. The German test vocabulary, as with relativeTime.
        fun sinceLabel(
            since: Instant,
            now: Instant = Instant.now(),
            spoken: Boolean = false,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = when (val bucket = sinceBucket(since, now, zone)) {
            is SinceBucket.Minutes -> when {
                !spoken -> "seit ${bucket.minutes} Min."
                bucket.minutes == 1 -> "seit 1 Minute"
                else -> "seit ${bucket.minutes} Minuten"
            }
            is SinceBucket.Hours -> when {
                !spoken -> "seit ${bucket.hours} Std."
                bucket.hours == 1 -> "seit 1 Stunde"
                else -> "seit ${bucket.hours} Stunden"
            }
            is SinceBucket.Days -> if (bucket.days == 1) "seit 1 Tag" else "seit ${bucket.days} Tagen"
            is SinceBucket.OnDate -> {
                val pattern = if (bucket.withYear) "d. MMMM yyyy" else "d. MMMM"
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.GERMAN).withZone(zone)
                "seit dem ${formatter.format(bucket.date)}"
            }
        }

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
