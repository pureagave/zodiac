package org.pureagave.zodiac.control.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.os.Build
import org.pureagave.zodiac.control.core.permission.requiredCockpitPermissions
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
     * without device owner. Also pre-grants the runtime permissions the
     * cockpit uses, since lock task hides the dialog that would otherwise ask
     * for them (see [preGrantUsedPermissions]).
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
            // Never let the tablet reboot into an OS update mid-event. The
            // device-owner API — not setGlobalSetting("ota_disable_automatic_update"),
            // whose key is not allow-listed on API 34, so it threw and was
            // silently swallowed, leaving the guarantee unapplied. Postpone is the
            // strongest sanctioned option (a ~30-day cap, ample for a week-long event).
            manager.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createPostponeInstallPolicy())
        }.onFailure { Timber.w(it, "kiosk: policy setup failed") }

        preGrantUsedPermissions(manager)

        runCatching { activity.startLockTask() }
            .onSuccess { Timber.i("kiosk: locked to %s", packageName) }
            .onFailure { Timber.w(it, "kiosk: startLockTask failed") }
    }

    /**
     * Pre-grant the runtime permissions the cockpit actually asks for at
     * launch ([requiredCockpitPermissions]).
     *
     * Why this exists: a device owner does **not** auto-grant runtime
     * permissions. Combined with [setStatusBarDisabled] (no pull-down) and
     * lock task suppressing the permission dialog, a dangerous permission
     * (e.g. `ACCESS_FINE_LOCATION`, or the Bluetooth pair on API 31+) that
     * wasn't already held before the tablet was kiosked becomes permanently
     * ungrantable — there is no on-device route to Settings to fix it. This
     * closes that trap by granting exactly the set the app already declares
     * in the manifest and already requests at runtime; it introduces no new
     * capability.
     *
     * Device-owner-only (like every other step in [engage]) and therefore
     * **unexercisable in CI** — [DevicePolicyManager.setPermissionGrantState]
     * only does anything real under an honored device-owner policy, which
     * Robolectric doesn't provide. Must be verified on a provisioned tablet;
     * see the "Runtime permissions" note in `docs/KIOSK.md`.
     */
    private fun preGrantUsedPermissions(manager: DevicePolicyManager) {
        requiredCockpitPermissions(Build.VERSION.SDK_INT).forEach { permission ->
            runCatching {
                manager.setPermissionGrantState(
                    admin,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.onSuccess { applied -> Timber.i("kiosk: pre-granted %s (applied=%b)", permission, applied) }
                .onFailure { Timber.w(it, "kiosk: pre-grant of %s failed", permission) }
        }
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
