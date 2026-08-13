package org.pureagave.zodiac.control.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Locks a fleet tablet to the cockpit so a passenger can't wander out of it.
 *
 * There are two grades of kiosk on Android and the difference matters here:
 *
 * * **Screen pinning** — any app can ask for it, but the user is told how to
 *   leave and can, by holding back+recents. Fine for a demo, useless for a
 *   tablet bolted to an art car that strangers will poke all week.
 * * **Lock task mode as device owner** — silent, unescapable, survives reboot,
 *   and additionally lets us kill the lockscreen (these Fires ship serving
 *   Amazon ads on it) and block OTA updates, which is a real hazard: a tablet
 *   that decides to install a new Fire OS at 2am mid-event is gone for hours.
 *
 * Device owner can only be set on a device with no accounts configured — i.e.
 * immediately after a factory reset, over adb. That provisioning step is
 * deliberately a human's job and lives in `docs/KIOSK.md`; this class only
 * *uses* the privilege if it was granted, and degrades to doing nothing at all
 * if it wasn't. A cockpit that refuses to run because it isn't device owner
 * would be a worse failure than one that isn't locked down.
 */
class KioskController(context: Context) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val admin = ComponentName(context.packageName, ZodiacDeviceAdminReceiver::class.java.name)
    private val packageName: String = context.packageName

    /** True when this app was provisioned as device owner (see docs/KIOSK.md). */
    val isDeviceOwner: Boolean
        get() = dpm?.isDeviceOwnerApp(packageName) == true

    /**
     * Apply the policies that only make sense on a vehicle-mounted tablet, then
     * enter lock task. Safe to call on every resume; every step is a no-op
     * without device owner.
     */
    fun engage(activity: Activity) {
        val manager = dpm
        if (manager == null) {
            // Should be impossible on a real device; logged rather than
            // silently returned so a weird OEM build doesn't look like a
            // successful kiosk engage.
            Timber.w("kiosk: no DevicePolicyManager on this device")
            return
        }
        if (!isDeviceOwner) {
            // Not provisioned — say so and carry on. This is the normal state
            // for a developer's device, and for any tablet not yet through the
            // factory-reset provisioning in docs/KIOSK.md.
            Timber.i("kiosk: not device owner; running unlocked (see docs/KIOSK.md)")
            return
        }
        runCatching {
            manager.setLockTaskPackages(admin, arrayOf(packageName))
            // The lockscreen on an ad-supported Fire is the first thing a
            // passenger sees, and it is an advert. Remove it.
            manager.setKeyguardDisabled(admin, true)
            manager.setStatusBarDisabled(admin, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Never let the tablet reboot into an OS update mid-event.
                manager.setGlobalSetting(admin, "ota_disable_automatic_update", "1")
            }
        }.onFailure { Timber.w(it, "kiosk: policy setup failed") }

        runCatching { activity.startLockTask() }
            .onSuccess { Timber.i("kiosk: locked to %s", packageName) }
            .onFailure { Timber.w(it, "kiosk: startLockTask failed") }
    }

    /** Leave lock task without un-provisioning — a temporary unlock for servicing. */
    fun release(activity: Activity) {
        runCatching { activity.stopLockTask() }
            .onSuccess { Timber.i("kiosk: released") }
            .onFailure { Timber.w(it, "kiosk: stopLockTask failed") }
    }

    /**
     * Un-provision the tablet: leave lock task, then relinquish device owner via
     * [DevicePolicyManager.clearDeviceOwnerApp]. This is the **only** recovery
     * short of a factory reset — a debug/non-`testOnly` build cannot be
     * un-provisioned over adb (`dpm remove-active-admin` throws, and a
     * device-owner app cannot be uninstalled), and nothing else self-clears. See
     * `docs/KIOSK.md`. Reached only by the hidden exit code
     * ([org.pureagave.zodiac.control.core.kiosk.KioskExitCode]) so a passenger
     * can't trigger it. No-op unless this app is device owner.
     *
     * After this returns, [engage] is a no-op (no longer device owner), so the
     * tablet stays unlocked and can be serviced, updated, or uninstalled.
     */
    @Suppress("DEPRECATION")
    fun exitKiosk(activity: Activity) {
        val manager = dpm
        if (manager == null || !isDeviceOwner) {
            Timber.i("kiosk: exit requested but not device owner; nothing to un-provision")
            return
        }
        runCatching { activity.stopLockTask() }
            .onFailure { Timber.w(it, "kiosk: stopLockTask during exit failed") }
        // clearDeviceOwnerApp is deprecated in API 34, but it is the only
        // self-service un-provision path and still functions; the recommended
        // replacements require an enrolled management flow we deliberately do not run.
        runCatching { manager.clearDeviceOwnerApp(packageName) }
            .onSuccess { Timber.i("kiosk: device owner cleared; tablet un-provisioned and unlocked") }
            .onFailure { Timber.w(it, "kiosk: clearDeviceOwnerApp failed") }
    }
}
