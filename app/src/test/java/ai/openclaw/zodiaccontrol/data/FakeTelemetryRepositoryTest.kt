package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.Telemetry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTelemetryRepositoryTest {
    @Test
    fun first_emission_matches_documented_seed_values() =
        runTest {
            val first = FakeTelemetryRepository().stream().take(1).toList().single()

            // tick 0: heading = (42 + 0) % 360, speed = 22 + |0-20|, thermal = 58 + 0,
            // mode = (0/20)%3 -> DIAGNOSTIC.
            assertEquals(Telemetry(42, 42, 58, true, CockpitMode.DIAGNOSTIC), first)
        }

    @Test
    fun stream_fields_stay_within_documented_ranges() =
        runTest {
            val samples = FakeTelemetryRepository().stream().take(60).toList()

            samples.forEach { t ->
                assertTrue("heading=${t.headingDeg}", t.headingDeg in 0..359)
                // speed = 22 + abs((tick % 40) - 20) -> 22..42
                assertTrue("speed=${t.speedKph}", t.speedKph in 22..42)
                // thermal = 58 + (tick % 9) -> 58..66
                assertTrue("thermal=${t.thermalC}", t.thermalC in 58..66)
                assertTrue("link", t.linkStable)
            }
        }

    @Test
    fun heading_advances_by_three_degrees_per_tick() =
        runTest {
            val headings = FakeTelemetryRepository().stream().take(5).toList().map { it.headingDeg }

            assertEquals(listOf(42, 45, 48, 51, 54), headings)
        }

    @Test
    fun mode_cycles_every_twenty_ticks() =
        runTest {
            val modes = FakeTelemetryRepository().stream().take(41).toList().map { it.mode }

            // Ticks 0-19 DIAGNOSTIC, 20-39 DRIVE, 40 wraps into COMBAT.
            assertEquals(CockpitMode.DIAGNOSTIC, modes[0])
            assertEquals(CockpitMode.DIAGNOSTIC, modes[19])
            assertEquals(CockpitMode.DRIVE, modes[20])
            assertEquals(CockpitMode.DRIVE, modes[39])
            assertEquals(CockpitMode.COMBAT, modes[40])
        }
}
