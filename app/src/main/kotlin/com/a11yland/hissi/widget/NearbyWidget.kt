package com.a11yland.hissi.widget

import android.content.Context
import android.content.Intent
import android.location.LocationManager
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
import androidx.glance.appwidget.action.actionStartActivity
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
import com.a11yland.hissi.core.Palette
import com.a11yland.hissi.core.WcagContrast
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.EquipmentCatalog
import com.a11yland.hissi.core.NearbyStations
import com.a11yland.hissi.core.StatusIcon
import com.a11yland.hissi.ui.currentLocation
import com.a11yland.hissi.ui.hasLocationPermission
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

class NearbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NearbyWidget()
}

// "In der Nähe" as a tile — the Android counterpart of the iOS NearbyWidget:
// the stations within walking distance and their lift status, nearest first.
// One responsive widget: the nearest station when small, three rows when
// wide, eight when tall.
//
// Location: the permission the app holds is reused, the widget never asks
// itself — without a grant it says so ("Standort in der App erlauben"), and
// without a fix within a short timeout it says that instead of guessing.
// Data: the catalog snapshot the app persisted (read-only — a widget never
// kicks off the ~30 s build; before the app has built one the bundled seed
// previews names with unknown statuses), then one targeted status request
// over exactly the shown stations' elevators. A failed request keeps the
// snapshot statuses: nearby is a suggestion, not a core flow.
class NearbyWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val TALL = DpSize(110.dp, 250.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(250.dp, 250.dp)
        private const val MAX_ROWS = 8
        private const val FIX_TIMEOUT_MILLIS = 8_000L
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, TALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent {
            GlanceTheme {
                NearbyContent(state)
            }
        }
    }

    private suspend fun loadState(context: Context): NearbyWidgetState {
        if (!hasLocationPermission(context)) return NearbyWidgetState.NoPermission
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) { currentLocation(context) }
            ?: lastKnownLocation(context)
            ?: return NearbyWidgetState.NoFix
        val container = (context.applicationContext as HissiApplication).container
        val catalog = container.catalog()
        val records = catalog.snapshot(rebuildingIfStale = false)
            ?: EquipmentCatalog.overlaid(live = emptyList(), seed = container.seed())
        val stations = NearbyStations.nearest(fix.first, fix.second, records, limit = MAX_ROWS)
        if (stations.isEmpty()) return NearbyWidgetState.Located(emptyList())
        // Phase 2: live statuses for exactly these rows — free while the
        // catalog cache is fresh, one request otherwise. What it doesn't
        // answer keeps its snapshot status.
        val fresh = runCatching { catalog.equipment(NearbyStations.elevatorIds(stations)) }
            .getOrDefault(emptyMap())
        return NearbyWidgetState.Located(NearbyStations.restated(stations, fresh))
    }

    // Any fix the system still holds, whatever its age — a stale position
    // beats no rows at all for a widget that cannot wait for GPS.
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            manager.allProviders
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}

private sealed interface NearbyWidgetState {
    data object NoPermission : NearbyWidgetState
    data object NoFix : NearbyWidgetState
    data class Located(val stations: List<NearbyStations.Station>) : NearbyWidgetState
}

// Light/dark pairs from the shared status palette (StatusUi) — the widget
// renders in the system theme, not the app's session-only override.
private val statusGreen =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_OK_TEXT_LIGHT), night = palette(Palette.STATUS_OK_TEXT_DARK))
private val statusRed =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_DOWN_TEXT_LIGHT), night = palette(Palette.STATUS_DOWN_TEXT_DARK))

@Composable
private fun NearbyContent(state: NearbyWidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    // The whole widget opens the app on the nearby block — locate and present
    // the search, like the iOS AppDeepLink.nearby.
    val open = Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_SHOW_NEARBY, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    var root = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(widgetGround)
        .clickable(actionStartActivity(open))
        .padding(12.dp)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        root = root.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    }
    when (state) {
        NearbyWidgetState.NoPermission ->
            Notice(context.getString(R.string.widget_location_allow), root)
        NearbyWidgetState.NoFix ->
            Notice(context.getString(R.string.widget_location_unavailable), root)
        is NearbyWidgetState.Located -> when {
            state.stations.isEmpty() -> Notice(
                context.getString(R.string.nearby_none_within, distanceLabel(NearbyStations.WALKING_RADIUS_METERS)),
                root,
            )
            size.height >= 250.dp -> StationList(state.stations, maxRows = 8, modifier = root)
            size.width >= 250.dp -> StationList(state.stations, maxRows = 3, modifier = root)
            else -> Nearest(state.stations.first(), root)
        }
    }
}

