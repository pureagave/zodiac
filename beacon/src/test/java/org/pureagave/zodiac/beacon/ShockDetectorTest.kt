package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ShockDetector] now reports the *peak* of a short collection window after a
 * threshold crossing, not the sample that crossed it (AUDIT-2026-08-09 C7).
 * These tests drive that window explicitly by timestamp so they fail for the
 * reason they claim rather than by accident of default constants.
 *
 * Dropped from the previous suite: `negative_magnitude_never_fires`. The input
 * is `sqrt(x²+y²+z²)`, which cannot be negative — the caller (`onLinearAcceleration`
 * in `TelemetryBroadcaster`) can never produce one, so that test exercised dead
 * input space and could not fail for any reason connected to real behavior.
 */
class ShockDetectorTest {
    @Test
    fun below_threshold_never_opens_a_window() {
        val det = ShockDetector(thresholdG = 1.5, peakWindowMs = 100L)
        assertNull(det.sample(magnitudeMps2 = 9.8, nowMs = 0L)) // ~1.0 g, normal jostle
        // If a window had (wrongly) opened, this later sample would close it and
        // report — confirm it stays silent well past where a window would close.
        assertNull(det.sample(magnitudeMps2 = 9.8, nowMs = 200L))
    }

    @Test
    fun a_crossing_sample_does_not_fire_immediately() {
        // This is the heart of C7: the sample that first crosses threshold must
        // NOT itself be the reported value — it only opens the window.
        val det = ShockDetector(thresholdG = 1.5, peakWindowMs = 100L)
        assertNull(det.sample(magnitudeMps2 = 20.0, nowMs = 0L)) // ~2.04 g, opens the window
    }

    @Test
    fun a_rising_impulse_reports_its_peak_not_the_first_sample() {
        // Onset at 2.04 g, rises to a true peak of ~3.06 g, then decays — the
        // reported value must be the peak, not the 2.04 g leading edge that
        // crossed threshold, and not the decaying tail either.
        val det = ShockDetector(thresholdG = 1.5, peakWindowMs = 100L)
        assertNull(det.sample(20.0, 0L)) // ~2.04 g onset — opens window
        assertNull(det.sample(25.0, 20L)) // ~2.55 g rising
        assertNull(det.sample(30.0, 40L)) // ~3.06 g true peak
        assertNull(det.sample(22.0, 60L)) // ~2.24 g decaying
        val reported = det.sample(21.0, 100L) // window closes (100ms elapsed)
        assertNotNull(reported)
        assertEquals(3.059, reported!!, 0.01)
    }

    @Test
    fun window_stays_silent_until_it_closes() {
        val det = ShockDetector(thresholdG = 1.5, peakWindowMs = 100L)
        assertNull(det.sample(20.0, 0L)) // opens
        assertNull(det.sample(20.0, 50L)) // still collecting, 50 < 100
        assertNull(det.sample(20.0, 99L)) // still collecting, 99 < 100
        assertNotNull(det.sample(20.0, 100L)) // 100 - 0 >= 100 -> closes and reports
    }

    @Test
    fun refractory_starts_when_the_window_closes_not_when_it_opens() {
        val det = ShockDetector(thresholdG = 1.5, refractoryMs = 500L, peakWindowMs = 100L)
        assertNull(det.sample(20.0, 0L)) // opens window at t=0
        assertNotNull(det.sample(20.0, 100L)) // window closes -> report at t=100; refractory runs until t=600
        // If refractory had instead started at the window's OPEN time (t=0), it
        // would already have expired by t=549 (0 + 500 = 500), and this sample
        // would incorrectly open a fresh window right here — which itself also
        // returns null (a window that just opened doesn't report yet), so this
        // assertion alone can't tell "correctly suppressed" apart from
        // "wrongly opened a window nobody's closed yet".
        assertNull(det.sample(30.0, 549L))
        // The follow-up sample resolves the ambiguity: if a window had wrongly
        // opened at t=549, it closes here (549 + 100 = 649) and reports
        // non-null. Correct behavior has no window open at t=549 (refractory
        // genuinely still active until t=600), so this sample instead opens a
        // brand new window at t=649 and is itself null.
        assertNull(det.sample(30.0, 649L))
    }

    @Test
    fun two_impacts_inside_the_refractory_report_once() {
        val det = ShockDetector(thresholdG = 1.5, refractoryMs = 500L, peakWindowMs = 100L)
        assertNull(det.sample(20.0, 0L)) // opens window #1
        assertNotNull(det.sample(20.0, 100L)) // window #1 closes -> report #1, refractory arms at t=100
        // A second, separate impact arrives well inside the 500 ms refractory
        // (even though it crosses threshold on its own) — must be swallowed
        // entirely, never opening a second window.
        assertNull(det.sample(30.0, 150L))
        assertNull(det.sample(30.0, 300L))
        assertNull(det.sample(30.0, 599L)) // still inside refractory (100 + 500 = 600)
    }

    @Test
    fun an_impact_after_the_refractory_reports_again() {
        val det = ShockDetector(thresholdG = 1.5, refractoryMs = 500L, peakWindowMs = 100L)
        assertNull(det.sample(20.0, 0L)) // opens window #1
        assertNotNull(det.sample(20.0, 100L)) // report #1 at t=100; refractory until t=600
        assertNull(det.sample(30.0, 601L)) // refractory just cleared -> opens window #2
        assertNotNull(det.sample(30.0, 701L)) // window #2 closes -> report #2
    }

    @Test
    fun exactly_at_threshold_opens_the_window() {
        // The gate is `g < thresholdG`, so a sample landing *exactly* on the
        // threshold opens a window, it isn't a miss. Pick a threshold equal to
        // 20 m/s² in g so the same float division reproduces it precisely.
        val g20 = 20.0 / 9.80665
        val det = ShockDetector(thresholdG = g20, peakWindowMs = 100L)
        assertNull(det.sample(magnitudeMps2 = 20.0, nowMs = 0L)) // opens, doesn't fire yet
        assertNotNull(det.sample(magnitudeMps2 = 20.0, nowMs = 100L)) // closes -> reports
    }

    @Test
    fun a_lower_sample_inside_the_window_never_lowers_the_reported_peak() {
        val det = ShockDetector(thresholdG = 1.5, peakWindowMs = 100L)
        assertNull(det.sample(30.0, 0L)) // ~3.06 g onset is already the peak
        assertNull(det.sample(16.0, 50L)) // ~1.63 g decaying tail, still above threshold
        val reported = det.sample(16.0, 100L)
        assertNotNull(reported)
        assertEquals(3.059, reported!!, 0.01) // still the onset value, not the tail
    }
}
