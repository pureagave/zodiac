package org.pureagave.zodiac.control.core.kiosk

/** The two hidden hot-zones the kiosk-exit code is tapped on (the right-edge corners). */
enum class KioskTapZone { TOP_END, BOTTOM_END }

/**
 * Recognises the hidden kiosk-exit tap code.
 *
 * A device-owner tablet (see `docs/KIOSK.md`) cannot be un-provisioned over adb
 * on a non-`testOnly` build, and this app never otherwise self-clears — so
 * without this the *only* way out of kiosk is a factory reset (a screen lost for
 * the week on the playa). This code is the operator's in-app recovery: enter it
 * and [org.pureagave.zodiac.control.kiosk.KioskController.exitKiosk] relinquishes
 * device owner.
 *
 * It is deliberately a memorised sequence, not a labelled button: the trade is
 * that an operator who forgets it falls back to a factory reset, but a passenger
 * poking the screen can never stumble into un-provisioning the tablet. The taps
 * come from two hidden corner hot-zones, so a random poke does nothing; only the
 * exact [code], each tap within [windowMs] of the last, fires.
 *
 * Pure and clock-injected so the sequence logic is unit-tested; the composable
 * only forwards taps. A wrong or late tap restarts the sequence — but a stray tap
 * that happens to equal the first code element restarts *at* element 1, so a
 * fumbled retry still completes without a pause.
 */
class KioskExitCode(
    private val code: List<KioskTapZone> = DEFAULT_CODE,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(code.isNotEmpty()) { "kiosk exit code must not be empty" }
    }

    private var index = 0
    private var lastTapMs = Long.MIN_VALUE

    /** Feed a tap at [nowMs]; returns true exactly on the tap that completes [code]. */
    fun tap(
        zone: KioskTapZone,
        nowMs: Long,
    ): Boolean {
        val stale = nowMs - lastTapMs > windowMs
        lastTapMs = nowMs
        index =
            when {
                // Too slow, or the wrong zone for where we were: restart — but if
                // this tap is itself the first code element, count it as element 1.
                stale || zone != code[index] -> if (zone == code[0]) 1 else 0
                else -> index + 1
            }
        if (index >= code.size) {
            index = 0
            return true
        }
        return false
    }

    companion object {
        /**
         * Alternating right-edge corners, six taps. Documented in `docs/KIOSK.md`
         * so operators can enter it and passengers can't guess it. A constant so
         * it is trivially changed if it ever leaks.
         */
        val DEFAULT_CODE =
            listOf(
                KioskTapZone.BOTTOM_END,
                KioskTapZone.TOP_END,
                KioskTapZone.BOTTOM_END,
                KioskTapZone.TOP_END,
                KioskTapZone.BOTTOM_END,
                KioskTapZone.TOP_END,
            )

        /** Each tap must land within this of the previous, else the sequence restarts. */
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
