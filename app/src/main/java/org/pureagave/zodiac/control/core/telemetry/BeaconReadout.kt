package org.pureagave.zodiac.control.core.telemetry

/**
 * Everything the ops footer draws about the beacon, in one value.
 *
 * These three arrive on separate sentences (`$ZODO`, `$ZBCN`, `$ZSHK`) and are
 * stored separately on the UI state, but they're always rendered together and
 * always as a group — so they travel together rather than as three more
 * parameters on an already-wide composable.
 */
data class BeaconReadout(
    val odometer: Odometer? = null,
    val health: BeaconHealth? = null,
    val shockG: Double? = null,
) {
    /**
     * Whether the beacon is reporting anything worth a line. With nothing on
     * the bus the footer stays one line rather than showing a row of dashes
     * for readings that will never arrive.
     */
    val any: Boolean
        get() = odometer != null || health != null || shockG != null

    companion object {
        val NONE = BeaconReadout()
    }
}
