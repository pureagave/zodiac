package org.pureagave.zodiac.beacon

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A boot start must restore whatever the operator last explicitly chose, and in
 * the absence of any choice it must broadcast anyway — the beacon is a power-on
 * appliance and, once mounted on the vehicle, nobody can reach it to press START
 * (changed 2026-08-11). Never without [EXTRA_FROM_BACKGROUND], which is the
 * contract B5's [safeForegroundTypes] relies on to avoid requesting the
 * microphone type on a background start (AUDIT-2026-08-09 B3).
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun setAutoStart(enabled: Boolean) {
        application
            .getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.PREF_AUTO_START, enabled)
            .apply()
    }

    @Test
    fun boot_with_autostart_enabled_starts_the_foreground_service() {
        // Mutation target: drop the putExtra(EXTRA_FROM_BACKGROUND, true) call.
        setAutoStart(true)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(application).nextStartedService
        assertNotNull("expected the service to be started", started)
        assertEquals(TelemetryService::class.java.name, started!!.component?.className)
        assertTrue(
            "boot start must carry EXTRA_FROM_BACKGROUND = true",
            started.getBooleanExtra(EXTRA_FROM_BACKGROUND, false),
        )
    }

    @Test
    fun boot_with_no_stored_preference_still_broadcasts() {
        // The appliance case, and the reason the default was inverted: a fresh
        // install, a factory reset, or a phone nobody has ever pressed START on.
        // Mounted on the vehicle it cannot be reached, so power-on has to be
        // enough. Mutation target: flip AUTO_START_DEFAULT back to false.
        // Cleared explicitly rather than relying on test isolation — a sibling
        // test writes this very key, and a default-sensitive test that silently
        // reads someone else's value proves nothing.
        application
            .getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(application).nextStartedService
        assertNotNull("an unconfigured beacon must still broadcast on boot", started)
        assertEquals(TelemetryService::class.java.name, started!!.component?.className)
        assertTrue(started.getBooleanExtra(EXTRA_FROM_BACKGROUND, false))
    }

    @Test
    fun boot_after_an_explicit_stop_starts_nothing() {
        // The STOP button is still a real stop, and still survives a reboot —
        // that property is what makes flipping the default safe.
        // Mutation target: delete the pref check entirely.
        setAutoStart(false)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun unrelated_action_starts_nothing() {
        // Mutation target: delete the action check — any broadcast this manifest
        // entry happened to also receive would start the service.
        setAutoStart(true)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun package_replaced_restarts_the_service() {
        // A sideloaded update kills the running service same as a reboot does.
        setAutoStart(true)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val started = shadowOf(application).nextStartedService
        assertNotNull(started)
        assertEquals(TelemetryService::class.java.name, started!!.component?.className)
        assertTrue(started.getBooleanExtra(EXTRA_FROM_BACKGROUND, false))
    }
}
