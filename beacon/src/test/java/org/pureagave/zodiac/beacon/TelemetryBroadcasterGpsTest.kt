package org.pureagave.zodiac.beacon

import android.content.Context
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [TelemetryBroadcaster.start] against a real (Robolectric) Context —
 * the shipped bug (AUDIT-2026-08-09 B5) was an unguarded `requestLocationUpdates`
 * behind `@SuppressLint("MissingPermission")`, throwing `SecurityException`
 * straight out of `onCreate`/`onStartCommand` and crash-looping under
 * `START_STICKY`. [gpsHandleOverride] is the seam that lets this be reproduced
 * and fixed without a real `LocationManager`.
 *
 * [TelemetryBroadcaster] is a process `object` singleton — every test here must
 * call [TelemetryBroadcaster.stop] and clear the override in `@After`, or state
 * leaks into the next test (and the next test class).
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryBroadcasterGpsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
    }

    @Test
    fun start_survives_a_security_exception_and_reports_gps_off() {
        // Permission looks granted (hasFineLocation = true) but the actual
        // registration call throws — the race this seam exists to guard, since
        // permission can be revoked between the check and the call. Mutation
        // target: remove the try/catch around handle.wire() in start() → this
        // exception propagates out of start() and the caller (the service)
        // crash-loops, which is the exact shipped bug.
        gpsHandleOverride =
            object : BeaconGpsHandle {
                override fun hasFineLocation(): Boolean = true

                override fun wire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) {
                    throw SecurityException("permission revoked mid-registration")
                }

                override fun unwire(
                    onLocation: LocationListener,
                    onNmea: OnNmeaMessageListener,
                ) = Unit
            }

        TelemetryBroadcaster.start(context, micEnabled = false)

        assertTrue("a GPS wiring failure must not stop the service from starting", TelemetryBroadcaster.isRunning.value)
        val status = TelemetryBroadcaster.status.value
        assertTrue("status must say GPS is off, not silently show 'acquiring'; was: $status", status.contains("GPS OFF"))
    }

    @Test
    fun start_with_no_location_permission_skips_wiring_and_reports_gps_off() {
        // The other route to "no fix": permission was never held, so wire() is
        // never even attempted. Distinct from the exception-path test above —
        // covers the `if (handle.hasFineLocation())` guard rather than the
        // try/catch.
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

        TelemetryBroadcaster.start(context, micEnabled = false)

        assertTrue(TelemetryBroadcaster.isRunning.value)
        assertTrue(TelemetryBroadcaster.status.value.contains("GPS OFF"))
    }
}
