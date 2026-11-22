package com.a11yland.hissi

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a11yland.hissi.core.DemoFixtures
import com.a11yland.hissi.data.DemoMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

// Play screenshots — the Android counterpart of HissiUITests/ScreenshotTests:
// one test per motif in demo mode (DemoMode: the bundled seed with fixed
// statuses, DemoFixtures' favorites), each a fresh activity. The shots land
// in the app's external files dir (screenshots/<name>.png), where
// scripts/screenshots.sh pulls them and pillarboxes them for Play. Run only
// through that script: it prepares the emulator (clean app data, per-app
// locale, SystemUI demo status bar) and the tests rely on that order —
// the welcome sheet needs the first launch, so it runs first.
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTests {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<HissiApplication>()

    @Before
    fun demoMode() {
        DemoMode.requested = true
    }

    @Test
    fun test1Welcome() {
        prepare(favorites = false, onboarded = false, dark = false)
        launch {
            waitForText(app.getString(R.string.welcome_search_title))
            snap("04-willkommen")
        }
    }

    @Test
    fun test2FavoritesDark() {
        prepare(favorites = true, onboarded = true, dark = true)
        launch {
            waitForFavorites()
            snap("01-favoriten")
        }
    }

    @Test
    fun test3Search() {
        prepare(favorites = true, onboarded = true, dark = false)
        launch {
            waitForFavorites()
            compose.onNode(hasSetTextAction()).performTextInput("Alexanderplatz")
            // The keyboard would cover half the shot; Back only closes it.
            shell("input keyevent 4")
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodes(mentions("Alexanderplatz")).fetchSemanticsNodes().size > 2
            }
            snap("02-suche")
        }
    }

    @Test
    fun test4FavoritesLight() {
        prepare(favorites = true, onboarded = true, dark = false)
        launch {
            waitForFavorites()
            snap("03-favoriten-hell")
        }
    }

    // Stored state before the activity starts: the favorites (demo picks or
    // none), the onboarding sheets (seen or not) and the system theme the app
    // follows.
    private fun prepare(favorites: Boolean, onboarded: Boolean, dark: Boolean) = runBlocking {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        val container = app.container
        val demo = container.demoCatalog()
        container.favorites.replaceAll(
            if (favorites) DemoFixtures.favorites(demo, System.currentTimeMillis()) else emptyList(),
        )
        if (onboarded) container.onboarding.markWelcomeSeen()
    }

    private fun launch(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { block() }
    }

    private fun waitForFavorites() {
        waitForText(DemoFixtures.favoritePicks.first().station)
        // The on-appear refresh answers from the demo catalog; give its
        // statuses and the list's animations a moment to settle.
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(mentions(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Elevator rows and search headers replace their children's text with one
    // spoken contentDescription (clearAndSetSemantics), so a text-only
    // matcher never sees a station name there.
    private fun mentions(text: String): SemanticsMatcher =
        hasText(text, substring = true) or hasContentDescription(text, substring = true)

    private fun snap(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot for $name")
        val directory = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(SHELL_SETTLE_MILLIS)
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SETTLE_MILLIS = 1_500L
        const val SHELL_SETTLE_MILLIS = 800L
    }
}
