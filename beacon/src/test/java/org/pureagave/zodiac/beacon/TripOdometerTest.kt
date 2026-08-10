package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TripOdometer] gates every fix on accuracy and on implied speed before it
 * touches [TripOdometer.totalMeters] (AUDIT-2026-08-09 C8) — that field is
 * persisted forever, so these tests specifically probe the failure modes the
 * audit named: a parked-among-RVs multipath excursion, a bad cold-start fix
 * hundreds of km away, and (the tension the fix must not break) a legitimate
 * fast highway drive to the event.
 *
 * `add(lat, lon, accuracyMeters, timestampMs)` — `timestampMs` is a monotonic
 * clock reading (mirrors `Location.elapsedRealtimeNanos / 1e6` at the call
 * site), not wall-clock GPS time.
 */
class TripOdometerTest {
    private companion object {
        const val GOOD_ACCURACY_M = 5.0f
        const val BAD_ACCURACY_M = 45.0f // a plausible multipath/cold-start figure
    }

    @Test
    fun first_fix_adds_nothing() {
        val odo = TripOdometer()
        assertEquals(0.0, odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L), 0.0)
        assertEquals(0.0, odo.tripMeters, 0.0)
    }

    @Test
    fun a_real_move_accumulates_haversine_distance() {
        val odo = TripOdometer()
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        // 0.001° of longitude at the equator ≈ 111.32 m, 10 s later — well
        // inside the plausible-speed envelope (≈11 m/s ≈ 40 kph).
        val d = odo.add(0.0, 0.001, GOOD_ACCURACY_M, 10_000L)
        assertEquals(111.32, d, 1.0)
        assertEquals(111.32, odo.tripMeters, 1.0)
    }

    @Test
    fun a_step_exactly_at_the_jitter_floor_still_counts() {
        // The gate is `d < jitterFloorM`, so a step landing exactly on the
        // floor must count, not be held as jitter. Landing on that boundary
        // *bit-for-bit* by deriving a coordinate delta analytically is not
        // reliable — the haversine formula's sin/cos/atan2 chain doesn't
        // round-trip exactly through a separately computed angle. Instead,
        // measure the real distance TripOdometer's own formula produces for a
        // fixed pair of fixes (permissive floor, nothing rejected), then reuse
        // that exact value as the floor for a second instance — so `d` and
        // `jitterFloorM` are guaranteed equal, not merely close.
        val probe = TripOdometer(jitterFloorM = 0.0)
        probe.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        val measured = probe.add(0.0, 0.0001, GOOD_ACCURACY_M, 1_000L)
        val odo = TripOdometer(jitterFloorM = measured)
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        val d = odo.add(0.0, 0.0001, GOOD_ACCURACY_M, 1_000L)
        assertEquals(measured, d, 0.0) // `d < floor` must be false when d == floor
    }

    @Test
    fun sub_floor_jitter_is_ignored_and_the_anchor_is_held() {
        val odo = TripOdometer(jitterFloorM = 5.0)
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        assertEquals(0.0, odo.add(0.0, 0.00001, GOOD_ACCURACY_M, 1_000L), 0.0) // ~1.1 m, under the 5 m floor
        // Anchor stayed at (0,0), so a step clearing the floor counts the full
        // distance from the original anchor (~11.1 m), not just the last leg.
        val d = odo.add(0.0, 0.00010, GOOD_ACCURACY_M, 2_000L)
        assertEquals(11.13, d, 1.0)
        assertEquals(11.13, odo.tripMeters, 1.0)
    }

    @Test
    fun total_is_seeded_and_accumulates_alongside_trip() {
        val odo = TripOdometer(totalSeedM = 1_000.0)
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        odo.add(0.0, 0.001, GOOD_ACCURACY_M, 10_000L) // ~111 m
        assertEquals(111.32, odo.tripMeters, 1.0)
        assertEquals(1_111.32, odo.totalMeters, 1.0)
    }

    @Test
    fun a_low_accuracy_fix_does_not_accumulate() {
        val odo = TripOdometer()
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        // Same ~111 m step as the "real move" test, but the fix is reported at
        // a degraded accuracy (the multipath signature) — must not count.
        val d = odo.add(0.0, 0.001, BAD_ACCURACY_M, 10_000L)
        assertEquals(0.0, d, 0.0)
        assertEquals(0.0, odo.tripMeters, 0.0)
    }

    @Test
    fun a_missing_accuracy_reading_does_not_accumulate() {
        val odo = TripOdometer()
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 0L)
        val d = odo.add(0.0, 0.001, null, 10_000L)
        assertEquals(0.0, d, 0.0)
    }

    @Test
    fun a_low_accuracy_first_fix_never_seeds_the_anchor() {
        // A cold-start fix (huge accuracy circle) must not become the
        // reference point — otherwise the very first good fix afterwards
        // would read as a giant, spurious step away from garbage.
        val odo = TripOdometer()
        odo.add(0.0, 0.0, BAD_ACCURACY_M, 0L) // rejected as the anchor
        val firstGoodFix = odo.add(0.0, 0.001, GOOD_ACCURACY_M, 10_000L)
        assertEquals(0.0, firstGoodFix, 0.0) // this is now treated as the FIRST fix
        assertEquals(0.0, odo.tripMeters, 0.0)
        // Confirm the anchor is now (0, 0.001): the next real step measures
        // from there, not from the original (0,0).
        val d = odo.add(0.0, 0.002, GOOD_ACCURACY_M, 20_000L)
        assertEquals(111.32, d, 1.0)
    }

    @Test
    fun a_teleport_step_is_rejected_while_a_legitimate_drive_accumulates() {
        val odo = TripOdometer()
        odo.add(40.0, -119.0, GOOD_ACCURACY_M, 0L)
        // ~200 km away, 10 s later: implies ~72,000 kph. No vehicle. Rejected.
        val teleport = odo.add(41.8, -119.0, GOOD_ACCURACY_M, 10_000L)
        assertEquals(0.0, teleport, 0.0)
        assertEquals(0.0, odo.tripMeters, 0.0)
        // A legitimate highway-speed step from the SAME still-held anchor:
        // ~1.1 km in 30 s ≈ 132 kph — under the 160 kph ceiling, must accumulate.
        val d = odo.add(40.01, -119.0, GOOD_ACCURACY_M, 30_000L)
        assertEquals(1_111.9, d, 5.0)
        assertEquals(1_111.9, odo.tripMeters, 5.0)
    }

    @Test
    fun the_lifetime_total_never_moves_for_a_rejected_step() {
        val odo = TripOdometer(totalSeedM = 5_000.0)
        odo.add(40.0, -119.0, GOOD_ACCURACY_M, 0L)
        odo.add(41.8, -119.0, GOOD_ACCURACY_M, 10_000L) // teleport, rejected
        odo.add(40.0, -119.0, BAD_ACCURACY_M, 20_000L) // low accuracy, rejected
        assertEquals(5_000.0, odo.totalMeters, 0.0)
    }

    @Test
    fun a_fast_highway_drive_under_the_speed_ceiling_is_not_rejected() {
        // 150 kph (41.67 m/s), safely under the 160 kph ceiling — must
        // accumulate, not be treated as a teleport. Pure latitude delta so the
        // haversine distance is exact (R * dLat_rad, no small-angle or cos(lat)
        // approximation), keeping the assertion tight.
        val odo = TripOdometer()
        odo.add(40.0, -119.0, GOOD_ACCURACY_M, 0L)
        val d = odo.add(40.0033727, -119.0, GOOD_ACCURACY_M, 9_000L) // ~375 m north in 9 s
        assertEquals(375.0, d, 1.0)
        assertEquals(375.0, odo.tripMeters, 1.0)
    }

    @Test
    fun a_step_with_non_positive_elapsed_time_is_rejected() {
        // Duplicate or out-of-order fix timestamps can't establish a speed —
        // bias toward rejecting rather than dividing by a bad interval.
        val odo = TripOdometer()
        odo.add(0.0, 0.0, GOOD_ACCURACY_M, 10_000L)
        val d = odo.add(0.0, 0.001, GOOD_ACCURACY_M, 10_000L) // same timestamp, dt = 0
        assertEquals(0.0, d, 0.0)
    }
}
