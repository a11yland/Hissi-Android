package com.a11yland.hissi.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.a11yland.hissi.R

// The disruption-alerts opt-in — the Android counterpart of the iOS live
// status, and like it behind the bell in the favorites app bar (AlertsSheet
// in ContentScreen) rather than a row above the list. The system permission dialog
// fires only from the toggle (in-context, mirroring the location rule), never
// from launching the app.
//
// The toggle records the user's intent and enables even when the notification
// permission is missing: the worker keeps evaluating, the hint below guides
// to the settings, and a grant made there makes alerts arrive without
// flipping anything again. Battery optimization is the main reliability risk
// for a 30-minute cadence (OEM killers), so an enabled toggle points at the
// exemption while it is not given.
//
// Xiaomi (MIUI/HyperOS, incl. Redmi and POCO) gets its own hint instead: its
// battery saver and the Autostart switch sit on top of the AOSP exemption,
// and without Autostart the system drops WorkManager jobs once the app is
// swiped away or after a reboot — alerts and widget refreshes simply stop.
// Neither switch is readable, so the hint shows until the user dismisses it,
// and it opens App info, where HyperOS lists both (stable, unlike the
// security-center component names that change between versions).
@Composable
fun AlertsSection(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var exemptFromBatteryOptimization by remember {
        mutableStateOf(isExemptFromBatteryOptimization(context))
    }
    var xiaomiHintDismissed by remember {
        mutableStateOf(uiPrefs(context).getBoolean(XIAOMI_HINT_DISMISSED, false))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted
        onSetEnabled(true)
    }

    // Both states are system settings the user may change over in Settings —
    // reflect them on return, not on the next visit.
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        exemptFromBatteryOptimization = isExemptFromBatteryOptimization(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    stringResource(R.string.alerts_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.alerts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { wanted ->
                    if (wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !notificationsAllowed
                    ) {
                        // onSetEnabled follows in the launcher callback — the
                        // dialog's answer refines the hint, not the intent.
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onSetEnabled(wanted)
                    }
                },
            )
        }

        if (enabled && !notificationsAllowed) {
            SettingsHint(
                text = stringResource(R.string.alerts_denied),
                action = stringResource(R.string.nearby_open_settings),
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }

        if (enabled && notificationsAllowed && isXiaomi() && !xiaomiHintDismissed) {
            SettingsHint(
                text = stringResource(R.string.alerts_xiaomi_hint),
                action = stringResource(R.string.alerts_xiaomi_open),
                dismiss = stringResource(R.string.alerts_xiaomi_done),
                onDismiss = {
                    uiPrefs(context).edit().putBoolean(XIAOMI_HINT_DISMISSED, true).apply()
                    xiaomiHintDismissed = true
                },
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }
        }

        if (enabled && notificationsAllowed && !isXiaomi() && !exemptFromBatteryOptimization) {
            SettingsHint(
                text = stringResource(R.string.alerts_battery_hint),
                action = stringResource(R.string.alerts_battery_open),
            ) {
                // The policy-safe list, not the direct exemption dialog —
                // that one is gated to app categories Hissi is not in.
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }
}

@Composable
private fun SettingsHint(
    text: String,
    action: String,
    dismiss: String? = null,
    onDismiss: () -> Unit = {},
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpen() },
            )
            if (dismiss != null) {
                Text(
                    dismiss,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }
        }
    }
}

private const val XIAOMI_HINT_DISMISSED = "xiaomiHintDismissed"

private fun uiPrefs(context: Context) =
    context.getSharedPreferences("alerts-ui", Context.MODE_PRIVATE)

// Redmi and POCO devices report Xiaomi as the manufacturer.
private fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

private fun isExemptFromBatteryOptimization(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
