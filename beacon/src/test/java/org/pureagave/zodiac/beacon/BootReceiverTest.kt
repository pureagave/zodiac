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
 * A boot start must restore whatever the operator last explicitly chose, and
 * nothing else — not a fresh install, not an unrelated broadcast, and never
 * without [EXTRA_FROM_BACKGROUND], which is the contract B5's
 * [safeForegroundTypes] relies on to avoid requesting the microphone type on a
 * background start (AUDIT-2026-08-09 B3).
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
    fun boot_with_autostart_disabled_starts_nothing() {
        // Mutation target: delete the pref check — a fresh install (PREF_AUTO_START
        // unset) or a phone whose operator's last action was STOP would
        // auto-start anyway.
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