// Permission missing, no fix, nothing within reach: one sentence, one
// element — never an empty tile that could pass for "nothing nearby".
@Composable
private fun Notice(text: String, modifier: GlanceModifier) {
    Column(
        modifier = modifier.semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_location),
            contentDescription = null,
            modifier = GlanceModifier.size(22.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text,
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 3,
        )
    }
}

// The small tile: the nearest station, its lifts' verdict and the distance.
@Composable
private fun Nearest(station: NearbyStations.Station, modifier: GlanceModifier) {
    val context = LocalContext.current
    val color = statusColor(station.status)
    Column(
        modifier = modifier.semantics { contentDescription = spokenRow(context, station) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Header()
        Spacer(GlanceModifier.height(4.dp))
        Text(
            station.name,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
            maxLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(station.status.icon.drawable()),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(color),
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                stationStatusText(context, station),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color),
                maxLines = 1,
            )
        }
        Text(
            distanceLabel(station.distanceMeters),
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun StationList(
    stations: List<NearbyStations.Station>,
    maxRows: Int,
    modifier: GlanceModifier,
) {
    Column(modifier = modifier) {
        Header()
        stations.take(maxRows).forEach { StationRow(it) }
    }
}

// "In der Nähe" with the mark — the tile's identity; the system chrome names
// the app, the header says what the tile shows.
@Composable
private fun Header() {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(bottom = 2.dp)) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_location),
            contentDescription = null,
            modifier = GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            context.getString(R.string.nearby_title),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
    }
}

// One station: name, status (shape + words, colour only reinforces), distance.
// Sorted by distance only — the status is what the row says, never where it
// sits. One row, one sentence for TalkBack.
@Composable
private fun StationRow(station: NearbyStations.Station) {
    val context = LocalContext.current
    val color = statusColor(station.status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics { contentDescription = spokenRow(context, station) },
    ) {
        Image(
            provider = ImageProvider(station.status.icon.drawable()),
            contentDescription = null,
            modifier = GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                station.name,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            Text(
                stationStatusText(context, station),
                style = TextStyle(fontSize = 11.sp, color = color),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            distanceLabel(station.distanceMeters),
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

private fun spokenRow(context: Context, station: NearbyStations.Station): String =
    listOf(station.name, stationStatusText(context, station), distanceLabel(station.distanceMeters))
        .joinToString(", ")

// Station.statusLabel from :core, rebuilt over string resources so it follows
// the app language — the same wording as the app's nearby rows.
private fun stationStatusText(context: Context, station: NearbyStations.Station): String = when {
    station.total <= 1 -> statusLabel(context, station.status)
    station.brokenCount > 0 ->
        context.getString(R.string.nearby_broken_ratio, station.brokenCount, station.total)
    station.unknownCount == station.total -> statusLabel(context, ElevatorStatus.Unknown)
    station.unknownCount > 0 ->
        context.getString(R.string.nearby_unknown_ratio, station.unknownCount, station.total)
    else -> context.getString(R.string.all_working)
}

private fun statusLabel(context: Context, status: ElevatorStatus): String = when (status) {
    ElevatorStatus.Working -> context.getString(R.string.status_working)
    ElevatorStatus.Broken -> context.getString(R.string.status_broken)
    ElevatorStatus.Unknown -> context.getString(R.string.status_unknown)
}

// "350 m" / "1,2 km" / "1 km" — the app's nearby rows' format.
private fun distanceLabel(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    meters % 1000 == 0.0 -> "${(meters / 1000).toInt()} km"
    else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000)
}

@Composable
private fun statusColor(status: ElevatorStatus): ColorProvider = when (status) {
    ElevatorStatus.Working -> statusGreen
    ElevatorStatus.Broken -> statusRed
    ElevatorStatus.Unknown -> statusAmber
}

private fun StatusIcon.drawable(): Int = when (this) {
    StatusIcon.CheckmarkCircle -> R.drawable.ic_widget_check_circle
    StatusIcon.WarningTriangle -> R.drawable.ic_widget_warning
    StatusIcon.QuestionmarkCircle -> R.drawable.ic_widget_help
    StatusIcon.Star -> R.drawable.ic_widget_star
}

// The palette's screen ground as the tile's ground (cream / deep purple) and
// the status text pairs, following the system theme like the status widget.
private fun palette(hex: String) = Color(WcagContrast.argb(hex))
private val widgetGround =
    androidx.glance.color.ColorProvider(day = palette(Palette.BACKGROUND_LIGHT), night = palette(Palette.BACKGROUND_DARK))
private val statusAmber =
    androidx.glance.color.ColorProvider(day = palette(Palette.STATUS_UNKNOWN_TEXT_LIGHT), night = palette(Palette.STATUS_UNKNOWN_TEXT_DARK))
