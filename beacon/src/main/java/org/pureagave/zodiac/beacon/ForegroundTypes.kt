package org.pureagave.zodiac.beacon

// Mirrors of android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_* — kept as
// local literals rather than an Android import, so this file (and its test) has
// zero Android imports and is exhaustively unit-testable off-device, with no
// Robolectric / device dependency.
private const val FGS_TYPE_LOCATION = 8
private const val FGS_TYPE_MICROPHONE = 128
private const val FGS_TYPE_SPECIAL_USE = 0x40000000

/**
 * Compute the foreground-service type bitmask that is *safe to declare* for this
 * start, given the platform version, the permissions actually held, and whether
 * this start has a reason to believe it's happening in the background (a null
 * `onStartCommand` intent, or an explicit `EXTRA_FROM_BACKGROUND`).
 *
 * Returns `null` below API 30, where foreground service types don't exist and
 * the caller must use the legacy 2-arg `startForeground`.
 *
 * Platform facts encoded here (see AUDIT-2026-08-09.md B5):
 * - API 34+: starting with the `location` type without location permission throws.
 * - API 34+: starting with the `microphone` type without RECORD_AUDIO throws.
 * - API 30+: a foreground service *restarted while backgrounded* (e.g. a
 *   START_STICKY restart, or a boot start) gets no microphone capture, and API 35
 *   outright bans launching with the microphone type from BOOT_COMPLETED — so the
 *   mic bit is never requested on a background start, on any SDK, as a blanket
 *   policy rather than a version-gated one.
 * - API 34+: `startForeground` with a zero type throws — hence the
 *   `specialUse` floor, backed by the manifest's `FOREGROUND_SERVICE_SPECIAL_USE`
 *   declaration, so a beacon with no permissions at all still has a legal type
 *   to start under (see B5: degraded-but-running).
 */
internal fun safeForegroundTypes(
    sdkInt: Int,
    hasLocationPermission: Boolean,
    hasRecordAudio: Boolean,
    fromBackground: Boolean,
): Int? {
    if (sdkInt < 30) return null
    var types = 0
    if (sdkInt < 34 || hasLocationPermission) types = types or FGS_TYPE_LOCATION
    if (hasRecordAudio && !fromBackground) types = types or FGS_TYPE_MICROPHONE
    if (types == 0) types = FGS_TYPE_SPECIAL_USE
    return types
}
