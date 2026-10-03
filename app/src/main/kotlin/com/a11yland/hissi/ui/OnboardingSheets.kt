package com.a11yland.hissi.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
// welcome says what the app does.
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
