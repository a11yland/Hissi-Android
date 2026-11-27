package com.a11yland.hissi.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R
import com.a11yland.hissi.core.WelcomeGate

// First-launch welcome and per-release "Was ist neu" as bottom sheets — the
// Android counterpart of the iOS WelcomeSheet/WhatsNewSheet. Dismissing in
// any way (button or swipe) stamps the seen-state via onDismiss.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(sheet: WelcomeGate.Sheet, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (sheet) {
                is WelcomeGate.Sheet.Welcome -> WelcomeContent()
                is WelcomeGate.Sheet.WhatsNew -> WhatsNewContent(sheet.version)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (sheet is WelcomeGate.Sheet.Welcome) R.string.onboarding_get_started
                        else R.string.onboarding_continue,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WelcomeContent() {
    Text(
        stringResource(R.string.welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AppFeature.welcome.forEach { FeatureRow(it) }
    }
}

// Curated per release; versions listed here must appear in
// WelcomeGate.curatedVersions, or the sheet never shows. 1.0 has none — the
// welcome says what the app does. Four rows at most: this is a sheet in the
// way of the favorites, not release notes. 1.3 carries the iOS 1.1 notes
// (the same changes arrived here) plus the accessibility work.
@Composable
private fun WhatsNewContent(version: String) {
    Text(
        stringResource(R.string.whats_new_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        version,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        when (version) {
            "1.3" -> {
                FeatureRow(Icons.Filled.LocationOn, R.string.whats_new_1_3_nearby_title, R.string.whats_new_1_3_nearby_detail)
                FeatureRow(Icons.Filled.Widgets, R.string.whats_new_1_3_widgets_title, R.string.whats_new_1_3_widgets_detail)
                FeatureRow(Icons.Filled.AutoAwesome, R.string.whats_new_1_3_interface_title, R.string.whats_new_1_3_interface_detail)
                FeatureRow(Icons.Filled.Accessibility, R.string.whats_new_1_3_accessibility_title, R.string.whats_new_1_3_accessibility_detail)
            }
            "1.4" -> {
                FeatureRow(Icons.Filled.Map, R.string.whats_new_1_4_germany_title, R.string.whats_new_1_4_germany_detail)
            }
            "1.5" -> {
                // Disruptions first — in the list and in these notes; the
                // data update is the footnote.
                FeatureRow(Icons.Filled.SwapVert, R.string.whats_new_1_5_order_title, R.string.whats_new_1_5_order_detail)
                FeatureRow(Icons.Filled.Schedule, R.string.whats_new_1_5_since_title, R.string.whats_new_1_5_since_detail)
                FeatureRow(Icons.Filled.Info, R.string.whats_new_1_5_reason_title, R.string.whats_new_1_5_reason_detail)
                FeatureRow(Icons.Filled.Sync, R.string.whats_new_1_5_data_title, R.string.whats_new_1_5_data_detail)
            }
            else -> Unit
        }
    }
}

// One feature: icon, title, detail — internal, the about sheet draws the
// same rows.
@Composable
internal fun FeatureRow(feature: AppFeature) {
    FeatureRow(feature.icon, feature.titleRes, feature.detailRes)
}

@Composable
private fun FeatureRow(icon: ImageVector, @StringRes titleRes: Int, @StringRes detailRes: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(detailRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
