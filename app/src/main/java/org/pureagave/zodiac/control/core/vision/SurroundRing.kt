package org.pureagave.zodiac.control.core.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
 *
 * Known limitations, documented rather than fixed here: reversing is not
 * modelled (see [brakeAdvised]'s doc), and a contact that drops out on
 * occlusion vanishes from the ring instantly rather than fading (see
 * [blips]'s doc).
 *
 * The *coverage* concern — which bearing arcs a live camera watches vs. which
 * are blind ([SurroundRingCoverage.rimSegments]) — is split into
 * [SurroundRingCoverage]; the base [COVERED_ARCS] assumption stays here as
 * shared data.
 */
object SurroundRing {
    /**
     * Which part of the vehicle a contact sits in. The distinction is not
     * cosmetic — see [brakeAdvised].
     */
    enum class Sector { FORWARD, SIDE, REAR }

    /**
     * Ordinal proximity band, replacing a linear size→radius mapping.
     *
     * Apparent thermal size cannot support a metric radial encoding: a child
     * at 5 m and an adult at 12 m read the same apparent size, someone prone
     * or on a bike doesn't scale the same way with range, and apparent size
     * falls off as ~1/r, so a linear map distorts closure rate into apparent
     * acceleration. Three ordinal bands read as "nearer than that one,
     * roughly" and stop the metric reading before it starts.
     */
    enum class Band { FAR, MID, NEAR }

    /** Half-angle of the nose cone. */
    const val FORWARD_HALF_DEG: Float = 60f

    /** Beyond this off the nose, a contact is behind the vehicle. */
    const val REAR_HALF_DEG: Float = 120f

    /** Sizes below this are [Band.FAR]. */
    const val FAR_BAND_MAX: Float = 0.33f

    /** Sizes at or above [FAR_BAND_MAX] and below this are [Band.MID]; at or above it, [Band.NEAR]. */
    const val MID_BAND_MAX: Float = 0.66f

    /** Ring radius, as a fraction of ring radius, for [Band.FAR]. */
    const val FAR_RADIUS_FRACTION: Float = 1.00f

    /** Ring radius, as a fraction of ring radius, for [Band.MID]. */
    const val MID_RADIUS_FRACTION: Float = 0.66f

    /**
     * Ring radius, as a fraction of ring radius, for [Band.NEAR] — closer than
     * the old linear floor (`0.18`), chosen so it clears the reticle arms
     * (~0.15 R) with room instead of crowding them.
     */
    const val NEAR_RADIUS_FRACTION: Float = 0.34f

    /**
     * How many blips the ring will draw at once.
     *
     * Deliberately finite: a slow art car in a crowd can have dozens of warm
     * bodies around it, and a ring speckled with forty marks tells a driver
     * nothing. [blips] keeps the most urgent and drops the rest — the contact
     * *count* elsewhere on the HUD still reflects everything, so the dropped
     * ones are not hidden, just not individually drawn. With angular
     * clustering in place this should rarely bind.
     */
    const val MAX_BLIPS: Int = 12

    /**
     * Contacts sharing a [Band] within this many degrees of each other merge
     * into one [Blip]. Thirty dots tell a driver nothing; "crowd on the port
     * bow" should read as one legible thing.
     */
    const val CLUSTER_DEG: Float = 15f

    /**
     * How much more urgent a challenger must be than an incumbent before it
     * takes the incumbent's slot under the [MAX_BLIPS] cap. Without this,
     * noisy `size` estimates make the cap boundary flicker — marks popping in
     * and out is more attention-grabbing than any real signal.
     *
     * `tracker.py`'s `switch_margin` is the same hysteresis idea applied to
     * the DMX head's single-target follow-latch, but the two are tuned
     * independently and are not meant to match: this one gates a difference
     * in [urgency] across up to [MAX_BLIPS] ring slots, that one gates a
     * difference in raw `size` for a single held target. Different
     * quantities on different scales doing different jobs — don't "fix" one
     * to equal the other.
     */
    const val SWITCH_MARGIN: Float = 0.08f

    /**
     * Below this speed, both the brake and check-rear imperatives stay quiet
     * even with a closing contact. People deliberately walk up to art cars —
     * every one of them looks like a constant-bearing, looming track while
     * boarding or parked, and a driver who sees "! BRAKE !" or
     * "! CHECK REAR !" at a dead stop tunes it out long before it matters at
     * speed on open playa. Contacts still draw and collision blips still
     * render red below this speed; only the imperatives are suppressed. This
     * is the moving gate, not a brake-only one — [rearAlert] shares it rather
     * than carrying a second threshold, so the two stay in lockstep.
     */
    const val BRAKE_MIN_KPH: Float = 5f

    /**
     * Bearings the sensor rig actually watches, as (from, to) inclusive
     * ranges in wrapped ±180 terms. The rig today is one UW thermal facing
     * forward — not a closed ring — so most of the circle is genuinely
     * unwatched, not watched-and-clear.
     *
     * **±64°, not ±80°** (revised 2026-08-07). FLIR quotes the Lepton UW as
     * "160°" without ever saying which axis. It cannot be the horizontal: the
     * board emits 160×120, and 160° across the width of a 4:3 f-theta frame
     * demands a ~200° diagonal — not a lens that exists. Read as the diagonal
     * it is an ordinary fisheye, and the horizontal half-angle is
     * 80° × 80/100 = **64°**. The Jetson rig spec carries the matching
     * `fovref=d`; `LeptonUwFovReferenceTest` (`:app` unit tests) pins the
     * arithmetic by re-deriving it from the raw rig geometry.
     *
     * Under genuine uncertainty this errs toward under-claiming on purpose. A
     * ring that says "not watched" where something is watching costs a little
     * unnecessary caution; one that says "watched" where nothing is looking is
     * the confident all-clear this whole mechanism exists to prevent.
     *
     * **Now the fallback, not the only honesty mechanism** (RES-P2-1). The edge
     * box puts live coverage on the wire via the `ZCOVER` channel
     * ([CoverageProtocol]); [SurroundRingCoverage.rimSegments] uses that when
     * present and falls back to this constant only when there is no fresh signal
     * (an old Jetson, or a stale one). Kept in step with the Jetson's `--camera`
     * rig geometry by `LeptonUwFovReferenceTest`, which re-derives the ±64° from
     * first principles — the wire value must agree with this so old and new
     * Jetsons render the same forward arc.
     */
    val COVERED_ARCS: List<ClosedFloatingPointRange<Float>> = listOf(-64f..64f)

    /**
     * One contact — or merged group of contacts — positioned on the ring, in
     * polar terms the renderer can use directly: [screenAngleDeg] follows the
     * canvas convention (0° = right, 90° = down) and [radiusFraction] is 0 at
     * the ego mark, 1 at the ring.
     *
     * [threat] is the representative of the group (the nearest member),
     * carrying [DriverThreat.id] for draw-order/hysteresis identity and
     * [DriverThreat.collision]. [memberCount] is 1 for a lone contact and
     * should render heavier for anything greater — never as a printed number,
     * since text is head-down reading. [collision] is never true for a merged
     * group: collision contacts are never merged (see [blips]).
     */
    data class Blip(
        val threat: DriverThreat,
        val screenAngleDeg: Float,
        val radiusFraction: Float,
        val memberCount: Int,
        val collision: Boolean,
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

    /** Whether the sensor rig watches [relAzDeg] at all — see [COVERED_ARCS]. */
    fun isCovered(relAzDeg: Float): Boolean {
        val a = wrapBearing(relAzDeg)
        return COVERED_ARCS.any { a in it }
    }

    /**
     * Ordinal proximity band for a thermal size estimate. Non-finite or
     * out-of-[0,1] sizes coerce to 0 (far) rather than producing an undefined
     * band, matching [radiusFraction]'s coercion.
     */
    fun bandOf(size: Float): Band {
        val s = clampSize(size)
        return when {
            s < FAR_BAND_MAX -> Band.FAR
            s < MID_BAND_MAX -> Band.MID
            else -> Band.NEAR
        }
    }

    /**
     * Proximity band → distance from the ego mark.
     *
     * **Radius is sensor prominence, not distance.** [DriverThreat.size] is
     * apparent thermal size, and the ordinal bands in [bandOf] are the most
     * this signal can honestly support — see the [Band] doc. Radius never
     * grows as a contact closes: the band-to-radius mapping is monotonic in
     * size.
     *
     * Intended end state, not this pass: with a known camera mounting height,
     * the elevation of a track's ground-contact point yields real range — a
     * Jetson change plus a nullable `rangeM` on `ThreatProtocol`.
     */
    fun radiusFraction(size: Float): Float =
        when (bandOf(size)) {
            Band.FAR -> FAR_RADIUS_FRACTION
            Band.MID -> MID_RADIUS_FRACTION
            Band.NEAR -> NEAR_RADIUS_FRACTION
        }

    /**
     * The blips to draw, in **draw order** — least urgent first, so painting
     * the list in sequence leaves the most urgent contact on top rather than
     * buried under whatever happened to arrive later.
     *
     * Collision contacts are never merged; every other contact is clustered
     * with others sharing its [Band] within [CLUSTER_DEG] of each other, with
     * the group's bearing computed as a circular mean (mean of unit vectors)
     * so a group straddling ±180 — a crowd behind the vehicle — lands astern
     * instead of averaging to dead ahead. The resulting candidates are capped
     * at [max]; [previousKeptIds] (the representative ids kept on the
     * previous call) keeps that cap from flickering — an incumbent only loses
     * its slot to a challenger that beats it by more than [SWITCH_MARGIN].
     * Ad-hoc `id = 0` contacts never latch as incumbents, since id 0 carries
     * no stable identity to protect. [BlipTracker] wraps this for a caller
     * that just wants frame-to-frame stability without managing the id set
     * itself.
     *
     * **Contact coasting is not modelled.** Thermal tracks drop out on
     * occlusion, so a person who vanishes behind another contact at 3 m
     * currently disappears from the ring on the very next frame instead of
     * fading over a second or two. Graceful fade-on-drop would need the
     * caller to keep dead tracks alive briefly by [DriverThreat.id]; not this
     * pass — noted here rather than fixed.
     */
    fun blips(
        threats: List<DriverThreat>,
        max: Int = MAX_BLIPS,
        previousKeptIds: Set<Int> = emptySet(),
    ): List<Blip> {
        if (max <= 0) return emptyList()
        val candidates = buildCandidates(threats)
        return selectWithHysteresis(candidates, previousKeptIds, max).sortedBy(::urgency)
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
     * Also gated on [BRAKE_MIN_KPH] — see its doc for why a stopped or
     * crawling vehicle must not flash this.
     *
     * **Reversing is not modelled.** This assumes the vehicle is moving
     * forward; in reverse the geometry inverts (the "rear" cone becomes the
     * one in the vehicle's path, and braking *does* help against it) and
     * there is no gear signal on the wire to distinguish the two cases. Treat
     * this as a spotter-procedure gap, not a bug — it is not fixed here.
     */
    fun brakeAdvised(
        threats: List<DriverThreat>,
        speedKph: Float,
    ): Boolean =
        speedKph >= BRAKE_MIN_KPH &&
            threats.any { it.collision && sectorOf(it.relAzDeg) != Sector.REAR }

    /**
     * A closing contact behind the vehicle — worth showing, not worth braking
     * for.
     *
     * Also gated on [BRAKE_MIN_KPH], the same walk-up rationale as
     * [brakeAdvised]: a parked or crawling art car in a crowd sees a
     * constant-bearing rear "collision" almost continuously, and an
     * always-on "! CHECK REAR !" in that state is uninformative right when it
     * would otherwise matter. This does not serve the pre-reverse check —
     * reversing is not modelled here any more than it is in [brakeAdvised];
     * at a standstill, rear safety is a spotter-procedure gap, not something
     * this alert covers.
     */
    fun rearAlert(
        threats: List<DriverThreat>,
        speedKph: Float,
    ): Boolean =
        speedKph >= BRAKE_MIN_KPH &&
            threats.any { it.collision && sectorOf(it.relAzDeg) == Sector.REAR }

    /**
     * The DRIVER HUD's status line is one of these five mutually-exclusive
     * states — see [hudStatus] for the precedence and the design doc's
     * status-line table for the text/colour each one maps to. Text and
     * colour are a rendering concern and deliberately not decided here.
     */
    enum class HudStatus { NO_VISION, BRAKE, CHECK_REAR, DEMO, CLEAR }

    /**
     * Which status-line state applies right now — pure precedence over the
     * already-decided inputs, so the status line and the centre banner cannot
     * disagree about what the vehicle should do.
     *
     * Takes the **already-latched** [brakeAdvised] and [checkRear] rather than
     * recomputing them,
     * so the status line and the centre banner cannot disagree about whether
     * the vehicle should be braking — and so a collision flag chattering at
     * frame rate cannot strobe the line. See
     * [org.pureagave.zodiac.control.core.vision.AlarmLatch].
     *
     * Precedence matters: an absent feed overrides everything (a stale
     * "CLEAR" is worse than no reading at all), and a simultaneous forward
     * and rear collision shows [HudStatus.BRAKE] — braking is the more
     * urgent instruction, so it must not be masked by the rear check. The
     * contact count must never disappear during [HudStatus.BRAKE] or
     * [HudStatus.CHECK_REAR]; losing it at the busiest moment would be
     * backwards, and it's what keeps [MAX_BLIPS]/clustering honest.
     */
    fun hudStatus(
        brakeAdvised: Boolean,
        checkRear: Boolean,
        visionFeed: VisionFeed,
    ): HudStatus =
        when {
            visionFeed == VisionFeed.ABSENT -> HudStatus.NO_VISION
            brakeAdvised -> HudStatus.BRAKE
            checkRear -> HudStatus.CHECK_REAR
            visionFeed == VisionFeed.DEMO -> HudStatus.DEMO
            else -> HudStatus.CLEAR
        }

    /**
     * Frame-to-frame convenience wrapper around [blips]: remembers which
     * representative ids were drawn last call so the hysteresis in [blips]
     * has an incumbent set to protect, without the caller threading a
     * `Set<Int>` through itself. One instance per HUD (e.g. `remember`-scoped
     * in the composable); a fresh tracker starts with no incumbents, which is
     * indistinguishable from the very first frame ever drawn.
     */
    class BlipTracker {
        private var keptIds: Set<Int> = emptySet()

        fun blips(
            threats: List<DriverThreat>,
            max: Int = MAX_BLIPS,
        ): List<Blip> {
            val result = blips(threats, max, keptIds)
            keptIds = result.mapNotNull { it.threat.id.takeIf { id -> id != 0 } }.toSet()
            return result
        }
    }

    /**
     * Circular mean of a set of bearings (already ±180-wrapped), computed as
     * the mean of unit vectors rather than a plain arithmetic mean. A plain
     * mean of e.g. 175° and −175° gives 0° — dead ahead — for a group that is
     * actually dead astern; the vector mean gives 180°, correctly. Falls back
     * to the first angle in the degenerate case where the vectors cancel
     * exactly, which cannot arise within one [CLUSTER_DEG]-bounded group in
     * practice.
     */
    private fun circularMeanDeg(anglesDeg: List<Float>): Float {
        var sumX = 0.0
        var sumY = 0.0
        for (a in anglesDeg) {
            val rad = Math.toRadians(a.toDouble())
            sumX += cos(rad)
            sumY += sin(rad)
        }
        if (sumX == 0.0 && sumY == 0.0) return anglesDeg.first()
        return Math.toDegrees(atan2(sumY, sumX)).toFloat()
    }

    /** Coerce a size reading the same way everywhere it's read. */
    private fun clampSize(size: Float): Float = if (size.isFinite()) size.coerceIn(0f, 1f) else 0f

    /** Urgency ranking for the [MAX_BLIPS] cap: collisions always outrank proximity. */
    private fun urgency(blip: Blip): Float = if (blip.collision) COLLISION_URGENCY_BONUS + blip.threat.size else blip.threat.size

    /**
     * Collision contacts each become their own [Blip] (never merged); every
     * other contact is grouped within its [Band] by [clusterGroups] and
     * folded into one representative [Blip] per group.
     */
    private fun buildCandidates(threats: List<DriverThreat>): List<Blip> {
        val (collisions, rest) = threats.partition { it.collision }
        val collisionBlips = collisions.map(::blipForCollision)
        val clusterBlips =
            rest.groupBy { bandOf(it.size) }
                .values
                .flatMap { clusterGroups(it) }
                .map(::blipForGroup)
        return collisionBlips + clusterBlips
    }

    private fun blipForCollision(threat: DriverThreat): Blip =
        Blip(
            threat = threat,
            screenAngleDeg = screenAngleDeg(threat.relAzDeg),
            radiusFraction = radiusFraction(threat.size),
            memberCount = 1,
            collision = true,
        )

    /** Nearest member represents the group; bearing is the group's circular mean. */
    private fun blipForGroup(group: List<DriverThreat>): Blip {
        val representative = group.maxByOrNull { it.size } ?: group.first()
        val meanBearing = circularMeanDeg(group.map { wrapBearing(it.relAzDeg) })
        return Blip(
            threat = representative,
            screenAngleDeg = screenAngleDeg(meanBearing),
            radiusFraction = radiusFraction(representative.size),
            memberCount = group.size,
            collision = false,
        )
    }

    /**
     * Chains contacts (already known to share a [Band]) into groups where
     * consecutive bearings — sorted around the circle — are within
     * [CLUSTER_DEG] of each other, including across the ±180 seam. Chaining
     * means a wide, dense crowd can end up as one group even though its two
     * ends are more than [CLUSTER_DEG] apart; that is the intended "crowd on
     * the port bow" behaviour, not a bug.
     */
    private fun clusterGroups(threats: List<DriverThreat>): List<List<DriverThreat>> {
        if (threats.size <= 1) return if (threats.isEmpty()) emptyList() else listOf(threats)
        val sorted = threats.sortedBy { wrapBearing(it.relAzDeg) }
        val groups = mutableListOf(mutableListOf(sorted[0]))
        for (i in 1 until sorted.size) {
            val prev = wrapBearing(sorted[i - 1].relAzDeg)
            val cur = wrapBearing(sorted[i].relAzDeg)
            if (cur - prev <= CLUSTER_DEG) {
                groups.last().add(sorted[i])
            } else {
                groups.add(mutableListOf(sorted[i]))
            }
        }
        mergeAcrossSeam(groups)
        return groups
    }

    /** If the first and last groups are within [CLUSTER_DEG] across the ±180 seam, fold them together. */
    private fun mergeAcrossSeam(groups: MutableList<MutableList<DriverThreat>>) {
        if (groups.size <= 1) return
        val firstAngle = wrapBearing(groups.first().first().relAzDeg)
        val lastAngle = wrapBearing(groups.last().last().relAzDeg)
        val wrapGap = firstAngle + FULL_TURN - lastAngle
        if (wrapGap <= CLUSTER_DEG) {
            groups.first().addAll(groups.removeAt(groups.size - 1))
        }
    }

    /**
     * Applies [SWITCH_MARGIN] hysteresis to the natural top-[max] selection by
     * urgency. Incumbents (candidates whose representative id is in
     * [previousKeptIds]) that fall just outside the natural cut only lose
     * their slot to the weakest currently-selected non-incumbent when that
     * slot's urgency exceeds theirs by more than [SWITCH_MARGIN]; otherwise
     * they reclaim it. Processed strongest-incumbent-first so the most urgent
     * excluded contact gets first refusal on a reclaimed slot.
     */
    private fun selectWithHysteresis(
        candidates: List<Blip>,
        previousKeptIds: Set<Int>,
        max: Int,
    ): List<Blip> {
        if (candidates.size <= max) return candidates
        val ranked = candidates.sortedByDescending(::urgency)
        if (previousKeptIds.isEmpty()) return ranked.take(max)

        val selected = ranked.take(max).toMutableList()
        val excludedIncumbents =
            ranked.drop(max).filter { it.threat.id != 0 && it.threat.id in previousKeptIds }
        for (incumbent in excludedIncumbents) {
            val weakestIdx = weakestChallengerIndex(selected, previousKeptIds) ?: continue
            if (urgency(selected[weakestIdx]) - urgency(incumbent) < SWITCH_MARGIN) {
                selected[weakestIdx] = incumbent
            }
        }
        return selected
    }

    /** Index of the lowest-urgency currently-selected blip that is not itself a protected incumbent. */
    private fun weakestChallengerIndex(
        selected: List<Blip>,
        previousKeptIds: Set<Int>,
    ): Int? =
        selected.indices
            .filter { selected[it].threat.id == 0 || selected[it].threat.id !in previousKeptIds }
            .minByOrNull { urgency(selected[it]) }

    private const val COLLISION_URGENCY_BONUS = 10f
    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f
    private const val QUARTER_TURN = 90f
}
