package com.a11yland.hissi.core

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

// Ported from the stateful suites of HissiTests/Tests/EquipmentCatalogTests.swift
// (CatalogPersistenceTests + the async SeedFavoriteBridgeTests). The cache
// scope is the runTest scope, which makes the "shared in-flight build"
// sequencing deterministic.

// Counts and answers catalog builds, one generation per call — lets tests
// distinguish "served from cache/disk" from "rebuilt".
private class BuildStub(private val generations: List<List<AccessibilityCloudClient.Equipment>?>) {
    var builds = 0
        private set

    fun next(): List<AccessibilityCloudClient.Equipment>? {
        val result = if (builds < generations.size) generations[builds] else generations.lastOrNull()
        builds += 1
        return result
    }
}

private fun equipment(id: String, isWorking: Boolean?) = AccessibilityCloudClient.Equipment(
    id = id,
    stationId = "900000001",
    stationName = "S Teststadt",
    stationNetworks = setOf(TransitNetwork.SBahn),
    description = "Straße ⟷ Bahnsteig",
    isWorking = isWorking,
    lastUpdateEpochMillis = 1_750_000_000_000,
    region = TransitRegion.Berlin,
)

private fun tempCacheFile(): File =
    File(System.getProperty("java.io.tmpdir"), "catalog-test-${UUID.randomUUID()}.json")

private fun CoroutineScope.cache(
    stub: BuildStub,
    file: File,
    ttl: Duration = Duration.ofMinutes(10),
    seed: List<SeedCatalog.Record> = emptyList(),
    fetchTargeted: suspend (List<String>) -> List<AccessibilityCloudClient.Equipment>? = { null },
) = CatalogCache(
    ttl = ttl,
    cacheFile = file,
    seed = seed,
    scope = this,
    fetchTargeted = fetchTargeted,
    buildLive = { stub.next() },
)

class CatalogPersistenceTest {
    @Test
    fun equipmentSurvivesASerializationRoundTrip() {
        val full = equipment(id = "101", isWorking = false)
        val minimal = AccessibilityCloudClient.Equipment(
            id = "7", stationId = "", stationName = "", description = "", isWorking = null,
        )
        val encoded = Json.encodeToString(listOf(full, minimal))
        val decoded = Json.decodeFromString<List<AccessibilityCloudClient.Equipment>>(encoded)
        assertEquals(listOf(full, minimal), decoded)
    }

    @Test
    fun firstLaunchHasNoSnapshotUntilABuildLands() = runTest {
        val stub = BuildStub(listOf(listOf(equipment(id = "101", isWorking = true))))
        val catalog = cache(stub, tempCacheFile())

        assertNull(catalog.snapshot())
        val built = assertNotNull(catalog.all())
        assertContentEquals(listOf("101"), built.map { it.id })
        assertContentEquals(listOf("101"), catalog.snapshot().orEmpty().map { it.id })
    }

    @Test
    fun freshPersistedCatalogServesANewInstanceWithoutABuild() = runTest {
        val file = tempCacheFile()
        val writerStub = BuildStub(listOf(listOf(equipment(id = "101", isWorking = false))))
        cache(writerStub, file).all()

        // Fresh process, same container: the snapshot answers instantly and,
        // still inside the TTL, no rebuild fires.
        val readerStub = BuildStub(listOf(null))
        val snapshot = assertNotNull(cache(readerStub, file).snapshot())
        assertContentEquals(listOf("101"), snapshot.map { it.id })
        assertEquals(false, snapshot.first().isWorking)
        assertEquals(0, readerStub.builds)
    }

    @Test
    fun staleSnapshotIsServedImmediatelyAndRevalidated() = runTest {
        val stub = BuildStub(
            listOf(
                listOf(equipment(id = "101", isWorking = true)),
                listOf(equipment(id = "101", isWorking = false)),
            ),
        )
        // ttl 0: nothing is ever fresh — every snapshot revalidates.
        val catalog = cache(stub, tempCacheFile(), ttl = Duration.ZERO)

        catalog.all()
        // Old data now, rebuild in the background …
        assertEquals(true, catalog.snapshot()?.firstOrNull()?.isWorking)
        // … and all() joins that same shared rebuild.
        val refreshed = assertNotNull(catalog.all())
        assertEquals(false, refreshed.first().isWorking)
        assertEquals(2, stub.builds)
    }

