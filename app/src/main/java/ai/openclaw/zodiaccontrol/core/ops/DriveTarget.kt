package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.LatLon

/**
 * A resolved "drive to" destination — a display [label] + its [location].
 * Unifies the fixed [NavTarget] presets (HOME / MAN / TEMPLE) with an arbitrary
 * discovery POI picked from the nearby panel, so the ops readout and the RADAR
 * target blip guide to either exactly the same way (bearing + distance via
 * [campGuidance]).
 */
data class DriveTarget(
    val label: String,
    val location: LatLon,
)

/** The preset destination as a [DriveTarget]. */
fun NavTarget.toDriveTarget(): DriveTarget = DriveTarget(label = label, location = location)
