package org.pureagave.zodiac.beacon

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AUDIT area 4, finding 3: the `$ZAUD` capture loop used to end permanently on
 * one bad read (`if (n <= 0) break`) with no `audioActive = false` — the
 * on-device MIC line froze on the last-seen "rms" reading forever, lying that
 * the channel was still live. [MicSource] is the seam that lets
 * [TelemetryBroadcaster.runAudioCapture] be driven directly with a scripted
 * fake, bypassing `start()`'s infinite tick+watchdog loops (which can't be
 * safely drained under `runTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioCaptureLoopTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
        micSourceOverride = null
    }

    /** Scripted [MicSource]: `reads[i]` is what the i-th `read()` call returns
     * (defaulting to -1, a hard error, once the script runs out); `restartResults[i]`
     * is what the i-th `restart()` call returns (defaulting to false, exhausted). */
    private class ScriptedMicSource(
        private val reads: List<Int>,
        private val restartResults: List<Boolean> = emptyList(),
    ) : MicSource {
        var readCalls = 0
            private set
        var closeCalls = 0
            private set
        private var restartCallIndex = 0

        override fun read(buf: ShortArray): Int {
            val n = reads.getOrElse(readCalls) { -1 }
            readCalls++
            if (n > 0) buf.fill(1000, 0, n)
            return n
        }

        override fun restart(): Boolean {
            val result = restartResults.getOrElse(restartCallIndex) { false }
            restartCallIndex++
            return result
        }

        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun a_hard_read_error_clears_audioActive_once_restarts_are_exhausted() =
        runTest {
            // One good frame, then a hard error forever; restart() never
            // recovers (budget exhausted). Mutation target: delete the
            // `finally { audioActive = false }` (and/or the recovery-exhausted
            // break) in runAudioCapture() -> audioActive stays true, frozen on
            // the last-seen "rms" reading -- the exact shipped status-lie bug.
            val source = ScriptedMicSource(reads = listOf(10))
            TelemetryBroadcaster.audioActive = true // the state startAudioCapture leaves before launching

            TelemetryBroadcaster.runAudioCapture(source)

            assertFalse(
                "audioActive must drop the instant the mic channel gives up, not stay frozen 'on'",
                TelemetryBroadcaster.audioActive,
            )
            assertEquals("the source must be closed once capture ends", 1, source.closeCalls)
        }

    @Test
    fun a_recovered_read_error_lets_capture_continue_past_the_failure() =
        runTest {
            // frame, hard error (recovers), frame, hard error (never recovers,
            // budget exhausted) -> 4 read() calls total. Mutation target: delete
            // the restart() call / treat n<0 as an immediate break -> the loop
            // ends after the first error, readCalls drops to 2.
            val source = ScriptedMicSource(reads = listOf(10, -1, 10, -1), restartResults = listOf(true))

            TelemetryBroadcaster.runAudioCapture(source)

            assertEquals(
                "expected capture to read past the first (recovered) failure and process a " +
                    "second frame before giving up on the second, unrecovered failure",
                4,
                source.readCalls,
            )
        }

    @Test
    fun a_guarded_startRecording_failure_does_not_abort_start_and_reports_mic_off() {
        // Plan[0]'s audio half of the P1 startup fix: a guarded startRecording()
        // failure (simulated here via micSourceOverride returning null) must
        // behave exactly like "permission not granted" -- skip the channel,
        // keep every other channel broadcasting, and say so on the readout.
        // Mutation target: remove the `?: return` null-guard on acquireMicSource()
        // in startAudioCapture() -> NPE on `micSource = source`, start() aborts.
        gpsHandleOverride =
            object : BeaconGpsHandle {
                override fun hasFineLocation(): Boolean = false

                override fun wire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) = error("must not be called without permission")

                override fun unwire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) = Unit
            }
        micSourceOverride = { null }
        // RECORD_AUDIO is a dangerous permission — Robolectric does not
        // auto-grant it just because the manifest declares it (unlike the
        // manifest itself, which is enough for a normal permission). Without
        // this, startAudioCapture() would return before ever reaching
        // acquireMicSource(), and the test would pass for the wrong reason.
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        TelemetryBroadcaster.start(context, micEnabled = true)

        assertTrue("a guarded mic startRecording() failure must not stop the service from starting", TelemetryBroadcaster.isRunning.value)
        assertFalse("audioActive must be false when the mic never actually started", TelemetryBroadcaster.audioActive)
        val status = TelemetryBroadcaster.status.value
        assertTrue("status must show the mic as off, not silently show 'rms'; was: $status", status.contains("MIC    off"))
    }
}
