package com.a11yland.hissi.core

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shared cache of the full equipment catalog, backing the station search and
// the favorites refresh — the stateful half of the iOS EquipmentCatalog actor
// (the pure overlay/bridge functions live in EquipmentCatalog). Concurrent
// callers (a refresh racing a search) share one in-flight build instead of
// fetching twice; the joined live catalog is persisted so the search is
// instant across launches (stale-while-revalidate), with the seed overlay
// applied on every load so a release with a newer bundled seed reworks
// persisted data.
class CatalogCache(
    // The API docs recommend caching status-bearing data for 10 minutes.
    private val ttl: Duration = Duration.ofMinutes(10),
    private val cacheFile: File?,
    private val seed: List<SeedCatalog.Record> = emptyList(),
    // Builds run on this scope so a cancelled caller (every search keystroke
    // cancels its predecessor) does not abort the shared build.
    private val scope: CoroutineScope,
    // The targeted where[id][in] fetch (TransitApi::fetchEquipment).
    private val fetchTargeted: suspend (List<String>) -> List<AccessibilityCloudClient.Equipment>?,
    // The live fetch. Returns the joined live catalog WITHOUT the seed
    // overlay — the overlay is applied on every load.
    private val buildLive: suspend () -> List<AccessibilityCloudClient.Equipment>?,
) {
    private val mutex = Mutex()
    private var cached: List<AccessibilityCloudClient.Equipment> = emptyList()
    private var fetchedAt: Instant? = null
    private var inFlight: Deferred<List<AccessibilityCloudClient.Equipment>?>? = null
    private var diskLoaded = false

    @Serializable
    private data class PersistedCatalog(
        val fetchedAtEpochMillis: Long,
        val live: List<AccessibilityCloudClient.Equipment>,
    )

    // Instant snapshot for stale-while-revalidate UIs (the search): memory or
    // persisted catalog of any age, null when neither exists (first launch).
    // When the snapshot is stale the shared rebuild is kicked off in the
    // background; callers show the stale names/statuses now and re-render off
    // all() when the build lands. Deliberately never falls back to the seed:
    // seed records carry pre-migration ids, and a favorite created from one
    // would never resolve live (offline search via all() stays the only path
    // to that).
    //
    // `rebuildingIfStale = false` is the widgets' read-only variant (the iOS
    // `snapshot(rebuildingIfStale:)`): a widget renders whatever the app
    // persisted and never kicks off the ~30 s catalog build itself.
    suspend fun snapshot(
        rebuildingIfStale: Boolean = true,
    ): List<AccessibilityCloudClient.Equipment>? = mutex.withLock {
        loadDiskIfNeeded()
        val fresh = fetchedAt?.let { Duration.between(it, Instant.now()) < ttl } ?: false
        if (rebuildingIfStale && (cached.isEmpty() || !fresh)) startBuildLocked()
        cached.ifEmpty { null }
    }

    // Returns the catalog, refetching when the cache is stale or empty.
    // Returns null only when there is nothing cached, the fetch fails and no
    // seed is bundled.
    suspend fun all(forceRefresh: Boolean = false): List<AccessibilityCloudClient.Equipment>? {
        val build = mutex.withLock {
            loadDiskIfNeeded()
            val fresh = fetchedAt?.let { Duration.between(it, Instant.now()) < ttl } ?: false
            if (!forceRefresh && cached.isNotEmpty() && fresh) return cached
            startBuildLocked()
        }
        build.await()?.let { return it }
        mutex.withLock { cached.ifEmpty { null } }?.let { return it }
        val seedOnly = withContext(Dispatchers.Default) {
            EquipmentCatalog.overlaid(live = emptyList(), seed = seed)
        }
        return seedOnly.ifEmpty { null }
    }

    // Resolves favorites in one batch, keyed by the requested id. A
    // still-fresh catalog cache answers for free; otherwise one targeted
    // request covers all numeric ids — the full catalog build stays
    // search-only. Non-numeric ids are seed-record favorites: when any cached
    // catalog knows their live counterpart they bridge to it via the operator
    // inventory number — the caller sees the live record, adopts its id
    // (StatusMerge) and the favorite refreshes directly from then on.
    // Unbridged seed ids resolve from the bundled seed with unknown status.
    // Ids missing from the result keep their previous values upstream.
    suspend fun equipment(ids: Set<String>): Map<String, AccessibilityCloudClient.Equipment> {
        if (ids.isEmpty()) return emptyMap()
        val (snapshot, fresh) = mutex.withLock {
            loadDiskIfNeeded()
            cached to (fetchedAt?.let { Duration.between(it, Instant.now()) < ttl } ?: false)
        }
        // The id mapping is stable, so any cached catalog answers it — only
        // the status has to be fresh, and that is fetched below.
        val bridged = EquipmentCatalog.bridgedLiveIds(ids, seed, snapshot)
        val resolved: List<AccessibilityCloudClient.Equipment>
        if (snapshot.isNotEmpty() && fresh) {
            resolved = snapshot
        } else {
            val numeric = (ids.filter { it.toLongOrNull() != null } + bridged.values).distinct().sorted()
            val live = if (numeric.isEmpty()) emptyList() else fetchTargeted(numeric)
            // A failed fetch degrades to the seed overlay: numeric favorites
            // stay unresolved (previous values survive), seed-id favorites
            // still answer — status unknown, never a false "working".
            resolved = EquipmentCatalog.overlaid(live = live.orEmpty(), seed = seed)
        }
        val byId = LinkedHashMap<String, AccessibilityCloudClient.Equipment>(resolved.size)
        for (record in resolved) byId.putIfAbsent(record.id, record)
        val result = mutableMapOf<String, AccessibilityCloudClient.Equipment>()
        for (id in ids) {
            val hit = bridged[id]?.let(byId::get) ?: byId[id]
            if (hit != null) result[id] = hit
        }
        // Last resort for seed ids the overlay didn't answer (e.g. an
        // accessibilityCloud-source seed record next to live data, unbridged
        // because no catalog build ever landed): resolve from the bundled
        // seed directly — status unknown, and no spurious failure report.
        val missingSeedIds = ids.filter { result[it] == null && it.toLongOrNull() == null }
        if (missingSeedIds.isNotEmpty()) {
            val fallback = EquipmentCatalog.overlaid(
                live = emptyList(),
                seed = seed.filter { it.id in missingSeedIds },
            )
            for (record in fallback) result[record.id] = record
        }
        return result
    }

    // Callers must hold the mutex. The build itself runs unstructured on the
    // cache's scope; a completed build is always safe to cache.
    private fun startBuildLocked(): Deferred<List<AccessibilityCloudClient.Equipment>?> {
        inFlight?.let { return it }
        val build = scope.async { finishBuild(buildLive()) }
        inFlight = build
        return build
    }

    private suspend fun finishBuild(
        live: List<AccessibilityCloudClient.Equipment>?,
    ): List<AccessibilityCloudClient.Equipment>? = mutex.withLock {
        inFlight = null
        if (live == null) return null
        cached = EquipmentCatalog.overlaid(live = live, seed = seed)
        fetchedAt = Instant.now()
        persist(live)
        cached
    }

    // MARK: Disk persistence (stale-while-revalidate across launches)

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun loadDiskIfNeeded() {
        if (diskLoaded) return
        diskLoaded = true
        if (cached.isNotEmpty()) return
        val file = cacheFile ?: return
        // Multi-MB JSON: read, decode and join off the caller's thread — the
        // first call typically comes from the main thread (search keystroke,
        // refresh). Callers hold the mutex, so the fields stay consistent.
        val loaded = withContext(Dispatchers.IO) {
            val persisted = runCatching {
                json.decodeFromString<PersistedCatalog>(file.readText())
            }.getOrNull() ?: return@withContext null
            if (persisted.live.isEmpty()) return@withContext null
            EquipmentCatalog.overlaid(live = persisted.live, seed = seed) to
                persisted.fetchedAtEpochMillis
        } ?: return
        cached = loaded.first
        fetchedAt = Instant.ofEpochMilli(loaded.second)
    }

    private suspend fun persist(live: List<AccessibilityCloudClient.Equipment>) {
        val file = cacheFile ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = json.encodeToString(
                    PersistedCatalog(
                        fetchedAtEpochMillis = Instant.now().toEpochMilli(),
                        live = live,
                    ),
                )
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(payload)
                if (!temp.renameTo(file)) {
                    file.writeText(payload)
                    temp.delete()
                }
            }
        }
    }
}
