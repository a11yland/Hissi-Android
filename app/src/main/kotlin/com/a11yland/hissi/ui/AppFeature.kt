package com.a11yland.hissi.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.a11yland.hissi.R

// What the app does, as rows — the Android counterpart of the iOS
// AppFeature. The welcome sheet draws its three from here, the about sheet
// lists them all, so the two never drift apart.
enum class AppFeature(
    val icon: ImageVector,
    @param:StringRes val titleRes: Int,
    @param:StringRes val detailRes: Int,
) {
    // In the order of a trip: find, nearby, favorites, then the two ways of
    // knowing without opening the app — the passive widgets and the alerts.
    Search(Icons.Filled.Search, R.string.welcome_search_title, R.string.welcome_search_detail),
    Nearby(Icons.Filled.LocationOn, R.string.welcome_nearby_title, R.string.welcome_nearby_detail),
    Favorites(Icons.Filled.Star, R.string.welcome_favorites_title, R.string.welcome_favorites_detail),
    Widget(Icons.Filled.Widgets, R.string.feature_widget_title, R.string.feature_widget_detail),
    Alerts(Icons.Filled.NotificationsActive, R.string.feature_alerts_title, R.string.feature_alerts_detail),
    ;

    companion object {
        val welcome: List<AppFeature> = listOf(Search, Favorites, Widget)
        val all: List<AppFeature> = entries
    }
}
