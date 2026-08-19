"""The scalar decisions behind thermal normalisation and track association.

Both real failures of 2026-08-07 were in this arithmetic, and neither was
catchable without hardware because neither had a test. The first two classes
here are written so that each of those bugs fails loudly if reintroduced.
"""

import unittest

from zvision.normalize import (
    DEFAULT_MIN_SPREAD,
    REBASELINE_FG_FRACTION,
    REBASELINE_SETTLE_FRAMES,
    CameraStallGuard,
    ReBaselineGuard,
    TRACK_ID_LIMIT,
    _mint_id,
    associate_tracks,
    image_rows,
    stretch_window,
)


class StretchCentreTest(unittest.TestCase):
    """Bug 1: smoothing the *centre* left the mapping trailing the data and
    made background subtraction useless (background movement went from 37.8 to
    128.7 of 255 levels). The centre must track the frame exactly."""

    def test_centre_is_used_unsmoothed(self):
        # Same steady spread, but the scene's DC level jumps — as it does on a
        # flat-field correction. The window must follow it immediately.
        lo1, scale1, ema = stretch_window(1000.0, 400.0, 400.0)
        lo2, scale2, _ = stretch_window(5000.0, 400.0, ema)
        self.assertAlmostEqual(4000.0, lo2 - lo1, places=6)
        self.assertAlmostEqual(scale1, scale2, places=6)

    def test_a_drifting_centre_never_lags(self):
        ema = None
        for centre in (1000.0, 1200.0, 1500.0, 900.0):
            lo, scale, ema = stretch_window(centre, 500.0, ema)
            # The window is always exactly centred on this frame.
            self.assertAlmostEqual(centre, lo + scale, places=6)


class StretchScaleTest(unittest.TestCase):
    """The scale, unlike the centre, *is* smoothed — that's what stops a hot
    object entering the frame from inflating the range and darkening
    everything else."""

    def test_first_frame_adopts_the_spread(self):
        _, _, ema = stretch_window(0.0, 640.0, None)
        self.assertAlmostEqual(640.0, ema, places=6)

    def test_a_sudden_hot_object_barely_moves_the_scale(self):
        # Settled on a quiet scene, then someone walks in and the frame's
        # contrast triples. One frame must stay far nearer the settled value
        # than the new one — that gap is what keeps the hot object an outlier
        # instead of something that rescales the whole image.
        settled, intruded = 600.0, 1800.0
        _, _, ema = stretch_window(0.0, intruded, settled, alpha=0.05)
        moved = (ema - settled) / (intruded - settled)
        self.assertLess(moved, 0.15, "one frame should not chase a transient")
        self.assertGreater(ema, settled, "but it should still move toward it")

    def test_the_scale_still_follows_a_sustained_change(self):
        ema = 600.0
        for _ in range(200):
            _, _, ema = stretch_window(0.0, 1800.0, ema, alpha=0.05)
        self.assertAlmostEqual(1800.0, ema, delta=5.0)


class GainFloorTest(unittest.TestCase):
    """Bug 2: with no floor, a thermally flat scene was amplified until sensor
    noise became contacts — 77 spurious detections in three minutes."""

    def test_a_flat_scene_does_not_get_amplified(self):
        # Measured reality: a still room gave a spread of ~51 counts against
        # ~37 counts of noise. Without a floor that is enormous gain.
        _, scale, _ = stretch_window(30000.0, 51.0, 51.0)
        self.assertEqual(DEFAULT_MIN_SPREAD, scale)

    def test_real_contrast_is_left_alone(self):
        # A person produces ~600 counts; the floor must not touch that.
        _, scale, _ = stretch_window(30000.0, 600.0, 600.0)
        self.assertAlmostEqual(600.0, scale, places=6)

    def test_the_floor_bounds_the_gain_not_the_ema(self):
        # The smoothed value keeps tracking the real (tiny) spread — only the
        # applied scale is clamped — so recovery is immediate when contrast
        # returns rather than having to climb back from the floor.
        _, scale, ema = stretch_window(0.0, 10.0, 10.0)
        self.assertEqual(DEFAULT_MIN_SPREAD, scale)
        self.assertAlmostEqual(10.0, ema, places=6)

    def test_floor_is_configurable(self):
        _, scale, _ = stretch_window(0.0, 5.0, 5.0, min_spread=1000.0)
        self.assertEqual(1000.0, scale)


