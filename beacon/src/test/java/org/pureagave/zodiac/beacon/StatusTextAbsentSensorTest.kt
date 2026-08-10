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

/**
 * `statusText()` is private, but it is reachable through the public [status]
 * flow: `start()` computes and publishes it immediately (before any tick),
 * so a fresh start on a device with no compass/light sensor registered
 * (Robolectric's shadow `SensorManager` returns null from `getDefaultSensor`
 * unless a shadow sensor is configured, so neither fires) is a real exercise
 * of the absent-sensor path end to end (AUDIT-2026-08-09 C1).
 */
@RunWith(RobolectricTestRunner::class)
class StatusTextAbsentSensorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        TelemetryBroadcaster.stop()
        gpsHandleOverride = null
    }

    @Test
    fun status_text_shows_absent_sensors_distinctly_not_as_zero() {
        // Mutation: revert headingDeg/luxValue to non-nullable `0.0` defaults —
        // this test would then see "HDG    0°" / "LIGHT  0 lx", which reads as
        // a real reading rather than "no sensor", the exact shipped bug.
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

        val status = TelemetryBroadcaster.status.value
        assertTrue("status must show absent heading distinctly; was: $status", status.contains("HDG    --"))
        assertTrue("status must show absent light distinctly; was: $status", status.contains("LIGHT  -- lx (no sensor)"))
        assertFalse("status must never print a bare 0 heading for an absent sensor; was: $status", status.contains("HDG    0°"))
        assertFalse("status must never print a bare 0 lx for an absent sensor; was: $status", status.contains("LIGHT  0 lx"))
    }
}
