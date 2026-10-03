package com.a11yland.hissi.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R
import com.a11yland.hissi.core.NearbyStations
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.withTimeoutOrNull

// Roughly 600 m across — enough to recognise the street the elevator is on.
// Widening beyond maxFitMeters would trade that context for a dot.
private const val STATION_SPAN_DEGREES = 0.006
private const val MAX_FIT_METERS = 2_000.0

// The map snippet from the iOS detail view: display-only (lite mode renders a
// static tile — cheap, no gestures), the whole surface is one tap target that
// hands the location to the user's maps app. The visible capsule is the
// affordance — without it the snippet reads as static content. Opaque capsule
// background on purpose: a translucent material over arbitrary map imagery
// cannot guarantee any contrast ratio (the blue/surface pairing mirrors the
// WCAG-checked iOS actionBlue).
//
// The device position is opt-in, mirroring iOS b4ceadb: a priming pill on the
// map asks for the grant — never the map on its own, so opening the detail
// view cannot surprise anyone with a prompt. Once granted (here or over in
// the nearby block — the permission is global state, unlike the iOS shared
// LocationProvider) the my-location layer draws the dot and a one-shot fix
// re-frames the camera; a denial leaves the map as it was rather than
// nagging.
@Composable
fun MapSnippet(
    latitude: Double,
    longitude: Double,
    stationName: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val position = LatLng(latitude, longitude)
    val initialCamera = CameraPosition.fromLatLngZoom(position, 16f)
    val camera = rememberCameraPositionState { this.position = initialCamera }
    val label = stringResource(R.string.map_snippet_label, stationName)
    val actionBlue = MaterialTheme.colorScheme.primary
    // The MapView spawns with a world-spanning default camera and applies the
    // compose camera state only after initialization — seeding the options
    // camera starts it at the station, and the fade keeps the tile loading
    // from flashing through.
    var loaded by remember { mutableStateOf(false) }
    val mapAlpha by animateFloatAsState(if (loaded) 1f else 0f, label = "mapFade")

    var authorized by remember { mutableStateOf(hasLocationPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) authorized = true else denied = true
    }

    // A grant (from the pill, or made earlier for the nearby suggestions)
    // gets one silent fix for the framing — the dot itself is the layer's.
    LaunchedEffect(authorized) {
        if (authorized) fix = withTimeoutOrNull(15_000) { currentLocation(context) }
    }

    // Station plus device when the two are close enough to share a frame.
    // Further apart than MAX_FIT_METERS the fit would zoom out so far that
    // the station loses its context — and someone 5 km away learns nothing
    // from seeing both dots at once — so the station keeps the frame and the
    // dot simply sits off-screen. Gated on loaded: a bounds update needs a
    // laid-out map.
    LaunchedEffect(fix, loaded) {
        val (deviceLatitude, deviceLongitude) = fix ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val distance = NearbyStations.distanceMeters(
            deviceLatitude, deviceLongitude, latitude, longitude,
        )
        if (distance > MAX_FIT_METERS) return@LaunchedEffect
        // Both points plus a margin, never tighter than the station frame.
        val latitudeDelta = max(abs(deviceLatitude - latitude) * 2.4, STATION_SPAN_DEGREES)
        val longitudeDelta = max(abs(deviceLongitude - longitude) * 2.4, STATION_SPAN_DEGREES)
        val centerLatitude = (deviceLatitude + latitude) / 2
        val centerLongitude = (deviceLongitude + longitude) / 2
        camera.move(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds(
                    LatLng(centerLatitude - latitudeDelta / 2, centerLongitude - longitudeDelta / 2),
                    LatLng(centerLatitude + latitudeDelta / 2, centerLongitude + longitudeDelta / 2),
                ),
                0,
            ),
        )
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        GoogleMap(
            modifier = Modifier
                .matchParentSize()
                .alpha(mapAlpha),
            cameraPositionState = camera,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).camera(initialCamera) },
            onMapLoaded = { loaded = true },
            // The layer never asks for the authorization on its own (enabling
            // it unauthorized would throw, hence the guard), so this cannot
            // surprise anyone with a prompt. Without the grant there is no dot.
            properties = MapProperties(isMyLocationEnabled = authorized),
            uiSettings = MapUiSettings(
                mapToolbarEnabled = false,
                zoomControlsEnabled = false,
                compassEnabled = false,
                myLocationButtonEnabled = false,
            ),
        ) {
            Marker(state = MarkerState(position = position), title = stationName)
        }
        // Lite mode swallows no clicks, but the overlay guarantees one tap
        // surface with one accessible name regardless of map internals.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClickLabel = stringResource(R.string.open_in_maps)) { onOpen() }
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick { onOpen(); true }
                },
        )
        // Priming affordance for the location prompt, layered above the tap
        // overlay so it stays tappable and focusable. The system dialog fires
        // from this tap and from nothing else — same rule as the nearby
        // suggestions. Once the grant is in the dot speaks for itself and the
        // pill goes; a denial removes it without nagging. Top start is the
        // one free corner — the open-in-Maps capsule holds the bottom end,
        // and at 160 dp both would collide on a narrow phone.
        if (!authorized && !denied) {
            MapPill(
                text = stringResource(R.string.map_show_location),
                icon = Icons.Filled.LocationOn,
                tint = actionBlue,
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
        // A plain Surface deliberately blocks touch propagation, so the
        // capsule must be clickable itself; semantics stay cleared — the
        // full-size overlay above is the one accessible target.
        Surface(
            onClick = onOpen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clearAndSetSemantics { },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Map,
                    contentDescription = null,
                    tint = actionBlue,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    stringResource(R.string.open_in_maps),
                    style = MaterialTheme.typography.labelMedium,
                    color = actionBlue,
                )
            }
        }
    }
}

// Same opaque pill as the open-in-Maps affordance — a translucent material
// over arbitrary map imagery guarantees no contrast ratio.
@Composable
private fun MapPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}
