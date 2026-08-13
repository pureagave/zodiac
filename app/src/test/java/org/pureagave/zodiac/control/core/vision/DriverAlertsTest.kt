package org.pureagave.zodiac.control.core.vision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The operator that decides whether the driver is being told to brake.
 *
 * Two failures live here and neither is visible without virtual time: a
 * chattering collision flag strobing the warning, and — the opposite — a
 * warning that never clears because a passed hazard produces no further frames
 * to re-evaluate on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriverAlertsTest {
    private fun t(
        az: Float,
        collision: Boolean,
    ) = DriverThreat(relAzDeg = az, size = 0.5f, collision = collision)

    private val driving = 20f

    @Test
    fun a_closing_contact_ahead_raises_the_warning_on_the_very_first_frame() =
        runTest {
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            assertEquals("no confirmation delay — that costs the moment it exists for", listOf(true), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun a_chattering_flag_does_not_strobe_the_warning() =
        runTest {
            // Ten seconds of 8 fps with the edge box flipping the flag every
            // other frame. Without the latch this emits dozens of times.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            repeat(80) { frame ->
                frames.emit(listOf(t(az = 5f, collision = frame % 2 == 0)))
                advanceTimeBy(125)
                runCurrent()
            }
            assertEquals("one steady warning, not a flicker", listOf(true), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun the_warning_clears_itself_when_the_feed_goes_quiet() =
        runTest {
            // The failure this catches: a hazard passes, the edge box stops
            // flagging it, no further frames arrive — and the last "! BRAKE !"
            // stays on screen until some unrelated contact happens along.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            assertEquals(listOf(true), seen.map { it.brake })
            advanceTimeBy(AlarmLatch.DEFAULT_HOLD_MS + 1)
            runCurrent()
            assertEquals("it must clear on its own, with no further frames", listOf(true, false), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun a_later_frame_supersedes_a_pending_clear() =
        runTest {
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            advanceTimeBy(AlarmLatch.DEFAULT_HOLD_MS - 100)
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals("the re-trigger pushed the expiry out", listOf(true), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun a_quiet_road_never_raises_anything() =
        runTest {
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            repeat(20) {
                frames.emit(listOf(t(az = 5f, collision = false), t(az = -40f, collision = false)))
                advanceTimeBy(125)
                runCurrent()
            }
            assertEquals(listOf(false), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun a_collision_astern_never_advises_braking() =
        runTest {
            // Braking puts the vehicle further into a rear contact's path.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 175f, collision = true)))
            runCurrent()
            assertEquals(listOf(false), seen.map { it.brake })
            assertEquals("but it must still be called out", listOf(true), seen.map { it.checkRear })
            job.cancel()
        }

    @Test
    fun the_speed_gate_is_read_per_frame_so_slowing_to_a_stop_silences_it() =
        runTest {
            // People walk up to a parked art car deliberately; every one of them
            // is a constant-bearing looming track. The gate has to follow the
            // vehicle actually stopping, not the speed captured at subscribe.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            var speed = driving
            val job = launch { frames.driverAlerts({ speed }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            assertEquals(listOf(true), seen.map { it.brake })
            speed = 0f
            advanceTimeBy(AlarmLatch.DEFAULT_HOLD_MS + 1)
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            assertEquals("stopped: the contact still draws, the imperative goes quiet", listOf(true, false), seen.map { it.brake })
            job.cancel()
        }

    @Test
    fun the_rear_callout_is_speed_gated_too_slowing_to_a_stop_silences_it() =
        runTest {
            // Same failure as the_speed_gate_is_read_per_frame_..., mirrored
            // for the rear callout: a parked or crawling car in a crowd must
            // not keep flashing CHECK REAR at every constant-bearing bystander.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            var speed = driving
            val job = launch { frames.driverAlerts({ speed }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 175f, collision = true)))
            runCurrent()
            assertEquals(listOf(true), seen.map { it.checkRear })
            speed = 0f
            advanceTimeBy(AlarmLatch.DEFAULT_HOLD_MS + 1)
            runCurrent()
            frames.emit(listOf(t(az = 175f, collision = true)))
            runCurrent()
            assertEquals(
                "stopped: the contact still draws, the callout goes quiet",
                listOf(true, false),
                seen.map { it.checkRear },
            )
            job.cancel()
        }

    /** A latch on the test's virtual clock, so holds elapse with advanceTimeBy. */
    private fun kotlinx.coroutines.test.TestScope.latch() = AlarmLatch(nowMs = { testScheduler.currentTime })

    @Test
    fun a_chattering_rear_flag_does_not_strobe_the_rear_callout_either() =
        runTest {
            // Found on the bench 2026-08-08: brake was latched and the rear
            // callout was not, so the same noisy flag that used to strobe
            // "! BRAKE !" still strobed "! CHECK REAR !".
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            repeat(80) { frame ->
                frames.emit(listOf(t(az = 175f, collision = frame % 2 == 0)))
                advanceTimeBy(125)
                runCurrent()
            }
            assertEquals("one steady callout, not a flicker", listOf(true), seen.map { it.checkRear })
            job.cancel()
        }

    @Test
    fun the_two_alerts_are_latched_independently() =
        runTest {
            // A forward collision that resolves must not keep the rear callout
            // alive, nor vice versa — they describe different hazards.
            val frames = MutableSharedFlow<List<DriverThreat>>()
            val seen = mutableListOf<DriverAlerts>()
            val job = launch { frames.driverAlerts({ driving }, latch(), latch()).toList(seen) }
            runCurrent()
            frames.emit(listOf(t(az = 5f, collision = true)))
            runCurrent()
            advanceTimeBy(AlarmLatch.DEFAULT_HOLD_MS + 1)
            runCurrent()
            frames.emit(listOf(t(az = 175f, collision = true)))
            runCurrent()
            assertEquals(DriverAlerts(brake = false, checkRear = true), seen.last())
            job.cancel()
        }
}
