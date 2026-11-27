package com.a11yland.hissi.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.a11yland.hissi.BuildConfig
import com.a11yland.hissi.core.AccessibilityCloudClient
import com.a11yland.hissi.core.CatalogCache
import com.a11yland.hissi.core.DemoFixtures
import com.a11yland.hissi.core.PageCountStore
import com.a11yland.hissi.core.SeedCatalog
import com.a11yland.hissi.core.StatusSources
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

    // The status source of every refresh: the catalog's targeted fetch. Its
    // records come with the bundled seed overlaid, so their source and
    // operator names replace what a favorite carries.
    suspend fun statusSources(): StatusSources {
        val catalog = catalog()
        return StatusSources(equipment = { catalog.equipment(it) }, metadataIsComplete = true)
    }

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
    private var seedStationList: List<SeedCatalog.Station>? = null
    private var catalogCache: CatalogCache? = null
    private val catalogMutex = Mutex()

    suspend fun seed(): List<SeedCatalog.Record> = catalogMutex.withLock { loadSeedLocked() }

    // The seed's station coordinates — the overlay's last fallback.
    suspend fun seedStations(): List<SeedCatalog.Station> = catalogMutex.withLock {
        seedStationList?.let { return it }
        withContext(Dispatchers.IO) {
            readSeedAsset()?.let(SeedCatalog::parseStations).orEmpty()
        }.also { seedStationList = it }
    }

    suspend fun catalog(): CatalogCache = catalogMutex.withLock {
        catalogCache?.let { return it }
        CatalogCache(
            cacheFile = File(context.cacheDir, "equipment-catalog.json"),
            seed = loadSeedLocked(),
            stations = withContext(Dispatchers.IO) {
                readSeedAsset()?.let(SeedCatalog::parseStations).orEmpty()
            }.also { seedStationList = it },
            legacyIds = withContext(Dispatchers.IO) {
                readSeedAsset()?.let(SeedCatalog::parseLegacyIds).orEmpty()
            },
            scope = appScope,
            fetchTargeted = if (DemoMode.isActive) {
                { ids -> demoCatalog().filter { it.id in ids } }
            } else {
                api::fetchEquipment
            },
            buildLive = if (DemoMode.isActive) {
                { demoCatalog() }
            } else {
                api::fetchCatalog
            },
        ).also { catalogCache = it }
    }

    // The screenshot catalog (DemoMode): the seed with fixed statuses, dates
    // relative to the first access.
    private var demo: List<AccessibilityCloudClient.Equipment>? = null

    suspend fun demoCatalog(): List<AccessibilityCloudClient.Equipment> {
        demo?.let { return it }
        val seed = seedRecords ?: withContext(Dispatchers.IO) {
            readSeedAsset()?.let(SeedCatalog::parse).orEmpty()
        }
        return DemoFixtures.catalog(seed, System.currentTimeMillis(), Locale.getDefault().language)
            .also { demo = it }
    }

    private suspend fun loadSeedLocked(): List<SeedCatalog.Record> {
        seedRecords?.let { return it }
        val parsed = withContext(Dispatchers.IO) {
            readSeedAsset()?.let(SeedCatalog::parse).orEmpty()
        }
        seedRecords = parsed
        return parsed
    }

    private fun readSeedAsset(): String? = runCatching {
        context.assets.open("seed-catalog.json").bufferedReader().use { it.readText() }
    }.getOrNull()
}
