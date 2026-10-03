package com.a11yland.hissi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Streetview
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
import androidx.compose.runtime.snapshotFlow
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
import com.google.android.gms.maps.StreetViewPanoramaOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StreetViewSource
import com.google.maps.android.compose.streetview.StreetView
import com.google.maps.android.compose.streetview.rememberStreetViewCameraPositionState
import com.google.maps.android.ktx.MapsExperimentalFeature

// The street-level counterpart of the iOS Look Around preview: Street View
// ships in the same play-services-maps artifact as the map snippet, and all
// mobile usage of the Maps SDK is unlimited at no charge — see the reversal
// note in docs/android-plan.md.
//
// Display-only like the map snippet: gestures off, one tap surface. The tap
// hands the spot to Google Maps' Street View instead of an in-app viewer —
// the same hand-off pattern as the map's geo: intent (iOS opens Look Around
// in-app because LookAroundPreview brings the viewer for free).
//
// Shown only where a panorama exists, like the iOS row: the panorama-change
// listener answers the coverage question (a null location means "nothing
// within the search radius" — it leaks through maps-compose's non-null state
// type, hence the safe-call below), with no extra API to enable. The caller
// keeps the snippet composed at zero size while unconfirmed so the probe can
// run without the layout ever showing an empty frame.
@OptIn(MapsExperimentalFeature::class)
@Composable
fun StreetViewSnippet(
    latitude: Double,
    longitude: Double,
    stationName: String,
    onCoverage: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val camera = rememberStreetViewCameraPositionState()
    var covered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (covered) 1f else 0f, label = "streetViewFade")
    val label = stringResource(R.string.street_view_label, stationName)
    val actionBlue = MaterialTheme.colorScheme.primary
    val onOpen = { openStreetView(context, latitude, longitude) }

    // The initial state is a sentinel with an empty pano id; a resolved
    // panorama always carries one.
    LaunchedEffect(Unit) {
        snapshotFlow { camera.location }.collect { location ->
            @Suppress("UNNECESSARY_SAFE_CALL")
            covered = location?.panoId?.isNotEmpty() == true
            onCoverage(covered)
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        StreetView(
            modifier = Modifier
                .matchParentSize()
                .alpha(alpha),
            cameraPositionState = camera,
            streetViewPanoramaOptionsFactory = {
                // Outdoor only — the iOS Look Around vocabulary is the
                // street, not the inside of a shop next to the entrance. The
                // radius gives entrance coordinates some slack to the nearest
                // camera path.
                StreetViewPanoramaOptions()
                    .position(LatLng(latitude, longitude), 100, StreetViewSource.OUTDOOR)
            },
            isPanningGesturesEnabled = false,
            isUserNavigationEnabled = false,
            isZoomGesturesEnabled = false,
        )
        // One tap surface with one accessible name, mirroring the map
        // snippet's overlay.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClickLabel = stringResource(R.string.open_street_view)) { onOpen() }
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick { onOpen(); true }
                },
        )
        // Same opaque capsule as the map's open-in-Maps affordance — without
        // it the snippet reads as static content.
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
                    Icons.Filled.Streetview,
                    contentDescription = null,
                    tint = actionBlue,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    stringResource(R.string.open_street_view),
                    style = MaterialTheme.typography.labelMedium,
                    color = actionBlue,
                )
            }
        }
    }
}

private fun openStreetView(context: Context, latitude: Double, longitude: Double) {
    val uri = Uri.parse("google.streetview:cbll=$latitude,$longitude")
    // Street View lives in the Google Maps app; without it the tap stays a
    // no-op — the preview itself already showed the street.
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"),
        )
    }
}
