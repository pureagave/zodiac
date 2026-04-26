package ai.openclaw.zodiaccontrol.core.sensor

import ai.openclaw.zodiaccontrol.core.geo.LatLon

/**
 * A single GPS fix as delivered by a [LocationSource]. Heading and speed are
 * optional because not every source reports them (e.g. a stationary fake, or
 * a stationary real receiver before motion is detected).
 *
 * @property location WGS84 lon/lat
 * @property headingDeg course-over-ground in degrees clockwise from true north,
 *  or null if unknown
 * @property speedKph ground speed in kph, or null if unknown
 * @property fixQualityM 1-sigma horizontal accuracy estimate in meters, or
 *  null if unknown
 */
data class GpsFix(
    val location: LatLon,
    val headingDeg: Double? = null,
    val speedKph: Double? = null,
    val fixQualityM: Double? = null,
)
