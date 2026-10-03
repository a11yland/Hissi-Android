package com.a11yland.hissi.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// One-shot device fix, shared by the nearby suggestions and the detail map.
// The permission itself is global platform state, so unlike the iOS
// LocationProvider there is no object to share — a grant given to either
// surface answers hasLocationPermission for the other. The fix stays in
// memory only, never stored.

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun hasLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

// One fix, ~hundred-meter accuracy is plenty for a nearest-station sort.
internal suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    // GPS first: stations are outdoors, and it is the provider that reliably
    // answers a one-shot request (LocationManager's "fused" needs Play
    // services plumbing that not every device/emulator delivers). GPS needs
    // the fine permission though — an "approximate" grant limits us to the
    // network/fused providers. Probing unknown providers throws on older
    // levels, hence the runCatching.
    val candidates =
        if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, "fused")
        }
    val providers = candidates
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    val provider = providers.firstOrNull() ?: return null
    // A recent cached fix from any provider beats waiting for a fresh one.
    providers
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
        .maxByOrNull { it.time }
        ?.let { return it.latitude to it.longitude }
    return suspendCancellableCoroutine { continuation ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The signal propagates the caller's timeout — without it the
                // provider keeps burning (GPS on) until a fix eventually lands.
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    continuation.resume(location?.let { it.latitude to it.longitude })
                }
            } else {
                val listener = LocationListener { location ->
                    continuation.resume(location.latitude to location.longitude)
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, context.mainLooper)
            }
        } catch (_: SecurityException) {
            continuation.resume(null)
        }
    }
}
