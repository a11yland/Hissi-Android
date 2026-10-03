package com.a11yland.hissi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.a11yland.hissi.core.Palette
import com.a11yland.hissi.core.WcagContrast
import com.a11yland.hissi.ui.ContentScreen
import com.a11yland.hissi.ui.LocalDarkTheme

class MainActivity : ComponentActivity() {
    companion object {
        // Set by the nearby widget's tap: open on the nearby block (locate and
        // present the search) — the Android counterpart of AppDeepLink.nearby.
        const val EXTRA_SHOW_NEARBY = "com.a11yland.hissi.SHOW_NEARBY"
    }

    // Counts nearby requests so a repeated tap (same extra, singleTop
    // onNewIntent) re-triggers the LaunchedEffect keyed on it.
    private var nearbyRequests by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_SHOW_NEARBY, false) == true) nearbyRequests++
        setContent {
            // Session-only appearance override, mirroring iOS AppAppearance:
            // resets to the system setting on every cold start by design —
            // but survives rotation, which is a config change, not a session.
            var override by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val dark = override ?: isSystemInDarkTheme()
            // enableEdgeToEdge derives the system-bar icon colours from the
            // *system* theme — restyle whenever the effective theme diverges.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style =
                    if (dark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalDarkTheme provides dark) {
                MaterialTheme(
                    colorScheme = if (dark) hissiDarkColorScheme else hissiLightColorScheme,
                ) {
                    ContentScreen(
                        isDark = dark,
                        onToggleAppearance = { override = !dark },
                        nearbyRequests = nearbyRequests,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_NEARBY, false)) nearbyRequests++
    }
}

// The "Creme-Lila" palette from :core's Palette (the iOS colour catalog) laid
// onto the Material roles: the screen ground (cream / deep purple) as
// background and surface, the rows' surface as the container tones, the
// palette's separator and secondary text, and the button lilac as primary —
// AAA as text on every ground (PaletteTest). The dark tint is pale, so filled
// buttons carry the palette's own label colour instead of white.
private fun hex(value: String) = Color(WcagContrast.argb(value))

private val hissiLightColorScheme = lightColorScheme(
    primary = hex(Palette.TINT_LIGHT),
    onPrimary = hex(Palette.ON_TINT_LIGHT),
    primaryContainer = hex(Palette.tintFillLight),
    onPrimaryContainer = hex(Palette.TINT_LIGHT),
    secondary = hex(Palette.TINT_LIGHT),
    onSecondary = hex(Palette.ON_TINT_LIGHT),
    secondaryContainer = hex(Palette.tintFillLight),
    onSecondaryContainer = hex(Palette.TINT_LIGHT),
    background = hex(Palette.BACKGROUND_LIGHT),
    onBackground = hex(Palette.BACKGROUND_DARK),
    surface = hex(Palette.BACKGROUND_LIGHT),
    onSurface = hex(Palette.BACKGROUND_DARK),
    surfaceVariant = hex(Palette.SURFACE_HIGH_LIGHT),
    onSurfaceVariant = hex(Palette.TEXT_SECONDARY_LIGHT),
    surfaceContainerLowest = hex(Palette.SURFACE_LIGHT),
    surfaceContainerLow = hex(Palette.SURFACE_LIGHT),
    surfaceContainer = hex(Palette.SURFACE_LIGHT),
    surfaceContainerHigh = hex(Palette.SURFACE_HIGH_LIGHT),
    surfaceContainerHighest = hex(Palette.SURFACE_HIGH_LIGHT),
    outline = hex(Palette.TEXT_SECONDARY_LIGHT),
    outlineVariant = hex(Palette.SEPARATOR_LIGHT),
)

private val hissiDarkColorScheme = darkColorScheme(
    primary = hex(Palette.TINT_DARK),
    onPrimary = hex(Palette.ON_TINT_DARK),
    primaryContainer = hex(Palette.tintFillDark),
    onPrimaryContainer = hex(Palette.TINT_DARK),
    secondary = hex(Palette.TINT_DARK),
    onSecondary = hex(Palette.ON_TINT_DARK),
    secondaryContainer = hex(Palette.tintFillDark),
    onSecondaryContainer = hex(Palette.TINT_DARK),
    background = hex(Palette.BACKGROUND_DARK),
    onBackground = hex(Palette.BACKGROUND_LIGHT),
    surface = hex(Palette.BACKGROUND_DARK),
    onSurface = hex(Palette.BACKGROUND_LIGHT),
    surfaceVariant = hex(Palette.SURFACE_HIGH_DARK),
    onSurfaceVariant = hex(Palette.TEXT_SECONDARY_DARK),
    surfaceContainerLowest = hex(Palette.SURFACE_DARK),
    surfaceContainerLow = hex(Palette.SURFACE_DARK),
    surfaceContainer = hex(Palette.SURFACE_DARK),
    surfaceContainerHigh = hex(Palette.SURFACE_HIGH_DARK),
    surfaceContainerHighest = hex(Palette.SURFACE_HIGH_DARK),
    outline = hex(Palette.TEXT_SECONDARY_DARK),
    outlineVariant = hex(Palette.SEPARATOR_DARK),
)
