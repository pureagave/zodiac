package org.pureagave.zodiac.beacon

import android.content.Context
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * AUDIT area 4: `updateFixHealth()` used to write `fixQuality`/`satellites`
 * with no freshness clock, so once the last GGA arrived, `$ZBCN` and the
 * status readout reported that "healthy fix, N sats" forever — even after the
 * GNSS chip went silent. `BeaconNetTest` covers `reportedFixHealth`'s pure
 * boundary logic; this wires it end-to-end through a real GGA and a real
 * elapsed-time advance.
 *
 * `statusText()` is `internal` specifically so this test can call it directly
 * rather than draining the tick loop under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TelemetryBroadcasterFixHealthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var capturedNmea: OnNmeaMessageListener? = null

    private val capturingGpsHandle =
        object : BeaconGpsHandle {
            override fun hasFineLocation(): Boolean = true

            override fun wire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) {
                capturedNmea = onNmea
            }

            override fun unwire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) = Unit
        }

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
        capturedNmea = null
    }

    @Test
    fun a_fresh_gga_reports_live_then_goes_stale_after_silence() {
        // Mutation target: revert updateFixHealth() to not stamp lastGgaAtMs
        // (or revert the $ZBCN/statusText call sites to raw fixQuality/
        // satellites instead of BeaconNet.reportedFixHealth(...)) -- the
        // post-advance status keeps showing "q1/8 sat" with no stale suffix,
        // and this test goes red.
        gpsHandleOverride = capturingGpsHandle
        TelemetryBroadcaster.start(context, micEnabled = false)
        val onNmea = capturedNmea ?: error("wire() never captured the NMEA listener")

        onNmea.onNmeaMessage("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47", 0L)

        val freshStatus = TelemetryBroadcaster.statusText()
        assertTrue("expected a live q1/8 fix; was: $freshStatus", freshStatus.contains("q1/8 sat"))
        assertFalse("must not already show stale; was: $freshStatus", freshStatus.contains("(stale"))

        // FIX_STALE_MS is 10_000L (private to TelemetryBroadcaster); advance
        // comfortably past it with no further GGA arriving.
        ShadowSystemClock.advanceBy(Duration.ofMillis(STALE_ADVANCE_MS))

        val staleStatus = TelemetryBroadcaster.statusText()
        assertTrue("expected a zeroed q0/0 fix once stale; was: $staleStatus", staleStatus.contains("q0/0 sat"))
        assertTrue("expected a stale annotation; was: $staleStatus", staleStatus.contains("(stale"))
    }

    private companion object {
        const val STALE_ADVANCE_MS = 11_000L
    }
}
