package org.pureagave.zodiac.control.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the 2026 city-move migration: the active Golden Spike origin (and its
 * year) must be the 2026 point, not last year's. Guards against a stale flip
 * back to [GoldenSpike.Y2025].
 */
class GoldenSpikeTest {
    @Test
    fun active_origin_is_the_2026_man() {
        assertEquals(GoldenSpike.Y2026, GoldenSpike.ACTIVE)
        assertEquals(2026, GoldenSpike.ACTIVE_YEAR)
    }

    @Test
    fun the_city_moved_2025_to_2026() {
        // The 2026 Man is a distinct coordinate from 2025 (the city translated
        // ~583 m SW); if these ever match, the migration data regressed.
        assertNotEquals(GoldenSpike.Y2025, GoldenSpike.Y2026)
    }
}
