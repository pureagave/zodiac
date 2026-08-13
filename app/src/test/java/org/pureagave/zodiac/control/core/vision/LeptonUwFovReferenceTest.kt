package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins [SurroundRing.COVERED_ARCS]' ±64° to the same geometry the Jetson's
 * `zvision/rig.py` `CameraMount.half_h_fov_deg()` (via
 * `geometry.pixel_to_bearing`) derives it from, rather than merely restating
 * the constant.
 *
 * The shipped rig is one forward Lepton UW: 160° **diagonal**-referenced,
 * f-theta (equidistant) fisheye, 4:3 sensor at 160×120. For an equidistant
 * lens the ray angle is linear in normalised image radius, and a
 * diagonal-referenced FOV normalises that radius by the frame diagonal —
 * `r_edge = sqrt(1 + aspect^2)` with `aspect = height / width`. The
 * horizontal frame edge sits at radius 1, so the horizontal half-angle is
 * `(fovDiag / 2) / r_edge`. This is exactly the equidistant branch of
 * `pixel_to_bearing` evaluated at `(cx=1.0, cy=0.5)` — this test reproduces
 * that closed form from the raw rig inputs, not from the literal 64.
 *
 * This is the **only** cross-check keeping the tablet's [SurroundRing] and
 * the Jetson's rig geometry from silently drifting apart — there is no wire
 * field for coverage yet (see [SurroundRing.COVERED_ARCS]'s doc). If this
 * test and `rig.py`'s own `half_h_fov_deg` tests ever disagree, the two rigs
 * have drifted and one side is wrong.
 */
class LeptonUwFovReferenceTest {
    // The shipped rig's raw inputs (zvision/rig.py CameraMount defaults):
    // one forward Lepton UW, 160° quoted diagonal, 160x120 frame.
    private val fovDiagDeg = 160.0
    private val frameWidth = 160.0
    private val frameHeight = 120.0

    /**
     * `geometry.pixel_to_bearing`'s equidistant + diagonal-fov-ref branch,
     * evaluated at the horizontal frame edge (cx=1.0, cy=0.5), reproduced
     * from first principles rather than imported — this test derives the
     * number independently of both the Kotlin and Python implementations.
     */
    private fun derivedHalfHorizontalFovDeg(): Double {
        val aspect = frameHeight / frameWidth
        val rEdge = sqrt(1.0 + aspect * aspect)
        return (fovDiagDeg / 2.0) / rEdge
    }

    @Test
    fun the_diagonal_referenced_geometry_yields_64_degrees_horizontal() {
        // Absolute anchor, independent of SurroundRing entirely: proves the
        // geometry, not just that two sides happen to agree.
        assertEquals(64.0, derivedHalfHorizontalFovDeg(), 1e-3)
    }

    @Test
    fun surround_ring_covered_arcs_matches_the_derived_geometry() {
        val derived = derivedHalfHorizontalFovDeg().toFloat()
        assertEquals(1, SurroundRing.COVERED_ARCS.size)
        val arc = SurroundRing.COVERED_ARCS.single()
        assertEquals(-derived, arc.start, 1e-3f)
        assertEquals(derived, arc.endInclusive, 1e-3f)
    }
}
