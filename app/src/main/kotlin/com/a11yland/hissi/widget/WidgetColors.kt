package com.a11yland.hissi.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import com.a11yland.hissi.core.Palette
import com.a11yland.hissi.core.WcagContrast

// The widgets' colours, all light/dark pairs from :core's Palette so
// PaletteTest covers every pair drawn on the tile. Not GlanceTheme's: from
// Android 12 on those follow the wallpaper (dynamic colour) and would land on
// the fixed palette ground at a contrast nobody checks. The widget renders in
// the system theme, not the app's session-only override.
private fun palette(hex: String) = Color(WcagContrast.argb(hex))

private fun pair(light: String, dark: String) = ColorProvider(day = palette(light), night = palette(dark))

// The palette's screen ground as the tile's ground (cream / deep purple).
internal val widgetGround = pair(Palette.BACKGROUND_LIGHT, Palette.BACKGROUND_DARK)

// Primary text is the opposite ground, like the app's onSurface.
internal val widgetText = pair(Palette.BACKGROUND_DARK, Palette.BACKGROUND_LIGHT)
internal val widgetSecondaryText = pair(Palette.TEXT_SECONDARY_LIGHT, Palette.TEXT_SECONDARY_DARK)
internal val widgetTint = pair(Palette.TINT_LIGHT, Palette.TINT_DARK)

// The status text pairs (StatusUi) — AAA on the ground like in the app.
internal val statusGreen = pair(Palette.STATUS_OK_TEXT_LIGHT, Palette.STATUS_OK_TEXT_DARK)
internal val statusRed = pair(Palette.STATUS_DOWN_TEXT_LIGHT, Palette.STATUS_DOWN_TEXT_DARK)
internal val statusAmber = pair(Palette.STATUS_UNKNOWN_TEXT_LIGHT, Palette.STATUS_UNKNOWN_TEXT_DARK)
