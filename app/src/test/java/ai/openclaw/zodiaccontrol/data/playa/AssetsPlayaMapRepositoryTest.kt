package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private class ThrowingReader(
        private val error: Throwable,
    ) : PlayaAssetReader {
        override fun read(
            year: String,
            name: String,
        ): String = throw error
    }
}