class StretchRobustnessTest(unittest.TestCase):
    def test_a_perfectly_flat_frame_cannot_divide_by_zero(self):
        # Mid-FFC the shutter is closed and every pixel reads the same.
        lo, scale, ema = stretch_window(100.0, 0.0, None)
        self.assertGreater(scale, 0.0)
        self.assertGreaterEqual(ema, 1.0)

    def test_non_finite_inputs_do_not_poison_the_window(self):
        lo, scale, ema = stretch_window(float("nan"), float("inf"), None)
        self.assertEqual(lo, lo)          # not NaN
        self.assertGreater(scale, 0.0)

    def test_a_corrupt_ema_recovers(self):
        _, scale, ema = stretch_window(0.0, 500.0, float("nan"))
        self.assertAlmostEqual(500.0, ema, places=6)


class TelemetryCropTest(unittest.TestCase):
    """Both halves of the 2026-08-07 telemetry-row trap, pinned. Cropping too
    little leaves a permanent band of false motion along the bottom edge;
    cropping unconditionally eats two real image rows off the mode that has no
    telemetry at all."""

    def test_the_taller_y16_mode_loses_exactly_its_telemetry_rows(self):
        self.assertEqual(120, image_rows(122, 120))

    def test_a_frame_at_sensor_height_is_untouched(self):
        # Asking the driver for 120 returns 120: there is nothing to crop, and
        # the first fix cropped anyway, silently discarding real image.
        self.assertEqual(120, image_rows(120, 120))

    def test_a_short_frame_is_never_shortened_further(self):
        self.assertEqual(118, image_rows(118, 120))


class TrackAssignmentTest(unittest.TestCase):
    """Stable ids are load-bearing: the collision estimator measures bearing
    *rate* per id, and the tracker light latches onto one. Churn makes a
    standing person look like a stream of new contacts.

    Single-blob cases exercise ``associate_tracks`` with a 1-element centroid
    list — the global-nearest batch pass degenerates to the old per-blob
    behaviour when there is only one blob to place."""

    def test_a_lone_blob_gets_a_fresh_id(self):
        ids, nxt = associate_tracks([(0.5, 0.5)], {}, 0.15, 7)
        self.assertEqual([7], ids)
        self.assertEqual(8, nxt)

    def test_a_small_movement_keeps_the_same_id(self):
        tracks = {3: (0.50, 0.50)}
        ids, nxt = associate_tracks([(0.52, 0.51)], tracks, 0.15, 9)
        self.assertEqual([3], ids)
        self.assertEqual(9, nxt)  # no id consumed

    def test_a_jump_beyond_the_window_is_a_new_contact(self):
        tracks = {3: (0.10, 0.10)}
        ids, nxt = associate_tracks([(0.90, 0.90)], tracks, 0.15, 9)
        self.assertEqual([9], ids)
        self.assertEqual(10, nxt)

    def test_the_nearest_track_wins(self):
        tracks = {1: (0.40, 0.50), 2: (0.55, 0.50)}
        ids, _ = associate_tracks([(0.54, 0.50)], tracks, 0.3, 9)
        self.assertEqual([2], ids)

    def test_two_blobs_near_one_track_get_distinct_ids(self):
        # Two blobs must not collapse onto one id — that would make a single
        # contact appear to teleport between two people.
        tracks = {1: (0.50, 0.50)}
        ids, nxt = associate_tracks([(0.50, 0.50), (0.53, 0.50)], tracks, 0.15, 9)
        self.assertEqual(2, len(set(ids)))
        self.assertEqual(1, ids[0])  # the closer one inherits the track
        self.assertEqual(9, ids[1])  # the other is genuinely new
        self.assertEqual(10, nxt)

    def test_match_distance_boundary_is_exclusive(self):
        tracks = {1: (0.0, 0.0)}
        # exactly at the threshold does not match (strict <)
        ids, _ = associate_tracks([(0.15, 0.0)], tracks, 0.15, 9)
        self.assertEqual([9], ids)
        ids, _ = associate_tracks([(0.149, 0.0)], tracks, 0.15, 9)
        self.assertEqual([1], ids)

    def test_matching_is_euclidean_not_per_axis(self):
        # 0.11 in each axis is 0.156 away — outside a 0.15 window, even though
        # neither axis alone exceeds it.
        tracks = {1: (0.0, 0.0)}
        ids, _ = associate_tracks([(0.11, 0.11)], tracks, 0.15, 9)
        self.assertEqual([9], ids)

    def test_ids_keep_increasing_across_many_new_contacts(self):
        nxt = 1
        ids = []
        for i in range(5):
            frame_ids, nxt = associate_tracks([(float(i), 0.0)], {}, 0.15, nxt)
            ids.append(frame_ids[0])
        self.assertEqual([1, 2, 3, 4, 5], ids)


