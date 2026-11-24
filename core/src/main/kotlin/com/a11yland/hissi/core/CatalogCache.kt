package com.a11yland.hissi.core

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val stations: List<SeedCatalog.Station> = emptyList(),
    // Format-1 seed ids → live ids (SeedCatalog.parseLegacyIds).
    private val legacyIds: Map<String, String> = emptyMap(),
    // Builds run on this scope so a cancelled caller (every search keystroke
    // cancels its predecessor) does not abort the shared build.
    private val scope: CoroutineScope,
    // The targeted where[id][in] fetch (TransitApi::fetchEquipment).
    private val fetchTargeted: suspend (List<String>) -> List<AccessibilityCloudClient.Equipment>?,
    // The live fetch. Returns the joined live catalog WITHOUT the seed
    // overlay — the overlay is applied on every load.
    private val buildLive: suspend () -> List<AccessibilityCloudClient.Equipment>?,
    // Every VRR elevator with its status (VrrApi::fetchAll); null when the
    // request fails — or while the source is switched off.
    private val fetchVrr: suspend () -> List<AccessibilityCloudClient.Equipment>? = { null },
    // Which ids are part of the app (feature toggles). Applied to whatever
    // comes back live or from disk, so switching a source off also hides
    // what an earlier run persisted.
    private val includes: (String) -> Boolean = { true },
) {
    private val mutex = Mutex()
    private var cached: List<AccessibilityCloudClient.Equipment> = emptyList()
    private var fetchedAt: Instant? = null
    private var inFlight: Deferred<List<AccessibilityCloudClient.Equipment>?>? = null
    private var diskLoaded = false

    // Whether the cached catalog holds live VRR records — a build during a
    // VRR outage caches only their status-unknown seed stand-ins, which
    // must not answer for ten minutes as if they were fresh.
    private var cachedHasVrr = false

    @Serializable
    private data class PersistedCatalog(
        val fetchedAtEpochMillis: Long,
        val live: List<AccessibilityCloudClient.Equipment>,
    )

    // Instant snapshot for stale-while-revalidate UIs (the search): memory or
    // persisted catalog of any age, null when neither exists (first launch).
    // When the snapshot is stale the shared rebuild is kicked off in the
    // background; callers show the stale names/statuses now and re-render off
    // all() when the build lands. Never falls back to the seed itself — the
    // callers that want a preview (search, nearby) overlay the seed on their
    // own.
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
            EquipmentCatalog.overlaid(live = emptyList(), seed = seed, stations = stations)
        }
        return seedOnly.ifEmpty { null }
    }

    // Resolves favorites in one batch, keyed by the requested id. A
    // still-fresh catalog cache answers for free; otherwise one targeted
    // request covers all numeric ids — the full catalog build stays
    // search-only. Non-numeric ids are favorites created from a format-1
    // seed preview: they bridge to their live id via the seed's legacyIds —
    // the caller sees the live record, adopts its id (StatusMerge) and the
    // favorite refreshes directly from then on. Ids missing from the result
    // keep their previous values upstream.
    suspend fun equipment(ids: Set<String>): Map<String, AccessibilityCloudClient.Equipment> {
        if (ids.isEmpty()) return emptyMap()
        val (snapshot, fresh, hasVrr) = mutex.withLock {
            loadDiskIfNeeded()
            Triple(cached, fetchedAt?.let { Duration.between(it, Instant.now()) < ttl } ?: false, cachedHasVrr)
        }
        val wantsVrr = ids.any { ElevatorSource.of(it) == ElevatorSource.Vrr }
        val bridged = EquipmentCatalog.bridgedLiveIds(ids, legacyIds)
        val resolved: List<AccessibilityCloudClient.Equipment>
        if (snapshot.isNotEmpty() && fresh) {
            // A fresh catalog built while VRR didn't answer holds only seed
            // stand-ins for VRR: ask again rather than serving "unbekannt"
            // until the cache expires.
            val vrr = if (wantsVrr && !hasVrr) fetchVrr()?.filter { includes(it.id) }.orEmpty() else emptyList()
            resolved = if (vrr.isEmpty()) {
                snapshot
            } else {
                snapshot.filter { ElevatorSource.of(it.id) != ElevatorSource.Vrr } + EquipmentCatalog.overlaid(
                    live = vrr,
                    seed = seed.filter { ElevatorSource.of(it.id) == ElevatorSource.Vrr },
                    stations = stations,
                )
            }
        } else {
            // Each source by its own means, at once: accessibility.cloud's
            // targeted request over exactly the asked ids, VRR's whole list
            // (its only cheap form — and it names no favorite) when a VRR id
            // is asked for.
            val numeric = (ids.filter { ElevatorSource.of(it) == ElevatorSource.AccessibilityCloud } + bridged.values)
                .distinct().sorted()
            val live = coroutineScope {
                val accessibilityCloud = async { if (numeric.isEmpty()) emptyList() else fetchTargeted(numeric) }
                val vrr = async { if (wantsVrr) fetchVrr() else null }
                (accessibilityCloud.await().orEmpty() + vrr.await().orEmpty()).filter { includes(it.id) }
            }
            // A failed fetch degrades to the seed, per source: every
            // favorite the seed knows still answers — status unknown, never
            // a false "working".
            resolved = EquipmentCatalog.overlaid(live = live, seed = seed, stations = stations)
        }
        val byId = LinkedHashMap<String, AccessibilityCloudClient.Equipment>(resolved.size)
        for (record in resolved) byId.putIfAbsent(record.id, record)
        val result = mutableMapOf<String, AccessibilityCloudClient.Equipment>()
        for (id in ids) {
            val hit = bridged[id]?.let(byId::get) ?: byId[id]
            if (hit != null) result[id] = hit
        }
        return result
    }

    // Callers must hold the mutex. The build itself runs unstructured on the
    // cache's scope; a completed build is always safe to cache.
    private fun startBuildLocked(): Deferred<List<AccessibilityCloudClient.Equipment>?> {
        inFlight?.let { return it }
        val build = scope.async {
            // Both sources at once; VRR is one quick request.
            val vrr = async { fetchVrr() }
            finishBuild(buildLive(), vrr.await())
        }
        inFlight = build
        return build
    }

    // accessibility.cloud decides whether there is a catalog at all: without
    // it the build fails as before and the cache stays (a catalog of
    // status-unknown seed records must not count as fresh). A VRR outage
    // only leaves the VRR elevators unknown — the overlay stands their seed
    // records in.
    private suspend fun finishBuild(
        accessibilityCloud: List<AccessibilityCloudClient.Equipment>?,
        vrr: List<AccessibilityCloudClient.Equipment>?,
    ): List<AccessibilityCloudClient.Equipment>? = mutex.withLock {
        inFlight = null
        if (accessibilityCloud == null) return null
        val live = (accessibilityCloud + vrr.orEmpty()).filter { includes(it.id) }
        cached = EquipmentCatalog.overlaid(live = live, seed = seed, stations = stations)
        cachedHasVrr = live.any { ElevatorSource.of(it.id) == ElevatorSource.Vrr }
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
            val live = persisted.live.filter { includes(it.id) }
            if (live.isEmpty()) return@withContext null
            Triple(
                EquipmentCatalog.overlaid(live = live, seed = seed, stations = stations),
                persisted.fetchedAtEpochMillis,
                live.any { ElevatorSource.of(it.id) == ElevatorSource.Vrr },
            )
        } ?: return
        cached = loaded.first
        fetchedAt = Instant.ofEpochMilli(loaded.second)
        cachedHasVrr = loaded.third
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
