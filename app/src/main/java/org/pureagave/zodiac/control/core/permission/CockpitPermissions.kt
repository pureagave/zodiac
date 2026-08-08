package org.pureagave.zodiac.control.core.permission

import android.Manifest
import android.os.Build

// Which runtime permissions the cockpit needs, and which of them still have to
// be asked for. Pure functions over plain strings so the SDK-level branching and
// the "is this actually a new grant" logic are unit-testable without a device —
// the `Manifest.permission` values are compile-time String constants, so nothing
// here touches the Android runtime.

/**
 * The permissions this build needs on an API [sdkInt] device. Bluetooth's
 * runtime permissions only exist from Android 12 (S); asking for them below
 * that returns an immediate denial and trains the user to dismiss dialogs.
 */
fun requiredCockpitPermissions(sdkInt: Int): List<String> =
    buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

/**
 * The subset of [required] not yet held. Empty means don't launch the request
 * at all — and that matters beyond saving a round trip: an already-granted
 * permission comes back from the launcher as `true`, so a blanket re-request
 * every launch looks exactly like a fresh grant. That was making the cockpit
 * stop and restart its location source on every cold start, which on the NET
 * source means dropping and rebinding the multicast socket for nothing.
 */
fun permissionsToRequest(
    required: List<String>,
    isGranted: (String) -> Boolean,
): List<String> = required.filterNot(isGranted)

/**
 * Whether a launcher result contains a permission that genuinely just turned
 * on — the only case worth restarting a location source for. Given the caller
 * only ever requests what was missing, any `true` here is a real transition;
 * this stays explicit rather than inlined so the intent survives a refactor.
 */
fun grantedAnythingNew(results: Map<String, Boolean>): Boolean = results.values.any { it }