class AssociateTracksGlobalNearestTest(unittest.TestCase):
    """The whole point of the finding 4 rewrite: association must be a single
    global-nearest pass over the frame, not a per-blob greedy one. A per-blob
    pass can let a blob processed earlier grab a track that a later blob is
    actually closer to — teleporting that track's bearing (masking a real
    constant-bearing collision) and re-minting an id for the blob that should
    have kept it."""

    def test_the_globally_closer_blob_inherits_the_track_not_the_first_one(self):
        # Track sits at 0.50. blob0=0.42 (d=0.08) is farther than
        # blob1=0.54 (d=0.04), but appears FIRST in contour order. A per-blob
        # greedy pass would let blob0 claim the track since it's processed
        # first and is still within match_dist; the correct, order-independent
        # answer is that blob1 — the physically closer one — gets it.
        tracks = {1: (0.50, 0.5)}
        centroids = [(0.42, 0.5), (0.54, 0.5)]
        ids, nxt = associate_tracks(centroids, tracks, 0.15, 9)
        self.assertEqual([9, 1], ids, "the farther blob must mint, not steal, the track")
        self.assertEqual(10, nxt)

    def test_the_result_does_not_depend_on_contour_order(self):
        # Same two blobs, reversed input order: the physically closer blob
        # (at 0.54) must still be the one that inherits track 1, regardless
        # of which index it now occupies.
        tracks = {1: (0.50, 0.5)}
        centroids = [(0.54, 0.5), (0.42, 0.5)]
        ids, _ = associate_tracks(centroids, tracks, 0.15, 9)
        self.assertEqual([1, 9], ids, "the closer blob keeps the track however it's ordered")


class TrackIdWrapTest(unittest.TestCase):
    """Ids are namespaced into per-camera blocks by the rig, so a local id must
    never leave its block — see TRACK_ID_LIMIT. This wrap/mint logic is
    load-bearing and is tested directly against ``_mint_id``, independent of
    the association pass that calls it."""

    def test_ids_wrap_instead_of_growing_without_bound(self):
        tid, nxt = _mint_id(TRACK_ID_LIMIT - 1, {}, set())
        self.assertEqual(TRACK_ID_LIMIT - 1, tid)
        self.assertEqual(1, nxt, "must wrap back into the block, not reach the limit")

    def test_the_wrap_never_yields_the_adhoc_sentinel(self):
        nxt = 1
        minted = set()
        for _ in range(TRACK_ID_LIMIT * 2):
            tid, nxt = _mint_id(nxt, {}, set())
            minted.add(tid)
        self.assertNotIn(0, minted, "0 is reserved for ad-hoc contacts")
        self.assertTrue(all(0 < i < TRACK_ID_LIMIT for i in minted))

    def test_a_custom_limit_is_honoured(self):
        tid, nxt = _mint_id(4, {}, set(), id_limit=5)
        self.assertEqual(4, tid)
        self.assertEqual(1, nxt)

    def test_a_lapped_counter_never_mints_an_id_still_tracking_someone(self):
        # The counter has gone all the way round while track 1 — a stationary
        # person, maybe the one the DMX light is latched onto — stayed alive
        # the whole time. Reissuing their id to a new blob splices two people
        # into one track: the collision estimator sees a teleporting bearing
        # and suppresses a real alarm, and the light follows the wrong person.
        tracks = {1: (0.9, 0.9)}
        tid, nxt = _mint_id(1, tracks, set())
        self.assertNotIn(tid, tracks, "minted a live track's id")
        self.assertEqual(2, tid)
        self.assertEqual(3, nxt)

    def test_a_lapped_counter_skips_ids_already_handed_out_this_frame(self):
        claimed = {1}
        tid, _ = _mint_id(1, {}, claimed)
        self.assertNotIn(tid, claimed)
        self.assertEqual(2, tid)

    def test_skipping_a_live_id_wraps_without_reaching_zero(self):
        # The last id in the block is alive; the skip must lap to 1, never 0.
        top = TRACK_ID_LIMIT - 1
        tracks = {top: (0.9, 0.9)}
        tid, _ = _mint_id(top, tracks, set())
        self.assertEqual(1, tid)


