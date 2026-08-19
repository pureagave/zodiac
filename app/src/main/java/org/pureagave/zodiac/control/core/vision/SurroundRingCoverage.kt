package org.pureagave.zodiac.control.core.vision

/**
 * Coverage classification for the DRIVER surround ring: which bearing arcs a
 * *live* camera watches vs. which are blind, and how each rim arc reads.
 *
 * Split out of [SurroundRing] (RES-P2-1) per that object's own guidance —
 * "split it by concern (placement / alerting / coverage) if it goes further".
 * Coverage is a distinct decision from blip placement and the brake/rear
 * alerting, so it lives here. Still pure, still `core/vision`, still fully
 * unit-tested — the decision the HUD turns on stays out of the Canvas.
 *
 * The base assumption [SurroundRing.COVERED_ARCS] (the static forward arc used
 * when no live `ZCOVER` signal is present) stays in [SurroundRing] as shared
 * data; the *classification* built on top of it is here.
 */
object SurroundRingCoverage {
    /**
     * How a rim segment should read on the HUD — see [rimSegments]. Text and
     * colour are a rendering concern (`SurroundRingCanvas` maps these to paint);
     * the *classification* is the decision, and it lives here.
     */
    enum class RimStyle {
        /** A live camera watches this arc: solid, trustworthy — an empty ring here IS all-clear. */
        COVERED,

        /** No live camera watches this arc (unconfigured, failed, or stalled): a visible fault wedge. */
        BLIND,

        /** The whole feed is the synthetic demo: coverage is fabricated, not real. */
        DEMO,
    }

    /** One rim arc and how it reads, swept clockwise from [startDeg] to [endDeg] (bearings off the nose). */
    data class RimSegment(
        val startDeg: Float,
        val endDeg: Float,
        val style: RimStyle,
    )

    /**
     * The bearings the rig does **not** watch — the complement of [covered]
     * (defaulting to [SurroundRing.COVERED_ARCS]) around the full circle.
     *
     * This is the decision that keeps an unwatched sector from rendering
     * identically to a watched-and-clear one, so it lives here with the rest
     * of the ring's coverage decisions rather than in draw code. The last gap
     * wraps through ±180 back to the first covered arc, which is exactly the
     * case a forward-facing rig produces: one covered arc across the nose and
     * one gap spanning the whole stern.
     *
     * Returned in unwrapped terms — a gap may run past +180 (e.g. `80..280`
     * for a ±80° covered arc) so a renderer can sweep it directly without
     * splitting it at the seam.
     */
    fun uncoveredArcs(covered: List<ClosedFloatingPointRange<Float>> = SurroundRing.COVERED_ARCS): List<ClosedFloatingPointRange<Float>> {
        if (covered.isEmpty()) return listOf(-HALF_TURN..HALF_TURN)
        val sorted = covered.sortedBy { it.start }
        val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
        for (i in sorted.indices) {
            val next = if (i + 1 < sorted.size) sorted[i + 1].start else sorted.first().start + FULL_TURN
            if (next > sorted[i].endInclusive) gaps += sorted[i].endInclusive..next
        }
        return gaps
    }

    /**
     * The rim as a list of classified segments that together sweep the whole
     * 360° with no gaps — the single decision RES-P2-1 turns on, so it lives
     * here (pure, tested) rather than in the Canvas.
     *
     * - [VisionFeed.ABSENT] → one full-circle [RimStyle.BLIND] wedge ("NO VISION").
     * - [VisionFeed.DEMO] → one full-circle [RimStyle.DEMO] wedge (fabricated coverage).
     * - [VisionFeed.LIVE] → the [covered] arcs (from the edge box's live `ZCOVER`
     *   signal, or the static [SurroundRing.COVERED_ARCS] when [covered] is null
     *   — an old Jetson that never sends coverage) as [RimStyle.COVERED], and
     *   their complement as [RimStyle.BLIND].
     *
     * A **null** [covered] means "no fresh coverage signal": fall back to the
     * static assumption, reproducing today's behaviour exactly. An **empty**
     * [covered] means "a live feed watching nothing" — the whole ring is BLIND,
     * never clear. The wire list is untrusted, so it is run through
     * [normalizeCovered] (merge overlapping / contained / seam-crossing arcs)
     * before the complement is taken — a blind arc must never be silently
     * dropped or coalesced into covered (R6).
     */
    fun rimSegments(
        feed: VisionFeed,
        covered: List<ClosedFloatingPointRange<Float>>?,
    ): List<RimSegment> =
        when (feed) {
            VisionFeed.ABSENT -> listOf(RimSegment(-HALF_TURN, HALF_TURN, RimStyle.BLIND))
            VisionFeed.DEMO -> listOf(RimSegment(-HALF_TURN, HALF_TURN, RimStyle.DEMO))
            VisionFeed.LIVE -> {
                val normalized = normalizeCovered(covered ?: SurroundRing.COVERED_ARCS)
                buildList {
                    normalized.forEach { add(RimSegment(it.start, it.endInclusive, RimStyle.COVERED)) }
                    uncoveredArcs(normalized).forEach { add(RimSegment(it.start, it.endInclusive, RimStyle.BLIND)) }
                }
            }
        }

