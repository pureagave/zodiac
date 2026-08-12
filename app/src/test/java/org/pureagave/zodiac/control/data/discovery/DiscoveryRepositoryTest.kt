package org.pureagave.zodiac.control.data.discovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import java.io.File
import java.io.IOException

/**
 * The offline-first discovery cache: on start it must serve the disk cache
 * immediately, and a failed/empty background refresh must never destroy the last
 * good dataset. Uses `runCurrent()` (never `advanceUntilIdle()` — the repo's
 * refresh loop has a ~daily delay that would spin forever) + a real temp dir so
 * the JSON save/load path is exercised, not mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val year = 2025

    private fun poi(
        uid: String,
        name: String,
        kind: PoiKind = PoiKind.ART,
        point: PlayaPoint? = PlayaPoint(1.0, 2.0),
    ) = PlayaPoi(uid = uid, name = name, kind = kind, point = point, subtitle = "sub-$name")

    private class ListSource(private val pois: List<PlayaPoi>) : DiscoverySource {
        override suspend fun fetch(year: Int): List<PlayaPoi> = pois
    }

    private class ThrowingSource : DiscoverySource {
        override suspend fun fetch(year: Int): List<PlayaPoi> = throw IOException("offline")
    }

    private class SuspendForeverSource : DiscoverySource {
        override suspend fun fetch(year: Int): List<PlayaPoi> = awaitCancellation()
    }

    /** Write a cache file in the exact on-disk format the repo's saveCache emits. */
    private fun writeCache(pois: List<PlayaPoi>) {
        val arr = JSONArray()
        pois.forEach { p ->
            val o = JSONObject()
            o.put("uid", p.uid)
            o.put("name", p.name)
            o.put("kind", p.kind.name)
            o.put("subtitle", p.subtitle)
            p.point?.let {
                o.put("eastM", it.eastM)
                o.put("northM", it.northM)
            }
            arr.put(o)
        }
        File(tmp.root, "discovery_$year.json").writeText(arr.toString())
    }

    /** Serialise POIs into the same JSON string the bundled seed asset carries. */
    private fun seedJsonOf(pois: List<PlayaPoi>): String {
        val arr = JSONArray()
        pois.forEach { p ->
            val o = JSONObject()
            o.put("uid", p.uid)
            o.put("name", p.name)
            o.put("kind", p.kind.name)
            o.put("subtitle", p.subtitle)
            p.point?.let {
                o.put("eastM", it.eastM)
                o.put("northM", it.northM)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    @Test
    fun serves_the_bundled_seed_when_there_is_no_disk_cache() =
        runTest {
            // The playa nightmare: a device that has never reached the API, and is
            // offline. With no seed the overlay is blank; the seed must boot it
            // fully. SuspendForeverSource so the live refresh can't mask the seed.
            val seed = listOf(poi("s1", "Seed Art"), poi("s2", "Seed Camp", PoiKind.CAMP))
            val repo =
                DiscoveryRepository(
                    SuspendForeverSource(),
                    backgroundScope,
                    tmp.root,
                    year,
                    seedJson = { seedJsonOf(seed) },
                )
            runCurrent()
            assertEquals(listOf("s1", "s2"), repo.pois.value.map { it.uid })
        }

    @Test
    fun disk_cache_wins_over_the_bundled_seed() =
        runTest {
            // A device that has fetched before serves its (fresher) cache, not the
            // frozen seed baked into the APK.
            writeCache(listOf(poi("c1", "Cached")))
            val repo =
                DiscoveryRepository(
                    SuspendForeverSource(),
                    backgroundScope,
                    tmp.root,
                    year,
                    seedJson = { seedJsonOf(listOf(poi("s1", "Seed"))) },
                )
            runCurrent()
            assertEquals(listOf("c1"), repo.pois.value.map { it.uid })
        }

    @Test
    fun a_successful_fetch_replaces_the_bundled_seed() =
        runTest {
            // Online path: the seed is only a floor — a live fetch overwrites it.
            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("f1", "Fresh"))),
                    backgroundScope,
                    tmp.root,
                    year,
                    seedJson = { seedJsonOf(listOf(poi("s1", "Seed"))) },
                )
            runCurrent()
            assertEquals(listOf("f1"), repo.pois.value.map { it.uid })
        }

    @Test
    fun a_malformed_seed_is_ignored_not_fatal() =
        runTest {
            // A corrupt bundled asset must degrade to "no seed", never crash the
            // cold start — the same fail-open discipline as a corrupt cache file.
            val repo =
                DiscoveryRepository(
                    SuspendForeverSource(),
                    backgroundScope,
                    tmp.root,
                    year,
                    seedJson = { "{ this is not valid json" },
                )
            runCurrent()
            assertEquals(emptyList<String>(), repo.pois.value.map { it.uid })
        }

    @Test
    fun serves_disk_cache_before_the_first_fetch_completes() =
        runTest {
            val cached = listOf(poi("a", "Alpha"), poi("b", "Bravo", PoiKind.CAMP, point = null))
            writeCache(cached)

            val repo = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()

            assertEquals(listOf("a", "b"), repo.pois.value.map { it.uid })
            assertEquals(PoiKind.CAMP, repo.pois.value[1].kind)
            assertNull("a null-point camp must round-trip", repo.pois.value[1].point)
        }

    @Test
    fun successful_fetch_updates_state_and_persists_for_reopen() =
        runTest {
            val fresh = listOf(poi("x", "Xray"), poi("y", "Yankee"))
            val repo = DiscoveryRepository(ListSource(fresh), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("x", "y"), repo.pois.value.map { it.uid })

            // A second repo on the same dir must serve the persisted cache — this
            // exercises the real saveCache -> loadCache round-trip.
            val reopened = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("x", "y"), reopened.pois.value.map { it.uid })
        }

    @Test
    fun failed_fetch_keeps_the_cached_list() =
        runTest {
            writeCache(listOf(poi("a", "Alpha")))
            val repo = DiscoveryRepository(ThrowingSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("a"), repo.pois.value.map { it.uid })
        }

    @Test
    fun empty_fetch_does_not_clobber_the_cache() =
        runTest {
            writeCache(listOf(poi("a", "Alpha")))
            val repo = DiscoveryRepository(ListSource(emptyList()), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("a"), repo.pois.value.map { it.uid })
        }

    @Test
    fun corrupt_cache_does_not_crash_and_refresh_recovers() =
        runTest {
            File(tmp.root, "discovery_$year.json").writeText("{ not valid json ][")
            val repo = DiscoveryRepository(ListSource(listOf(poi("z", "Zulu"))), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("z"), repo.pois.value.map { it.uid })
        }

    @Test
    fun unknown_kind_in_cache_defaults_to_art() =
        runTest {
            File(tmp.root, "discovery_$year.json").writeText(
                """[{"uid":"q","name":"Quebec","kind":"PLAZA","subtitle":"s","eastM":1.0,"northM":2.0}]""",
            )
            val repo = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(PoiKind.ART, repo.pois.value.single().kind)
        }

    @Test
    fun art_only_fetch_does_not_destroy_cached_camps() =
        runTest {
            // Simulates the C9 partial-degradation scenario: a prior good fetch
            // cached both art and camps, then a later fetch's camp records all
            // fail to parse (empty, not an exception) while art comes through.
            writeCache(listOf(poi("a", "Alpha", PoiKind.ART), poi("b", "Bravo", PoiKind.CAMP)))
            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("a2", "Alpha Two", PoiKind.ART))),
                    backgroundScope,
                    tmp.root,
                    year,
                )
            runCurrent()

            val ids = repo.pois.value.map { it.uid }.toSet()
            assertEquals(setOf("a2", "b"), ids)
        }

    @Test
    fun camp_only_fetch_does_not_destroy_cached_art() =
        runTest {
            writeCache(listOf(poi("a", "Alpha", PoiKind.ART), poi("b", "Bravo", PoiKind.CAMP)))
            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("b2", "Bravo Two", PoiKind.CAMP))),
                    backgroundScope,
                    tmp.root,
                    year,
                )
            runCurrent()

            val ids = repo.pois.value.map { it.uid }.toSet()
            assertEquals(setOf("a", "b2"), ids)
        }

    @Test
    fun art_only_fetch_persists_the_merged_set_not_just_the_fresh_art() =
        runTest {
            // The merge must actually hit disk, not just the in-memory StateFlow --
            // otherwise the next cold start loses the camps again.
            writeCache(listOf(poi("a", "Alpha", PoiKind.ART), poi("b", "Bravo", PoiKind.CAMP)))
            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("a2", "Alpha Two", PoiKind.ART))),
                    backgroundScope,
                    tmp.root,
                    year,
                )
            runCurrent()

            val reopened = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(setOf("a2", "b"), reopened.pois.value.map { it.uid }.toSet())
        }

    @Test
    fun cache_round_trips_unicode_in_artist_names() =
        runTest {
            val unicodeName = "火星人 — Skål! 🔥"
            val fresh = listOf(poi("u1", unicodeName))
            val repo = DiscoveryRepository(ListSource(fresh), backgroundScope, tmp.root, year)
            runCurrent()

            val reopened = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(unicodeName, reopened.pois.value.single().name)
        }

    @Test
    fun a_torn_write_leaves_the_previous_good_cache_readable() =
        runTest {
            // Write a real good cache first, exactly as a prior successful launch
            // would have.
            writeCache(listOf(poi("good", "Good Data")))

            // Simulate a power cut mid-write: the CacheWriter seam dies partway
            // through writing the .tmp file, before the atomic rename ever runs.
            val dyingWriter =
                CacheWriter { target, _ ->
                    target.writeText("{ this is not even close to valid json")
                    throw IOException("power cut mid-write")
                }
            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("new", "New Data"))),
                    backgroundScope,
                    tmp.root,
                    year,
                    cacheWriter = dyingWriter,
                )
            runCurrent()

            // The in-memory state still reflects the fetch (that part is fine --
            // it's the on-disk cache that must survive the crash).
            assertEquals(listOf("new"), repo.pois.value.map { it.uid })

            // But the real cache file on disk was never touched by the failed
            // write -- rename never ran -- so it must still hold the old good data.
            val reopened = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(
                "a torn write must not corrupt the previously-persisted cache",
                listOf("good"),
                reopened.pois.value.map { it.uid },
            )
        }

    @Test
    fun a_stray_tmp_file_from_a_real_crash_does_not_wedge_the_next_healthy_write() =
        runTest {
            // A genuine power cut kills the process before any Kotlin catch block
            // gets to run, so the .tmp file is left behind with no cleanup --
            // unlike the CacheWriter-seam test above, where DiscoveryRepository's
            // own catch still executes and deletes it. Simulate that directly by
            // dropping a stray .tmp file on disk with no repository involved.
            writeCache(listOf(poi("good", "Good Data")))
            File(tmp.root, "discovery_$year.json.tmp").writeText("garbage, never renamed")

            val repo =
                DiscoveryRepository(
                    ListSource(listOf(poi("recovered", "Recovered Data"))),
                    backgroundScope,
                    tmp.root,
                    year,
                )
            runCurrent()
            assertEquals(listOf("recovered"), repo.pois.value.map { it.uid })

            val reopened = DiscoveryRepository(SuspendForeverSource(), backgroundScope, tmp.root, year)
            runCurrent()
            assertEquals(listOf("recovered"), reopened.pois.value.map { it.uid })
        }
}
