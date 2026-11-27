package com.a11yland.hissi.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.a11yland.hissi.R
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.Palette
import com.a11yland.hissi.core.StatusIcon
import com.a11yland.hissi.core.TransitNetwork
import com.a11yland.hissi.core.TransitRegion
import com.a11yland.hissi.core.WcagContrast
import java.time.Instant

// UI mapping of the platform-neutral :core vocabulary: shapes onto Material
// icons, labels onto string resources (so they follow the app language), and
// the status colours from the iOS StatusColors (WCAG-checked pairs).

// The effective appearance. The in-app toggle can override the system
// setting, so isSystemInDarkTheme() would pick the wrong half of a WCAG
// colour pair whenever the two diverge — MainActivity provides the resolved
// value for everything colour-picking.
val LocalDarkTheme = staticCompositionLocalOf { false }

fun StatusIcon.vector(): ImageVector = when (this) {
    StatusIcon.CheckmarkCircle -> Icons.Filled.CheckCircle
    StatusIcon.WarningTriangle -> Icons.Filled.Warning
    StatusIcon.QuestionmarkCircle -> Icons.AutoMirrored.Filled.Help
    StatusIcon.Star -> Icons.Filled.Star
}

private fun hex(value: String) = Color(WcagContrast.argb(value))

// The status *text* colour: the palette's darker variants, AA on every ground
// (PaletteTest). Use this for the status word and anything else that is read.
@Composable
fun statusColor(status: ElevatorStatus): Color {
    val dark = LocalDarkTheme.current
    return when (status) {
        ElevatorStatus.Working -> hex(if (dark) Palette.STATUS_OK_TEXT_DARK else Palette.STATUS_OK_TEXT_LIGHT)
        ElevatorStatus.Broken -> hex(if (dark) Palette.STATUS_DOWN_TEXT_DARK else Palette.STATUS_DOWN_TEXT_LIGHT)
        ElevatorStatus.Unknown -> hex(if (dark) Palette.STATUS_UNKNOWN_TEXT_DARK else Palette.STATUS_UNKNOWN_TEXT_LIGHT)
    }
}

// The status *symbol* colour: the brighter mark colours (ping green / amber /
// red) for the icon, whose shape carries the status — the colour only
// reinforces it, so it needs no text contrast.
@Composable
fun statusSymbolColor(status: ElevatorStatus): Color {
    val dark = LocalDarkTheme.current
    return when (status) {
        ElevatorStatus.Working -> hex(if (dark) Palette.STATUS_OK_SYMBOL_DARK else Palette.STATUS_OK_SYMBOL_LIGHT)
        ElevatorStatus.Broken -> hex(if (dark) Palette.STATUS_DOWN_SYMBOL_DARK else Palette.STATUS_DOWN_SYMBOL_LIGHT)
        ElevatorStatus.Unknown -> hex(if (dark) Palette.STATUS_UNKNOWN_SYMBOL_DARK else Palette.STATUS_UNKNOWN_SYMBOL_LIGHT)
    }
}

// The "possibly outdated" warning shares the unknown status' text colour —
// amber means "unsure" throughout the palette.
@Composable
fun staleWarningColor(): Color = statusColor(ElevatorStatus.Unknown)

@Composable
fun statusLabel(status: ElevatorStatus): String = when (status) {
    ElevatorStatus.Working -> stringResource(R.string.status_working)
    ElevatorStatus.Broken -> stringResource(R.string.status_broken)
    ElevatorStatus.Unknown -> stringResource(R.string.status_unknown)
}

// Localized relative time off the :core ages, e.g. "vor 3 Min." / "3 min
// ago" — always relative, never a date. `spoken` writes the short units out
// for TalkBack.
@Composable
fun relativeTimeText(since: Instant, spoken: Boolean = false): String =
    when (val age = MonitoredElevator.relativeAge(since)) {
        null -> stringResource(R.string.relative_just_now)
        is MonitoredElevator.Age.Minutes -> when {
            !spoken -> stringResource(R.string.relative_minutes, age.count)
            age.count == 1 -> stringResource(R.string.relative_one_minute_spoken)
            else -> stringResource(R.string.relative_minutes_spoken, age.count)
        }
        is MonitoredElevator.Age.Hours -> when {
            !spoken -> stringResource(R.string.relative_hours, age.count)
            age.count == 1 -> stringResource(R.string.relative_one_hour_spoken)
            else -> stringResource(R.string.relative_hours_spoken, age.count)
        }
        is MonitoredElevator.Age.Days ->
            if (age.count == 1) stringResource(R.string.relative_one_day)
            else stringResource(R.string.relative_days, age.count)
        is MonitoredElevator.Age.Weeks -> stringResource(R.string.relative_weeks, age.count)
        is MonitoredElevator.Age.Months -> stringResource(R.string.relative_months, age.count)
        is MonitoredElevator.Age.Years -> stringResource(R.string.relative_years, age.count)
    }

