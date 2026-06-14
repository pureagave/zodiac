package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AssetsPlayaMapRepositoryTest {
    @Test
    fun load_with_missing_asset_emits_failed_without_throwing() =
        runTest {
            val repo =
                AssetsPlayaMapRepository(
                    reader = ThrowingReader(IOException("art.geojson not found")),
                    year = "2025",
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            // Must not propagate the IOException out of load().
            repo.load()

            val result = repo.loadResult.value
            assertTrue("expected Failed, got $result", result is MapLoadResult.Failed)
            assertEquals("art.geojson not found", (result as MapLoadResult.Failed).message)
        }

    @Test
    fun load_after_failure_does_not_latch() =
        runTest {
            // load() returns early only on Loaded; a Failed state should leave
            // the door open for a subsequent retry.
            val repo =
                AssetsPlayaMapRepository(
                    reader = ThrowingReader(IOException("transient")),
                    year = "2025",
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            repo.load()
            assertTrue(repo.loadResult.value is MapLoadResult.Failed)
            assertEquals(false, repo.loadResult.value is MapLoadResult.Loaded)
        }

    @Test
    fun load_with_valid_assets_emits_loaded_and_assembles_map() =
        runTest {
            // No binaryCache, so this exercises the real JSON parse/stitch path:
            // eight layers -> one PlayaMap -> Loaded.
            val repo =
                AssetsPlayaMapRepository(
                    reader = FakeReader(),
                    year = "2025",
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            repo.load()

            val result = repo.loadResult.value
            assertTrue("expected Loaded, got $result", result is MapLoadResult.Loaded)
            val map = (result as MapLoadResult.Loaded).map
            assertEquals("2025", map.year)
            // Layers we fed real geometry into are populated...
            assertEquals(1, map.trashFence.size)
            assertEquals(1, map.streetLines.size)
            assertEquals("Esplanade", map.streetLines[0].name)
            assertEquals(1, map.cpns.size)
            assertEquals("The Man", map.cpns[0].name)
            // ...and an empty FeatureCollection layer parses to an empty list,
            // not a failure.
            assertTrue(map.toilets.isEmpty())
        }

    @Test
    fun load_with_valid_assets_does_not_latch_into_failed() =
        runTest {
            val repo =
                AssetsPlayaMapRepository(
                    reader = FakeReader(),
                    year = "2025",
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            repo.load()

            assertFalse(repo.loadResult.value is MapLoadResult.Failed)
        }

    private class ThrowingReader(
        private val error: Throwable,
    ) : PlayaAssetReader {
        override fun read(
            year: String,
            name: String,
        ): String = throw error
    }

    /**
     * Returns minimal-but-valid GeoJSON for each layer the repository asks for.
     * A few layers carry real geometry (trash_fence, street_lines, cpns) so the
     * assembled PlayaMap has populated, assertable structure; the
     * rest are empty FeatureCollections, which parse cleanly to empty lists.
     */
    private class FakeReader : PlayaAssetReader {
        override fun read(
            year: String,
            name: String,
        ): String =
            when (name) {
                "trash_fence" -> POLYGON
                "street_lines" -> STREET_LINES
                "cpns" -> POINTS
                else -> EMPTY_COLLECTION
            }

        private companion object {
            const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

            const val POLYGON =
                """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-119.2,40.78],[-119.21,40.78],[-119.21,40.79],[-119.2,40.78]]]},
                   "properties":{}}
                ]}
                """

            const val STREET_LINES =
                """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.205,40.785]]},
                   "properties":{"name":"Esplanade","width":"30","type":"arc"}}
                ]}
                """

            const val POINTS =
                """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","geometry":{"type":"Point","coordinates":[-119.203,40.787]},
                   "properties":{"NAME":"The Man","TYPE":"CPN"}}
                ]}
                """
        }
    }
}
