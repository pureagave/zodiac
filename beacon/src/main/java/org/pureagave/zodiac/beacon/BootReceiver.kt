package org.pureagave.zodiac.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restores the operator's last expressed intent after a reboot or a sideloaded
 * update (`MY_PACKAGE_REPLACED` also kills the service) — a 12V brownout or
 * thermal reboot must not leave the whole fleet blind until a human physically
 * picks up the phone (AUDIT-2026-08-09 B3).
 *
 * Default `false`: [PREF_AUTO_START] is unset on a fresh install, so a new
 * beacon never broadcasts before a human has pressed START once.
 * [BeaconActivity.onToggle] sets it on both the start and stop branches, so
 * boot always restores whatever was last explicitly chosen — the STOP button
 * stays a real stop, even across a reboot.
 *
 * Hard dependency on B5: a boot start is a background start
 * ([EXTRA_FROM_BACKGROUND] = true), and [safeForegroundTypes] is what keeps
 * that from throwing on an API 34/35 phone (no mic type, location type only if
 * held). Without B5 this receiver would be a boot *crash*, not a boot fix.
 *
 * If permissions were revoked while the phone was powered off, this still
 * starts the service — B5's guards keep it running in degraded mode,
 * broadcasting sensors with no GNSS fix, with the notification saying why. The
 * fleet sees a live beacon with no fix instead of silence.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(TelemetryBroadcaster.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_AUTO_START, false)) return
        val svc =
            Intent(context, TelemetryService::class.java)
                .putExtra(EXTRA_FROM_BACKGROUND, true)
        ContextCompat.startForegroundService(context, svc)
    }

    companion object {
        /** Shared with [BeaconActivity.onToggle], which is the only writer. */
        internal const val PREF_AUTO_START = "auto_start"
    }
}
