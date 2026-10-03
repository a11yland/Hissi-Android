package com.a11yland.hissi.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.a11yland.hissi.HissiApplication
import com.a11yland.hissi.MainActivity
import com.a11yland.hissi.R
import com.a11yland.hissi.core.ElevatorRanking
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.ElevatorSummary
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.Palette
import com.a11yland.hissi.core.WcagContrast
import com.a11yland.hissi.core.StatusIcon
import com.a11yland.hissi.data.FavoritesRefresher

class HissiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HissiWidget()
}

// The Android counterpart of the iOS widget pair, collapsed into one
// responsive widget: iOS splits systemLarge into a second widget *kind*
// because its gallery lists kinds, not sizes — Android's picker offers one
// resizable widget, so the size alone decides what is shown. On the
// ElevatorSummary vocabulary like every glanceable surface: explicit
// no-favorites state (never a false all-clear), aggregate verdict when small,
// broken-first row list with more room.
//
// Data comes straight from the favorites store — same process as the app, so
// the persisted records double as the status cache (FavoritesRepository): a
// reload right after the app's refresh renders for free, a stale store gets
// one live fetch, and a failed fetch falls back to the last known statuses.
class HissiWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val TALL = DpSize(110.dp, 250.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 250.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, TALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val elevators = loadElevators(context)
        provideContent {
            GlanceTheme {
                WidgetContent(elevators)
            }
        }
    }

    private suspend fun loadElevators(context: Context): List<MonitoredElevator> {
        val container = (context.applicationContext as HissiApplication).container
        val stored = container.favorites.load()
        // Written within the reuse window (the app's refresh is what
        // triggered this reload) the store answers without a fetch.
        if (stored.isEmpty() || FavoritesRefresher.freshEnough(stored)) return stored
        return runCatching { FavoritesRefresher.refreshAll(container).elevators }
            .getOrDefault(stored)
    }
}

// Light/dark pairs from the shared status palette (StatusUi) — the widget
// renders in the system theme, not the app's session-only override.
private val statusGreen =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_OK_TEXT_LIGHT), night = palette(Palette.STATUS_OK_TEXT_DARK))
private val statusRed =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_DOWN_TEXT_LIGHT), night = palette(Palette.STATUS_DOWN_TEXT_DARK))

@Composable
private fun WidgetContent(elevators: List<MonitoredElevator>) {
    val size = LocalSize.current
    val summary = ElevatorSummary.of(elevators)
    // The whole widget opens the app — there is no deeper target to offer.
    var root = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(widgetGround)
        .clickable(actionStartActivity<MainActivity>())
        .padding(12.dp)
    // The system widget radius exists from S on; below, launchers show
    // square widgets anyway.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        root = root.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    }
    when {
        summary.verdict == ElevatorSummary.Verdict.NoFavorites -> NoFavorites(root)
        size.height >= 250.dp && size.width >= 250.dp ->
            ElevatorList(elevators, summary, maxRows = 8, showsHeader = true, modifier = root)
        // A tall-but-narrow shape (2xN) still fits a list — just not the
        // header line next to the rows' own icons.
        size.height >= 250.dp ->
            ElevatorList(elevators, summary, maxRows = 6, showsHeader = false, modifier = root)
        size.width >= 250.dp ->
            ElevatorList(elevators, summary, maxRows = 4, showsHeader = false, modifier = root)
        else -> Verdict(summary, root)
    }
}