if __name__ == "__main__":
    unittest.main()


class ReBaselineGuardTest(unittest.TestCase):
    """A flat-field correction re-baselines every pixel at once, and the
    background subtractor calls the whole frame foreground. Measured on the real
    board 2026-08-08: 100% of pixels moving, median stepping 128-232 counts,
    about every 3 minutes. The overnight empty-room run turned that into 10
    phantom collision flags in 7.7 hours — one false "! BRAKE !" every 45
    minutes, which is how a driver learns to ignore the real one."""

    def test_an_ordinary_scene_passes_straight_through(self):
        g = ReBaselineGuard()
        for fg in (0.0, 0.01, 0.05, 0.2):
            self.assertFalse(g.suppress(fg), f"{fg} is a scene, not a re-baseline")

    def test_a_whole_frame_step_is_suppressed(self):
        self.assertTrue(ReBaselineGuard().suppress(1.0))

    def test_a_person_close_to_the_camera_is_not_mistaken_for_one(self):
        # The gap is enormous — a body fills a few percent of a 160x120 frame,
        # an FFC fills all of it — so this threshold never has to be delicate.
        self.assertFalse(ReBaselineGuard().suppress(0.25))

    def test_the_frames_after_a_step_are_suppressed_too(self):
        # MOG2 needs a few frames to absorb the new baseline; the residual
        # foreground right after a step is still large.
        g = ReBaselineGuard(settle_frames=3)
        self.assertTrue(g.suppress(1.0))
        for _ in range(3):
            self.assertTrue(g.suppress(0.02), "still settling")
        self.assertFalse(g.suppress(0.02), "back to normal")

    def test_detection_resumes_promptly(self):
        # The blind window is the cost of this guard; it must stay short. At
        # ~9 fps, three frames is about a third of a second.
        g = ReBaselineGuard()
        g.suppress(1.0)
        frames = 0
        while g.suppress(0.0):
            frames += 1
            self.assertLess(frames, 10, "guard must not latch open")
        self.assertLessEqual(frames, REBASELINE_SETTLE_FRAMES)

    def test_a_second_step_while_settling_restarts_the_window(self):
        g = ReBaselineGuard(settle_frames=2)
        g.suppress(1.0)
        g.suppress(0.01)
        self.assertTrue(g.suppress(1.0), "another step")
        self.assertTrue(g.suppress(0.01))
        self.assertTrue(g.suppress(0.01))
        self.assertFalse(g.suppress(0.01))

    def test_back_to_back_steps_do_not_leave_it_stuck(self):
        g = ReBaselineGuard()
        for _ in range(50):
            g.suppress(1.0)
        frames = 0
        while g.suppress(0.0):
            frames += 1
            self.assertLess(frames, 10)

    def test_a_corrupt_frame_is_suppressed_rather_than_trusted(self):
        self.assertTrue(ReBaselineGuard().suppress(float("nan")))

    def test_the_threshold_sits_far_from_both_real_cases(self):
        # Guards the constant itself: well above any plausible body, well below
        # the measured 100%.
        self.assertGreater(REBASELINE_FG_FRACTION, 0.3)
        self.assertLess(REBASELINE_FG_FRACTION, 0.9)

    def test_settling_is_visible_for_logging(self):
        g = ReBaselineGuard()
        self.assertFalse(g.settling)
        g.suppress(1.0)
        self.assertTrue(g.settling)


