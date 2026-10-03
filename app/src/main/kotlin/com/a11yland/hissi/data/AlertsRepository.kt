package com.a11yland.hissi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.a11yland.hissi.alerts.DisruptionAlertsWorker
import com.a11yland.hissi.core.MonitoredElevator
import com.a11yland.hissi.core.RefreshInterval
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.alertsDataStore by preferencesDataStore(name = "alerts")

// The disruption-alerts state: the user's opt-in, the worker scheduling that
// follows from it, and the baseline the transition rules compare against.
// Persistence and scheduling live together because they must never diverge —
// setEnabled is the one entry point (hand-wired app, no DI ceremony).
class AlertsRepository(private val context: Context) {
    private val enabledKey = booleanPreferencesKey("alertsEnabled")
    private val json = Json { ignoreUnknownKeys = true }

    // The favorites as of the last evaluation — what DisruptionAlert.transition
    // diffs against. A plain JSON file rather than DataStore: the worker is
    // its only writer, and a debuggable build can inspect and induce
    // transitions via run-as (the DoD's induced-transition test).
    private val baselineFile = File(context.filesDir, "alert-baseline.json")

    suspend fun isEnabled(): Boolean =
        context.alertsDataStore.data.first()[enabledKey] ?: false

    // Enabling schedules the periodic evaluation; disabling cancels it and
    // drops the baseline, so a later re-enable starts silent (first run has
    // nothing to compare against — by the rules, nothing alerts).
    suspend fun setEnabled(enabled: Boolean) {
        context.alertsDataStore.edit { it[enabledKey] = enabled }
        val workManager = WorkManager.getInstance(context)
        val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        if (enabled) {
            // One immediate evaluation establishes the baseline at the moment
            // of opt-in — without it, a lift breaking before the first
            // periodic run (up to 30 minutes out) would never alert: the
            // first evaluation is silent by the rules (nothing to compare
            // against), and it would swallow the transition as a fact.
            workManager.enqueueUniqueWork(
                DisruptionAlertsWorker.UNIQUE_NAME + "-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DisruptionAlertsWorker>()
                    .setConstraints(connected)
                    .build(),
            )
            // KEEP: re-enabling must not reset the cadence a running chain
            // already has.
            workManager.enqueueUniquePeriodicWork(
                DisruptionAlertsWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DisruptionAlertsWorker>(
                    RefreshInterval.MINUTES.toLong(),
                    TimeUnit.MINUTES,
                )
                    .setConstraints(connected)
                    .build(),
            )
        } else {
            workManager.cancelUniqueWork(DisruptionAlertsWorker.UNIQUE_NAME)
            workManager.cancelUniqueWork(DisruptionAlertsWorker.UNIQUE_NAME + "-now")
            clearBaseline()
        }
    }

    suspend fun baseline(): List<MonitoredElevator> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<List<MonitoredElevator>>(baselineFile.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun saveBaseline(elevators: List<MonitoredElevator>) = withContext(Dispatchers.IO) {
        runCatching { baselineFile.writeText(json.encodeToString(elevators)) }
        Unit
    }

    private suspend fun clearBaseline() = withContext(Dispatchers.IO) {
        baselineFile.delete()
        Unit
    }
}