    @Test
    fun failedRebuildKeepsServingTheStaleCatalog() = runTest {
        val stub = BuildStub(listOf(listOf(equipment(id = "101", isWorking = true)), null))
        val catalog = cache(stub, tempCacheFile(), ttl = Duration.ZERO)

        catalog.all()
        val stale = assertNotNull(catalog.all())
        assertContentEquals(listOf("101"), stale.map { it.id })
    }

    @Test
    fun corruptCacheFileIsIgnored() = runTest {
        val file = tempCacheFile()
        file.writeText("nonsense")
        val catalog = cache(BuildStub(listOf(null)), file)
        assertNull(catalog.snapshot())
    }
}

// The async half of the seed-favorite bridge: resolution through the cache.
class SeedFavoriteResolutionTest {
    private fun seedRecord(id: String, number: Int?, source: String = "fasta") = SeedCatalog.Record(
        id = id, source = source, acId = null, fastaEquipmentNumber = number,
        stationNumber = null, brokenliftsStationId = null, brokenliftsIndex = null,
        stationName = "S Teststadt", description = "Straße ⟷ Bahnsteig",
        latitude = null, longitude = null, sourceName = "DB FaSta",
        organizationName = "DB", region = "berlin",
    )

    private fun liveEquipment(id: String, inventoryId: String? = null) =
        AccessibilityCloudClient.Equipment(
            id = id, inventoryId = inventoryId, stationId = "900000001",
            stationName = "S Teststadt", description = "Straße ⟷ Bahnsteig",
            isWorking = true,
        )

    @Test
    fun seedFavoriteResolvesToItsLiveRecordOnceACatalogExists() = runTest {
        val stub = BuildStub(listOf(listOf(liveEquipment(id = "456", inventoryId = "42"))))
        val catalog = cache(
            stub, tempCacheFile(),
            seed = listOf(seedRecord(id = "fasta-42", number = 42)),
        )

        catalog.all()
        val resolved = catalog.equipment(setOf("fasta-42"))
        assertEquals("456", resolved["fasta-42"]?.id)
        assertEquals(true, resolved["fasta-42"]?.isWorking)
    }

    @Test
    fun seedFavoriteWithoutAnyCatalogResolvesFromTheSeedUnknown() = runTest {
        val catalog = cache(
            BuildStub(listOf(null)), tempCacheFile(),
            seed = listOf(seedRecord(id = "fasta-42", number = 42)),
        )

        val resolved = catalog.equipment(setOf("fasta-42"))
        assertEquals("fasta-42", resolved["fasta-42"]?.id)
        assertNull(resolved["fasta-42"]?.isWorking)
    }

    // An accessibilityCloud-source seed record next to live data is neither
    // appended by the overlay nor bridged (numbers differ) — the favorite
    // must still answer from the seed instead of failing every refresh.
    @Test
    fun unbridgedSeedFavoriteNextToLiveDataFallsBackToTheSeed() = runTest {
        val stub = BuildStub(listOf(listOf(liveEquipment(id = "456", inventoryId = "99"))))
        val catalog = cache(
            stub, tempCacheFile(),
            seed = listOf(seedRecord(id = "z87ZFWJs5aB2seuYf", number = 42, source = "accessibilityCloud")),
        )

        catalog.all()
        val resolved = catalog.equipment(setOf("z87ZFWJs5aB2seuYf"))
        assertEquals("z87ZFWJs5aB2seuYf", resolved["z87ZFWJs5aB2seuYf"]?.id)
        assertNull(resolved["z87ZFWJs5aB2seuYf"]?.isWorking)
    }

    // No Swift counterpart (URLSession); here the targeted path is cheap to
    // pin down: a stale cache triggers exactly one batched fetch, and its
    // result answers by requested id.
    @Test
    fun staleCacheResolvesNumericIdsViaOneTargetedFetch() = runTest {
        val requests = mutableListOf<List<String>>()
        val catalog = CatalogCache(
            ttl = Duration.ZERO,
            cacheFile = tempCacheFile(),
            seed = emptyList(),
            scope = this,
            fetchTargeted = { ids ->
                requests.add(ids)
                listOf(equipment(id = "101", isWorking = false), equipment(id = "102", isWorking = true))
            },
            buildLive = { null },
        )
        val resolved = catalog.equipment(setOf("101", "102"))
        assertEquals(listOf(listOf("101", "102")), requests)
        assertEquals(false, resolved["101"]?.isWorking)
        assertEquals(true, resolved["102"]?.isWorking)
    }
}
