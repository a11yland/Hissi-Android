package com.a11yland.hissi.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.a11yland.hissi.HissiApplication
import com.a11yland.hissi.core.DisruptionAlert
import com.a11yland.hissi.data.FavoritesRefresher

// The periodic evaluation behind the disruption alerts: refresh all favorites
// over the shared path, compare against the last evaluation's baseline, alert
// on real transitions only (DisruptionAlert rules), remember the new
// baseline. The Android counterpart of the iOS Live Activity's update ride on
// the background task — permanent instead of per-trip (docs/android-plan.md).
class DisruptionAlertsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HissiApplication).container
        val alerts = container.alerts
        // The unique work is cancelled on disable; this guard only covers a
        // run already in flight when the toggle flips.
        if (!alerts.isEnabled()) return Result.success()

        val previous = alerts.baseline()
        val refreshed = runCatching { FavoritesRefresher.refreshAll(container) }
            .getOrNull() ?: return Result.retry()
        // The refresh happened either way — the widget may as well show it
        // (mirrors the iOS background task's fan-out).
        container.reloadWidgets()

        DisruptionAlert.transition(previous, refreshed.elevators)?.let {
            AlertNotifications.post(applicationContext, it)
        }
        // Baseline advances even without an alert: the diff is against the
        // last evaluation, and a standing disruption must stay "standing".
        alerts.saveBaseline(refreshed.elevators)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "disruption-alerts"
    }
}