    /**
     * Merge a possibly-messy covered-arc list (the wire is untrusted) into
     * disjoint arcs on the circle: overlapping, fully-contained, seam-crossing
     * and out-of-order arcs all fold into a clean set whose complement
     * ([uncoveredArcs]) is exact. Any arc spanning a full turn collapses to the
     * whole ring. Invalid arcs (non-finite, zero/negative length) are dropped
     * toward LESS coverage. The old [uncoveredArcs] alone mishandles a contained
     * arc; this runs first so it never sees one.
     */
    fun normalizeCovered(covered: List<ClosedFloatingPointRange<Float>>): List<ClosedFloatingPointRange<Float>> {
        val valid = covered.filter { it.start.isFinite() && it.endInclusive.isFinite() && it.endInclusive > it.start }
        // Any single arc spanning a full turn is the whole ring covered.
        if (valid.any { (it.endInclusive - it.start) >= FULL_TURN - COVER_EPS }) return listOf(-HALF_TURN..HALF_TURN)
        val segs = mutableListOf<DoubleArray>()
        valid.forEach { circularSubsegments(it, segs) }
        if (segs.isEmpty()) return emptyList()
        segs.sortBy { it[0] }
        val merged = mergeAdjacentRuns(segs)
        rejoinSeam(merged)
        return merged.map { (it[0] - HALF_TURN).toFloat()..(it[1] - HALF_TURN).toFloat() }
    }

    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f

    /** Slack for the coverage-arc merge: absorbs float error and treats abutting arcs as one. */
    private const val COVER_EPS = 1e-4
}

// -- normalizeCovered mechanics ---------------------------------------------
// Top-level (file-private) so they don't count against the object's function
// budget: they carry no ring decision, just the interval-union arithmetic that
// keeps normalizeCovered readable. All work in a [0,360) double space (start
// shifted by +180) so a plain linear merge handles the circle.

private const val COVER_FULL_TURN = 360.0
private const val COVER_HALF_TURN = 180.0
private const val COVER_MERGE_EPS = 1e-4

/**
 * Lay one covered arc onto [0,360) as one or two half-open [lo, hi] runs,
 * splitting a run that passes 360 so the downstream merge stays linear.
 */
private fun circularSubsegments(
    arc: ClosedFloatingPointRange<Float>,
    out: MutableList<DoubleArray>,
) {
    val start = arc.start.toDouble()
    val len = (arc.endInclusive.toDouble() - start).coerceAtMost(COVER_FULL_TURN)
    val base = ((start + COVER_HALF_TURN) % COVER_FULL_TURN + COVER_FULL_TURN) % COVER_FULL_TURN
    val hi = base + len
    if (hi <= COVER_FULL_TURN) {
        out += doubleArrayOf(base, hi)
    } else {
        out += doubleArrayOf(base, COVER_FULL_TURN)
        out += doubleArrayOf(0.0, hi - COVER_FULL_TURN)
    }
}

/** Merge overlapping/abutting runs (already sorted by start) into disjoint runs. */
private fun mergeAdjacentRuns(sorted: List<DoubleArray>): MutableList<DoubleArray> {
    val merged = mutableListOf<DoubleArray>()
    for (seg in sorted) {
        val last = merged.lastOrNull()
        if (last != null && seg[0] <= last[1] + COVER_MERGE_EPS) {
            last[1] = maxOf(last[1], seg[1])
        } else {
            merged += seg.copyOf()
        }
    }
    return merged
}

/**
 * Rejoin a run split across the 0/360 seam: if the first run starts at 0 and the
 * last ends at 360 they are one circular arc, so fold the first onto the last.
 */
private fun rejoinSeam(merged: MutableList<DoubleArray>) {
    if (merged.size > 1 && merged.first()[0] <= COVER_MERGE_EPS && merged.last()[1] >= COVER_FULL_TURN - COVER_MERGE_EPS) {
        val first = merged.removeAt(0)
        merged.last()[1] = COVER_FULL_TURN + first[1]
    }
}
