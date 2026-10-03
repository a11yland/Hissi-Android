package com.a11yland.hissi.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.a11yland.hissi.BuildConfig
import com.a11yland.hissi.core.CatalogCache
import com.a11yland.hissi.core.PageCountStore
import com.a11yland.hissi.core.SeedCatalog
import com.a11yland.hissi.core.TransitApi
import com.a11yland.hissi.widget.HissiWidget
import com.a11yland.hissi.widget.NearbyWidget
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Hand-wired singletons — the app is small enough that a DI framework would
// only add ceremony.
class AppContainer(private val context: Context) {
    // Builds and refreshes must survive a cancelled caller (search keystrokes,
    // closed screens), so they run on this app-lifetime scope.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api: TransitApi by lazy {
        TransitApi(
            engine = OkHttp.create(),
            token = BuildConfig.TRANSIT_TOKEN,
            pageCounts = prefsPageCounts,
            // Mirrors the iOS behavior: requests carry the app's resolved
            // language (per-app locale included — it changes the default).
            appLocalization = { Locale.getDefault().toLanguageTag() },
            logger = { android.util.Log.w("HissiApi", it) },
        )
    }

    // Last seen totalPages per collection (speculative pagination), persisted
    // like the iOS UserDefaults counterpart.
    private val prefsPageCounts: PageCountStore by lazy {
        val prefs = context.getSharedPreferences("catalog-page-counts", Context.MODE_PRIVATE)
        object : PageCountStore {
            override fun hint(resource: String): Int? =
                prefs.getInt(resource, 0).takeIf { it > 1 }

            override fun remember(count: Int, resource: String) {
                prefs.edit().putInt(resource, count).apply()
            }
        }
    }

    // The widget re-renders from the favorites store, so every write the user
    // can notice (refresh, favorite toggle) requests a reload here — the
    // Android counterpart of the iOS RefreshFanOut's reloadAllTimelines.
    // Cheap: a store written within RefreshInterval.cacheReuse answers the
    // reload without a fetch (FavoritesRefresher.freshEnough).
    fun reloadWidgets() {
        appScope.launch {
            runCatching { HissiWidget().updateAll(context) }
            runCatching { NearbyWidget().updateAll(context) }
        }
    }

    val favorites: FavoritesRepository by lazy { FavoritesRepository(context) }
    val recents: RecentsRepository by lazy { RecentsRepository(context) }
    val onboarding: OnboardingRepository by lazy { OnboardingRepository(context) }
    val alerts: AlertsRepository by lazy { AlertsRepository(context) }

    // The bundled seed and the catalog need disk I/O, hence memoized suspend
    // accessors instead of lazy properties.
    private var seedRecords: List<SeedCatalog.Record>? = null
    private var catalogCache: CatalogCache? = null
    private val catalogMutex = Mutex()

    suspend fun seed(): List<SeedCatalog.Record> = catalogMutex.withLock { loadSeedLocked() }

    suspend fun catalog(): CatalogCache = catalogMutex.withLock {
        catalogCache?.let { return it }
        CatalogCache(
            cacheFile = File(context.cacheDir, "equipment-catalog.json"),
            seed = loadSeedLocked(),
            scope = appScope,
            fetchTargeted = api::fetchEquipment,
            buildLive = api::fetchCatalog,
        ).also { catalogCache = it }
    }

    private suspend fun loadSeedLocked(): List<SeedCatalog.Record> {
        seedRecords?.let { return it }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("seed-catalog.json").bufferedReader().use { it.readText() }
            }.getOrNull()?.let(SeedCatalog::parse).orEmpty()
        }
        seedRecords = parsed
        return parsed
    }
}
