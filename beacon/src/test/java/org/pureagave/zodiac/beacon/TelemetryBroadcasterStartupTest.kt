package org.pureagave.zodiac.beacon

import android.content.Context
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * AUDIT area 4, finding 1: `start()`'s synchronous tail read
 * `wifiManager.dhcpInfo.ipAddress` unguarded (reached via `maintainTransport()`).
 * A throw there — a `WifiManager` in a bad state right after boot is a real
 * scenario, not a hypothetical — used to escape `start()` before `_running`
 * was set: no tick loop, no watchdog, the notification still says
 * "Broadcasting", the wake lock is held by a zombie, and `isRunning` reads
 * false so STOP can never reach `stopService`. Worse, the next START press
 * re-runs `start()` on the half-initialized singleton, since the
 * `if (_running.value) return` re-entry guard never fired — double `wire()`,
 * zero `unwire()` (AUDIT probe C).
 *
 * [ThrowingDhcpInfoShadowWifiManager] reproduces the throw without needing
 * real hardware in a bad DHCP state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDhcpInfoShadowWifiManager::class])
class TelemetryBroadcasterStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val noOpWiredGpsHandle =
        object : BeaconGpsHandle {
            override fun hasFineLocation(): Boolean = true

            override fun wire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) = Unit

            override fun unwire(
                onLocation: LocationListener,
                onNmea: OnNmeaMessageListener,
            ) = Unit
        }

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
    }

    @Test
    fun start_survives_a_throwing_dhcp_read_and_reaches_running() {
        // Mutation target: revert the runCatching guard around
        // `wifiManager?.dhcpInfo?.ipAddress` in maintainTransport() back to the
        // raw call — the shadow's throw then propagates out of
        // maintainTransport() -> start(), start() aborts before `_running` is
        // set, and isRunning stays false.
        gpsHandleOverride = noOpWiredGpsHandle

        TelemetryBroadcaster.start(context, micEnabled = false)

        assertTrue(
            "a throwing dhcp read must not stop the service from starting",
            TelemetryBroadcaster.isRunning.value,
        )
        val status = TelemetryBroadcaster.status.value
        assertTrue(
            "status must still show the broadcast footer (degraded targets, not a dead service); was: $status",
            status.contains("→ ${TelemetryBroadcaster.GROUP}:${TelemetryBroadcaster.PORT}"),
        )
    }

    @Test
    fun repeated_start_with_a_throwing_dhcp_read_does_not_double_wire_gps() {
        // Probe C: before the fix, the first start() aborted with `_running`
        // still false, so the re-entry guard never fired and a second START
        // press re-ran wire() -> duplicate GNSS forwarding + two odometers
        // racing persistTotal. Same mutation as above reproduces this: revert
        // the dhcp guard and wireCalls ends up at 2, not 1.
        var wireCalls = 0
        gpsHandleOverride =
            object : BeaconGpsHandle {
                override fun hasFineLocation(): Boolean = true

                override fun wire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) {
                    wireCalls++
                }

                override fun unwire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) = Unit
            }

        TelemetryBroadcaster.start(context, micEnabled = false)
        TelemetryBroadcaster.start(context, micEnabled = false)

        assertEquals("wire() must run exactly once across both start() calls", 1, wireCalls)
    }
}

/**
 * Minimal Robolectric shadow that makes `WifiManager.getDhcpInfo()` throw, to
 * reproduce a `WifiManager` in a bad post-boot state without real hardware.
 * Extends the real [ShadowWifiManager] rather than replacing it outright so
 * every other WifiManager call the test harness relies on keeps working.
 */
@Implements(WifiManager::class)
class ThrowingDhcpInfoShadowWifiManager : ShadowWifiManager() {
    @Implementation
    override fun getDhcpInfo(): DhcpInfo = error("beacon test: simulated dhcpInfo read failure")
}
