package org.pureagave.zodiac.control.core.vision

/**
 * Places full-circle thermal contacts onto the DRIVER HUD's surround ring.
 *
 * The edge box fuses a ring of cameras and reports bearings over the whole
 * circle, but the HUD's wireframe figures live in a *forward perspective* view —
 * they stand on a ground plane at a horizontal position derived from bearing,
 * which has no meaning for something behind the vehicle. So the HUD carries two
 * projections: the perspective view keeps the forward arc, and this plan-view
 * ring carries everything, including what the driver cannot see.
 *
 * The ring is **nose-up**: a driver needs "that one is on my left", not a
 * compass. Straight ahead is the top of the ring, dead astern the bottom, and
 * the whole picture rotates with the vehicle rather than with the world.
 */
object SurroundRing {
    /**
     * Which part of the vehicle a contact sits in. The distinction is not
     * cosmetic — see [brakeAdvised].
     */
    enum class Sector { FORWARD, SIDE, REAR }

    /** Half-angle of the nose cone. */
    const val FORWARD_HALF_DEG: Float = 60f

    /** Beyond this off the nose, a contact is behind the vehicle. */
    const val REAR_HALF_DEG: Float = 120f

    /**
     * Closest a blip may sit to the centre, as a fraction of ring radius.
     *
     * A contact at `size = 1` is nearly touching the vehicle. Drawing it at
     * radius 0 would bury it under the ego mark exactly when it matters most,
     * so the scale bottoms out here instead.
     */
    const val MIN_RADIUS_FRACTION: Float = 0.18f

    /**
     * How many blips the ring will draw at once.
     *
     * Deliberately finite: a slow art car in a crowd can have dozens of warm
     * bodies around it, and a ring speckled with forty marks tells a driver
     * nothing. [blips] keeps the most urgent and drops the rest — the contact
     * *count* elsewhere on the HUD still reflects everything, so the dropped
     * ones are not hidden, just not individually drawn.
     */
    const val MAX_BLIPS: Int = 12

    /**
     * One contact positioned on the ring, in polar terms the renderer can use
     * directly: [screenAngleDeg] follows the canvas convention (0° = right,
     * 90° = down) and [radiusFraction] is 0 at the ego mark, 1 at the ring.
     */
    data class Blip(
        val threat: DriverThreat,
        val screenAngleDeg: Float,
        val radiusFraction: Float,
    )

    /** Fold a bearing into (−180, 180]; dead astern resolves to +180. */
    fun wrapBearing(relAzDeg: Float): Float {
        if (!relAzDeg.isFinite()) return 0f
        var d = relAzDeg % FULL_TURN
        if (d > HALF_TURN) d -= FULL_TURN
        if (d <= -HALF_TURN) d += FULL_TURN
        return d
    }

    fun sectorOf(relAzDeg: Float): Sector {
        val a = kotlin.math.abs(wrapBearing(relAzDeg))
        return when {
            a <= FORWARD_HALF_DEG -> Sector.FORWARD
            a <= REAR_HALF_DEG -> Sector.SIDE
            else -> Sector.REAR
        }
    }

    /**
     * Bearing → canvas angle, nose-up.
     *
     * Bearing is measured off the nose; the canvas measures from +X (right) with
     * +Y pointing *down*. Rotating by a quarter turn puts the nose at the top
     * and leaves the sense of rotation intact — a contact to starboard (+az)
     * lands on the right of the ring, which is what the driver's head does.
     */
    fun screenAngleDeg(relAzDeg: Float): Float = wrapBearing(relAzDeg) - QUARTER_TURN

    /**
     * Proximity → distance from the ego mark.
     *
     * [DriverThreat.size] runs 0 (far) → 1 (near), so the ring inverts it:
     * far contacts sit out at the rim, near ones close in. Floored at
     * [MIN_RADIUS_FRACTION] so the nearest contact stays visible rather than
     * vanishing under the ego mark.
     */
    fun radiusFraction(size: Float): Float {
        val s = if (size.isFinite()) size.coerceIn(0f, 1f) else 0f
        return (1f - s).coerceAtLeast(MIN_RADIUS_FRACTION)
    }

    /**
     * The blips to draw, in **draw order** — least urgent first, so painting
     * the list in sequence leaves the most urgent contact on top rather than
     * buried under whatever happened to arrive later.
     *
     * Urgency is collision first, then proximity. Capped at [max]; see
     * [MAX_BLIPS] for why the cap exists and why it does not hide anything.
     */
    fun blips(
        threats: List<DriverThreat>,
        max: Int = MAX_BLIPS,
    ): List<Blip> {
        if (max <= 0) return emptyList()
        return threats
            .sortedWith(compareByDescending<DriverThreat> { it.collision }.thenByDescending { it.size })
            .take(max)
            .map { Blip(it, screenAngleDeg(it.relAzDeg), radiusFraction(it.size)) }
            .reversed()
    }

    /**
     * Whether the HUD should tell the driver to brake.
     *
     * **Not simply "is anything on a collision course".** A contact closing
     * from behind is one the driver cannot help by braking — slowing down puts
     * the vehicle further into its path, and a "! BRAKE !" flash for something
     * astern trains the driver to distrust the alert that matters. Braking is
     * advised only for contacts forward of the beam-ish [REAR_HALF_DEG] cone;
     * a closing rear contact raises [rearAlert] instead.
     *
     * This is the specific bug the forward-arc filter was hiding: with
     * full-circle bearings on the wire and no sector logic, a contact astern
     * would have fired the brake warning.
     */
    fun brakeAdvised(threats: List<DriverThreat>): Boolean = threats.any { it.collision && sectorOf(it.relAzDeg) != Sector.REAR }

    /** A closing contact behind the vehicle — worth showing, not worth braking for. */
    fun rearAlert(threats: List<DriverThreat>): Boolean = threats.any { it.collision && sectorOf(it.relAzDeg) == Sector.REAR }

    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f
    private const val QUARTER_TURN = 90f
}