// How long a status has held, off the :core ages: "seit 3 Tagen" / "for 3
// days" — always relative. `spoken` writes the short units out for TalkBack.
// Context-based rather than @Composable: the Glance widget formats the same
// line.
fun sinceText(
    context: Context,
    since: Instant,
    now: Instant = Instant.now(),
    spoken: Boolean = false,
): String = when (val age = MonitoredElevator.sinceAge(since, now)) {
    is MonitoredElevator.Age.Minutes -> when {
        !spoken -> context.getString(R.string.since_minutes, age.count)
        age.count == 1 -> context.getString(R.string.since_one_minute_spoken)
        else -> context.getString(R.string.since_minutes_spoken, age.count)
    }
    is MonitoredElevator.Age.Hours -> when {
        !spoken -> context.getString(R.string.since_hours, age.count)
        age.count == 1 -> context.getString(R.string.since_one_hour_spoken)
        else -> context.getString(R.string.since_hours_spoken, age.count)
    }
    is MonitoredElevator.Age.Days ->
        if (age.count == 1) context.getString(R.string.since_one_day)
        else context.getString(R.string.since_days, age.count)
    is MonitoredElevator.Age.Weeks -> context.getString(R.string.since_weeks, age.count)
    is MonitoredElevator.Age.Months -> context.getString(R.string.since_months, age.count)
    is MonitoredElevator.Age.Years -> context.getString(R.string.since_years, age.count)
}

@Composable
fun sinceText(since: Instant, spoken: Boolean = false): String =
    sinceText(LocalContext.current, since, spoken = spoken)

// The status with its duration as one spoken phrase — "Außer Betrieb seit 3
// Tagen"; without a known start just the status. Mirrors iOS
// `ElevatorStatus.sentence`.
fun statusSentence(context: Context, statusLabel: String, since: Instant?, now: Instant = Instant.now()): String =
    since?.let { context.getString(R.string.status_with_since, statusLabel, sinceText(context, it, now, spoken = true)) }
        ?: statusLabel

// Federal state header in the search results; names spelled the same in
// German and English stay literals, like the network names.
@Composable
fun regionLabel(region: TransitRegion): String = when (region) {
    TransitRegion.BadenWuerttemberg -> stringResource(R.string.region_baden_wuerttemberg)
    TransitRegion.Bayern -> stringResource(R.string.region_bayern)
    TransitRegion.Hessen -> stringResource(R.string.region_hessen)
    TransitRegion.MecklenburgVorpommern -> stringResource(R.string.region_mecklenburg_vorpommern)
    TransitRegion.Niedersachsen -> stringResource(R.string.region_niedersachsen)
    TransitRegion.NordrheinWestfalen -> stringResource(R.string.region_nordrhein_westfalen)
    TransitRegion.RheinlandPfalz -> stringResource(R.string.region_rheinland_pfalz)
    TransitRegion.Sachsen -> stringResource(R.string.region_sachsen)
    TransitRegion.SachsenAnhalt -> stringResource(R.string.region_sachsen_anhalt)
    TransitRegion.Thueringen -> stringResource(R.string.region_thueringen)
    else -> region.label
}

@Composable
fun networkLabel(network: TransitNetwork): String = when (network) {
    TransitNetwork.UBahn -> "U-Bahn"
    TransitNetwork.SBahn -> "S-Bahn"
    TransitNetwork.Regional -> stringResource(R.string.network_regional)
    TransitNetwork.Access -> stringResource(R.string.network_access)
}

// Speakable network name for TalkBack; null = access elevators, which carry
// no badge (the visible label already reads fine). Mirrors the iOS
// spokenName.
@Composable
fun spokenNetworkName(network: TransitNetwork): String? = when (network) {
    TransitNetwork.UBahn -> "U-Bahn"
    TransitNetwork.SBahn -> "S-Bahn"
    TransitNetwork.Regional -> stringResource(R.string.network_regional_spoken)
    TransitNetwork.Access -> null
}

// Badge letter and colour for the network chips in station headers; access
// elevators carry no badge.
val TransitNetwork.badge: String?
    get() = when (this) {
        TransitNetwork.UBahn -> "U"
        TransitNetwork.SBahn -> "S"
        TransitNetwork.Regional -> "R"
        TransitNetwork.Access -> null
    }

// White letter on the network's colour — the AAA pairs from :core's Palette
// (PaletteTest keeps them at 7:1), shared with the iOS badges.
val TransitNetwork.badgeColor: Color
    get() = when (this) {
        TransitNetwork.UBahn -> Color(WcagContrast.argb(Palette.U_BAHN_BADGE))
        TransitNetwork.SBahn -> Color(WcagContrast.argb(Palette.S_BAHN_BADGE))
        TransitNetwork.Regional -> Color(WcagContrast.argb(Palette.REGIONAL_BADGE))
        TransitNetwork.Access -> Color(WcagContrast.argb(Palette.ACCESS_BADGE))
    }