class CameraStallGuardTest(unittest.TestCase):
    """The Jetson cold-boot race (2026-08-13): a camera opened before its device
    was streaming-ready wedged in select() timeout for two hours and never
    recovered. The guard must reopen a stalled camera on a bounded cadence, and
    must never reopen a healthy one."""

    @staticmethod
    def _guard(times, stall=6.0):
        it = iter(times)
        return CameraStallGuard(stall_secs=stall, clock=lambda: next(it))

    def test_healthy_camera_never_reopens(self):
        # Frames keep arriving, even across a long wall-clock gap: no reopen.
        g = self._guard([0.0, 1.0, 2.0, 100.0])
        self.assertFalse(g.note(True))
        self.assertFalse(g.note(True))
        self.assertFalse(g.note(True))
        self.assertFalse(g.note(True))

    def test_stall_triggers_reopen_at_the_boundary(self):
        # ok at t=0, then no frames; reopen exactly when >= stall_secs elapses.
        g = self._guard([0.0, 2.0, 5.0, 6.0], stall=6.0)
        self.assertFalse(g.note(True))   # t=0 ok, clock starts
        self.assertFalse(g.note(False))  # t=2, 2 < 6
        self.assertFalse(g.note(False))  # t=5, 5 < 6
        self.assertTrue(g.note(False))   # t=6, 6 >= 6 -> reopen

    def test_first_read_failing_graces_warmup_then_reopens(self):
        # A camera blind from the very first read (the boot wedge) still recovers.
        g = self._guard([0.0, 3.0, 6.0], stall=6.0)
        self.assertFalse(g.note(False))  # first-ever obs is a failure: start clock
        self.assertFalse(g.note(False))  # t=3 < 6
        self.assertTrue(g.note(False))   # t=6 >= 6 -> reopen

    def test_reopen_resets_the_backoff(self):
        # A dead camera is retried once per stall_secs, not every frame.
        g = self._guard([0.0, 6.0, 9.0, 12.0], stall=6.0)
        self.assertFalse(g.note(False))  # t=0 first fail
        self.assertTrue(g.note(False))   # t=6 -> reopen, clock resets to 6
        self.assertFalse(g.note(False))  # t=9, 9-6=3 < 6
        self.assertTrue(g.note(False))   # t=12, 12-6=6 -> reopen again

    def test_recovery_clears_the_stall(self):
        g = self._guard([0.0, 6.0, 7.0, 200.0], stall=6.0)
        self.assertFalse(g.note(False))  # t=0 first fail
        self.assertTrue(g.note(False))   # t=6 reopen
        self.assertFalse(g.note(True))   # t=7 a frame arrives -> healthy again
        self.assertFalse(g.note(True))   # t=200 still healthy, no reopen


class CameraDeliveringTest(unittest.TestCase):
    """The blind-arc liveness signal (RES-P2-1). ``delivering`` answers "did a
    frame actually arrive recently", which must be tracked separately from the
    reopen backoff — reading ``_last_ok`` would let a dead camera look alive for
    a stall interval after every reopen, the exact false all-clear this exists to
    prevent."""

    @staticmethod
    def _guard(times, stall=6.0):
        it = iter(times)
        return CameraStallGuard(stall_secs=stall, clock=lambda: next(it))

    def test_a_recent_frame_counts_as_delivering(self):
        g = self._guard([0.0, 0.5])
        g.note(True)                                 # t=0 frame delivered
        self.assertTrue(g.delivering(3.0))           # t=0.5, well within the window

    def test_a_camera_never_delivering_is_not_delivering(self):
        g = self._guard([100.0])
        self.assertFalse(g.delivering(3.0))          # no frame ever

    def test_a_stale_frame_is_not_delivering(self):
        g = self._guard([0.0, 4.0])
        g.note(True)                                 # t=0
        self.assertFalse(g.delivering(3.0))          # t=4, last frame 4 s ago

    def test_boundary_just_inside_the_window_still_delivers(self):
        g = self._guard([0.0, 2.9])
        g.note(True)
        self.assertTrue(g.delivering(3.0))

    def test_boundary_just_outside_the_window_is_blind(self):
        g = self._guard([0.0, 3.1])
        g.note(True)
        self.assertFalse(g.delivering(3.0))

    def test_a_reopen_does_not_masquerade_as_a_delivered_frame(self):
        # The load-bearing separation: note(False) at the stall boundary fires a
        # reopen and resets _last_ok — but NOT _last_frame. Liveness must still
        # read from the last real frame (t=0), not the reopen (t=6). If someone
        # "simplifies" delivering() back onto _last_ok, this fails.
        g = self._guard([0.0, 6.0, 6.5], stall=6.0)
        self.assertFalse(g.note(True))               # t=0 real frame -> _last_frame=0
        self.assertTrue(g.note(False))               # t=6 stall -> reopen, resets _last_ok only
        self.assertFalse(g.delivering(3.0))          # t=6.5, last real frame was 6.5 s ago

    def test_a_reopen_read_as_last_ok_would_falsely_deliver(self):
        # Positive-control twin of the mutation test: with a SHORT enough gap the
        # real frame is still inside the window, proving delivering() isn't just
        # always-False. t=0 frame, reopen at t=6, query at t=2.5 would be inside
        # a 3 s window — but sequencing keeps the query after the frame.
        g = self._guard([0.0, 2.5], stall=6.0)
        g.note(True)                                 # t=0
        self.assertTrue(g.delivering(3.0))           # t=2.5, inside the window
