package com.a11yland.hissi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.a11yland.hissi.BuildConfig
import com.a11yland.hissi.core.WelcomeGate
import kotlinx.coroutines.flow.first

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

// Persistence side of WelcomeGate — the decision matrix itself is the
// fixture-tested :core. Mirrors the iOS OnboardingState.
class OnboardingRepository(private val context: Context) {
    private val welcomeKey = booleanPreferencesKey("hasSeenWelcome")
    private val whatsNewKey = stringPreferencesKey("whatsNewShownForVersion")

    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun pendingSheet(): WelcomeGate.Sheet? {
        val preferences = context.onboardingDataStore.data.first()
        return WelcomeGate.sheet(
            hasSeenWelcome = preferences[welcomeKey] ?: false,
            shownWhatsNewVersion = preferences[whatsNewKey],
            currentVersion = currentVersion,
        )
    }

    // The welcome also stamps the current version: it already presents the
    // app as of today, a "Was ist neu" right after would be noise.
    suspend fun markWelcomeSeen() {
        context.onboardingDataStore.edit {
            it[welcomeKey] = true
            it[whatsNewKey] = currentVersion
        }
    }

    suspend fun markWhatsNewSeen(version: String) {
        context.onboardingDataStore.edit { it[whatsNewKey] = version }
    }
}
