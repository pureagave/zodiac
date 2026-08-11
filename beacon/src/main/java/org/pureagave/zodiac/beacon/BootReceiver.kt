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
 * **Default `true` (changed 2026-08-11).** The beacon is a power-on appliance:
 * once it is mounted on the vehicle nobody can reach it, so having power must be
 * sufficient to make it broadcast. A fresh install, a factory reset or a wiped
 * phone therefore beacons on the next boot with no human involved.
 *
 * This does not make the STOP button a lie. [BeaconActivity.onToggle] writes the
 * flag on *both* branches, so an explicit STOP persists across reboots exactly as
 * before — the only thing that changed is the state before anyone has expressed
 * an intent at all, and for a dedicated sensor phone "broadcast" is the right
 * answer there. The old default cost the fleet its GPS after any reinstall until
 * somebody remembered to press START, which is precisely the situation a mounted
 * phone cannot recover from.
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
        if (!prefs.getBoolean(PREF_AUTO_START, AUTO_START_DEFAULT)) return
        val svc =
            Intent(context, TelemetryService::class.java)
                .putExtra(EXTRA_FROM_BACKGROUND, true)
        ContextCompat.startForegroundService(context, svc)
    }

    companion object {
        /** Shared with [BeaconActivity.onToggle], which is the only writer. */
        internal const val PREF_AUTO_START = "auto_start"

        /**
         * Absent flag means "yes, broadcast". See the class doc: a mounted phone
         * cannot be reached to press START, so power-on has to be enough.
         */
        internal const val AUTO_START_DEFAULT = true
    }
}
