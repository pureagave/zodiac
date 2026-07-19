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
}