// Without favorites there is nothing to monitor — "Alle in Betrieb" here
// would be a false all-clear.
@Composable
private fun NoFavorites(modifier: GlanceModifier) {
    val context = LocalContext.current
    val label = context.getString(R.string.no_favorites_title)
    Column(
        modifier = modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_star),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            label,
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

// The small tile: the aggregate verdict as icon, count and word — the answer
// to "is my route okay?" at a glance.
@Composable
private fun Verdict(summary: ElevatorSummary, modifier: GlanceModifier) {
    val context = LocalContext.current
    val color = summaryColor(summary)
    val big: String
    val caption: String
    when (summary.verdict) {
        ElevatorSummary.Verdict.Broken -> {
            big = "${summary.brokenCount}"
            caption = context.getString(R.string.widget_out_of_service)
        }
        ElevatorSummary.Verdict.Unknown, ElevatorSummary.Verdict.AwaitingSync -> {
            big = "${summary.unknownCount}"
            caption = context.getString(R.string.widget_unknown)
        }
        else -> {
            big = context.getString(R.string.widget_all)
            caption = context.getString(R.string.widget_in_service)
        }
    }
    Column(
        // One element, one sentence — instead of icon/number/caption as three
        // TalkBack stops.
        modifier = modifier.semantics { contentDescription = summaryLabel(context, summary) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(summary.icon.drawable()),
            contentDescription = null,
            modifier = GlanceModifier.size(22.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Text(
            big,
            style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color),
        )
        Text(
            caption,
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

// The row list: same rows at every size, different cap. The tallest size is
// the one that answers "show me everything", so it also gets the aggregate
// verdict as a header — the medium row has no room to spare for it.
@Composable
private fun ElevatorList(
    elevators: List<MonitoredElevator>,
    summary: ElevatorSummary,
    maxRows: Int,
    showsHeader: Boolean,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    // Broken elevators must survive the cap — the shared urgency order.
    val ranked = ElevatorRanking.byUrgency(elevators)
    Column(modifier = modifier) {
        if (showsHeader) {
            val label = summaryLabel(context, summary)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.padding(bottom = 4.dp)
                    .semantics { contentDescription = label },
            ) {
                Image(
                    provider = ImageProvider(summary.icon.drawable()),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(summaryColor(summary)),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    label,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = summaryColor(summary),
                    ),
                    maxLines = 1,
                )
            }
        }
        ranked.take(maxRows).forEach { elevator ->
            ElevatorRow(elevator)
        }
        if (elevators.size > maxRows) {
            Text(
                context.getString(R.string.widget_more, elevators.size - maxRows),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun ElevatorRow(elevator: MonitoredElevator) {
    val context = LocalContext.current
    val status = ElevatorStatus.from(elevator.isWorking)
    val color = statusColor(elevator.isWorking)
    // One row, one sentence for TalkBack.
    val spoken = listOf(elevator.stationName, elevator.elevatorDescription, statusLabel(context, status))
        .filter { it.isNotEmpty() }
        .joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics { contentDescription = spoken },
    ) {
        Image(
            provider = ImageProvider(status.icon.drawable()),
            contentDescription = null,
            modifier = GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                elevator.stationName,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            if (elevator.elevatorDescription.isNotEmpty()) {
                Text(
                    elevator.elevatorDescription,
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            shortStatusLabel(context, status),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color),
        )
    }
}

// :core's German labels are the test vocabulary — the rendered strings come
// from resources so they follow the system language (per-app locale included).
private fun summaryLabel(context: Context, summary: ElevatorSummary): String =
    when (summary.verdict) {
        ElevatorSummary.Verdict.NoFavorites -> context.getString(R.string.no_favorites_title)
        ElevatorSummary.Verdict.Broken ->
            context.getString(R.string.summary_broken, summary.brokenCount)
        ElevatorSummary.Verdict.Unknown ->
            context.getString(R.string.summary_unknown, summary.unknownCount)
        ElevatorSummary.Verdict.AllWorking -> context.getString(R.string.all_working)
        ElevatorSummary.Verdict.AwaitingSync -> context.getString(R.string.status_unknown)
    }

private fun statusLabel(context: Context, status: ElevatorStatus): String = when (status) {
    ElevatorStatus.Working -> context.getString(R.string.status_working)
    ElevatorStatus.Broken -> context.getString(R.string.status_broken)
    ElevatorStatus.Unknown -> context.getString(R.string.status_unknown)
}

private fun shortStatusLabel(context: Context, status: ElevatorStatus): String = when (status) {
    ElevatorStatus.Working -> context.getString(R.string.status_short_working)
    ElevatorStatus.Broken -> context.getString(R.string.status_short_broken)
    ElevatorStatus.Unknown -> context.getString(R.string.status_short_unknown)
}

@Composable
private fun summaryColor(summary: ElevatorSummary): ColorProvider = when (summary.verdict) {
    ElevatorSummary.Verdict.AllWorking -> statusGreen
    ElevatorSummary.Verdict.Broken -> statusRed
    ElevatorSummary.Verdict.Unknown, ElevatorSummary.Verdict.AwaitingSync -> statusAmber
    else -> GlanceTheme.colors.onSurfaceVariant
}

@Composable
private fun statusColor(isWorking: Boolean?): ColorProvider = when (isWorking) {
    true -> statusGreen
    false -> statusRed
    null -> statusAmber
}

private fun StatusIcon.drawable(): Int = when (this) {
    StatusIcon.CheckmarkCircle -> R.drawable.ic_widget_check_circle
    StatusIcon.WarningTriangle -> R.drawable.ic_widget_warning
    StatusIcon.QuestionmarkCircle -> R.drawable.ic_widget_help
    StatusIcon.Star -> R.drawable.ic_widget_star
}

// The palette's screen ground as the tile's ground (cream / deep purple),
// following the system theme like the status colours.
private fun palette(hex: String) = Color(WcagContrast.argb(hex))
private val widgetGround =
    androidx.glance.color.ColorProvider(day = palette(Palette.BACKGROUND_LIGHT), night = palette(Palette.BACKGROUND_DARK))
private val statusAmber =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_UNKNOWN_TEXT_LIGHT), night = palette(Palette.STATUS_UNKNOWN_TEXT_DARK))
