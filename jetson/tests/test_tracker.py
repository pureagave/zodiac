import unittest

from zvision.threat import DriverThreat
from zvision.tracker import (
    NINE_CHANNEL_OVERRIDES,
    config_for_channel_mode,
    nearest_equivalent_pan,
    reachable,
    Tracker,
    TrackerConfig,
    deg_to_dmx16,
    select_best,
)

ONE_CONTACT = [DriverThreat(rel_az_deg=10.0, size=0.5, id=1)]
SNAP = 1.0e9  # dt large enough to defeat the slew limiter and land on target


class SelectBestTest(unittest.TestCase):
    def test_empty_scene_selects_nothing(self):
        self.assertIsNone(select_best([]))

    def test_nearest_by_size_wins_when_no_collision(self):
        near = DriverThreat(rel_az_deg=5.0, size=0.8, id=1)
        far = DriverThreat(rel_az_deg=-5.0, size=0.3, id=2)
        self.assertEqual(1, select_best([far, near]).id)

    def test_collision_beats_a_nearer_non_collision(self):
        looming = DriverThreat(rel_az_deg=1.0, size=0.4, collision=True, id=1)
        closer = DriverThreat(rel_az_deg=-1.0, size=0.9, collision=False, id=2)
        self.assertEqual(1, select_best([closer, looming]).id)


class DmxMappingTest(unittest.TestCase):
    def test_zero_maps_to_zero(self):
        self.assertEqual((0, 0), deg_to_dmx16(0.0, 540.0))

    def test_full_scale_maps_to_max(self):
        self.assertEqual((255, 255), deg_to_dmx16(540.0, 540.0))

    def test_mid_scale_is_half(self):
        coarse, _ = deg_to_dmx16(270.0, 540.0)
        self.assertEqual(128, coarse)

    def test_out_of_range_is_clamped(self):
        self.assertEqual((0, 0), deg_to_dmx16(-90.0, 540.0))
        self.assertEqual((255, 255), deg_to_dmx16(900.0, 540.0))

    def test_dead_axis_parks_at_zero(self):
        self.assertEqual((0, 0), deg_to_dmx16(90.0, 0.0))

    def test_non_finite_deg_parks_at_zero(self):
        # The park sentinel is NaN; round(nan) raises, so deg_to_dmx16 must guard
        # non-finite angles and park at 0 rather than crash the frame.
        self.assertEqual((0, 0), deg_to_dmx16(float("nan"), 540.0))
        self.assertEqual((0, 0), deg_to_dmx16(float("inf"), 540.0))
        self.assertEqual((0, 0), deg_to_dmx16(float("-inf"), 540.0))


class TrackerAimTest(unittest.TestCase):
    def test_dead_ahead_points_at_pan_center(self):
        trk = Tracker(TrackerConfig())  # pan_center 270 of a 540 sweep
        f = trk.update([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)], dt=1.0e9)
        self.assertEqual(128, f.channels[1])  # pan coarse ~ mid-scale
        self.assertEqual(1, f.target_id)

    def test_right_contact_pans_past_center(self):
        trk = Tracker(TrackerConfig())
        f = trk.update([DriverThreat(rel_az_deg=20.0, size=0.5, id=1)], dt=1.0e9)
        self.assertGreater(f.pan_deg, 270.0)  # +az => pan past center

    def test_slew_limits_movement_per_frame(self):
        # 120 deg/s * 0.1 s = 12 deg max step; a 28.5 deg swing can't complete.
        trk = Tracker(TrackerConfig(pan_slew_dps=120.0))
        f = trk.update([DriverThreat(rel_az_deg=28.5, size=0.5, id=1)], dt=0.1)
        self.assertAlmostEqual(282.0, f.pan_deg, places=3)

    def test_dimmer_full_on_target_dark_when_idle(self):
        trk = Tracker(TrackerConfig())
        lit = trk.update([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)], dt=1.0e9)
        self.assertEqual(255, lit.dimmer)
        dark = trk.update([], dt=1.0e9)
        self.assertEqual(0, dark.dimmer)
        self.assertIsNone(dark.target_id)

    def test_idle_holds_aim_rather_than_sweeping(self):
        trk = Tracker(TrackerConfig())
        trk.update([DriverThreat(rel_az_deg=20.0, size=0.5, id=1)], dt=1.0e9)
        held = trk.update([], dt=1.0e9).pan_deg
        self.assertAlmostEqual(290.0, held, places=3)  # stays where it last aimed

    def test_park_recenters_and_blacks_out(self):
        cfg = TrackerConfig()
        trk = Tracker(cfg)
        trk.update([DriverThreat(rel_az_deg=25.0, size=0.9, id=1)], dt=1.0e9)
        p = trk.park()
        self.assertEqual(128, p.channels[1])  # back to pan center
        # Read the channel from the config rather than hardcoding it. This test
        # asserted channel 5 and so agreed with a wiring bug: on this fixture 5
        # is the colour wheel and the dimmer is 8 (see MOVING-HEAD.md).
        self.assertEqual(0, p.channels[cfg.dimmer_channel])
        self.assertIsNone(p.target_id)

    def test_the_dimmer_is_not_wired_to_the_colour_wheel(self):
        # Channel 5 on this head is Colour, where 140-255 means "auto colour
        # change, fast". Writing brightness there spins colours at full speed
        # while the real dimmer sits at 0 and the fixture looks dead.
        self.assertEqual(8, TrackerConfig().dimmer_channel)

    def test_the_reset_and_auto_program_channels_are_never_driven(self):
        # ch11 held at 250-255 for 5 s triggers a motor reset; ch10 above 59
        # hands the head to its internal programs. Either mid-show is a
        # fixture that stops obeying us.
        cfg = TrackerConfig()
        trk = Tracker(cfg)
        frame = trk.update([DriverThreat(rel_az_deg=10.0, size=0.5, id=1)], dt=1.0e9)
        for channel in cfg.forbidden_channels:
            self.assertNotIn(channel, frame.channels)
            self.assertNotIn(channel, trk.park().channels)


class ChannelModeConfigTest(unittest.TestCase):
    """9-channel is not "11-channel minus the fine channels". Dropping the two
    fine channels shifts everything above pan down by two, which moves the
    dimmer *and* the channels we must never drive. MOVING-HEAD.md 3.1 / 3.2."""

    def test_eleven_channel_is_the_dataclass_default(self):
        cfg, default = config_for_channel_mode(11), TrackerConfig()
        for field in (
            "pan_channel",
            "pan_fine_channel",
            "tilt_channel",
            "tilt_fine_channel",
            "dimmer_channel",
            "forbidden_channels",
        ):
            self.assertEqual(getattr(default, field), getattr(cfg, field), field)

    def test_nine_channel_moves_the_dimmer_to_six(self):
        self.assertEqual(6, config_for_channel_mode(9).dimmer_channel)

    def test_nine_channel_drops_the_fine_channels(self):
        cfg = config_for_channel_mode(9)
        self.assertEqual(1, cfg.pan_channel)
        self.assertEqual(2, cfg.tilt_channel)
        self.assertIsNone(cfg.pan_fine_channel)
        self.assertIsNone(cfg.tilt_fine_channel)

    def test_nine_channel_moves_the_reset_guard_down_to_eight_and_nine(self):
        # The regression this pins. NINE_CHANNEL_OVERRIDES used to leave
        # forbidden_channels at the 11-channel (10, 11). A 9-channel fixture
        # does not read ch10/ch11 at all, so the guard protected two channels
        # that do not exist while ch9 -- the MOTOR RESET -- and ch8 -- the auto
        # programs -- sat completely unguarded.
        self.assertEqual((8, 9), config_for_channel_mode(9).forbidden_channels)

    def test_the_two_modes_do_not_share_a_guard(self):
        self.assertNotEqual(
            config_for_channel_mode(9).forbidden_channels,
            config_for_channel_mode(11).forbidden_channels,
        )

    def test_overrides_are_applied_on_top_of_the_channel_map(self):
        cfg = config_for_channel_mode(9, pan_gain=-1.0, pan_center_deg=90.0)
        self.assertEqual(-1.0, cfg.pan_gain)
        self.assertEqual(90.0, cfg.pan_center_deg)
        self.assertEqual(6, cfg.dimmer_channel)  # map survives the overrides

    def test_an_unknown_channel_mode_is_rejected(self):
        # Silently falling back to 11 on a typo would drive tilt onto the
        # colour wheel of a 9-channel head and look like a dead fixture.
        for bad in (0, 1, 10, 12, "9", None):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                config_for_channel_mode(bad)


class ForbiddenChannelsHoldInEitherModeTest(unittest.TestCase):
    def test_neither_mode_ever_drives_its_own_forbidden_channels(self):
        for mode in (9, 11):
            with self.subTest(mode=mode):
                cfg = config_for_channel_mode(mode)
                trk = Tracker(cfg)
                frame = trk.update(
                    [DriverThreat(rel_az_deg=10.0, size=0.5, id=1)], dt=1.0e9
                )
                for channel in cfg.forbidden_channels:
                    self.assertNotIn(channel, frame.channels)
                    self.assertNotIn(channel, trk.park().channels)

    def test_the_dimmer_is_never_itself_a_forbidden_channel(self):
        # If the guard and the dimmer ever collided the head could never light.
        for mode in (9, 11):
            with self.subTest(mode=mode):
                cfg = config_for_channel_mode(mode)
                self.assertNotIn(cfg.dimmer_channel, cfg.forbidden_channels)

    def test_nine_channel_writes_stay_inside_the_nine_channel_footprint(self):
        # A 9-channel fixture reads ch1-9. Anything we emit above that is
        # landing on a channel the head does not have.
        cfg = config_for_channel_mode(9)
        trk = Tracker(cfg)
        frame = trk.update([DriverThreat(rel_az_deg=10.0, size=0.5, id=1)], dt=1.0e9)
        for channel in list(frame.channels) + list(trk.park().channels):
            self.assertLessEqual(channel, 9, f"ch{channel} is outside 9-channel mode")


class FrameEmissionTest(unittest.TestCase):
    """``_frame`` is where the config's channel map becomes actual DMX. Every
    test here asserts on *which channel carries what*, because a map that is
    correct in the dataclass and wrong in the emission looks identical from the
    config's point of view — and on the fixture it looks like a dead head."""

    def test_eleven_channel_emits_exactly_its_five_channels(self):
        trk = Tracker(config_for_channel_mode(11))
        f = trk.update(ONE_CONTACT, dt=SNAP)
        self.assertEqual({1, 2, 3, 4, 8}, set(f.channels))

    def test_nine_channel_emits_exactly_its_three_channels(self):
        trk = Tracker(config_for_channel_mode(9))
        f = trk.update(ONE_CONTACT, dt=SNAP)
        self.assertEqual({1, 2, 6}, set(f.channels))

    def test_nine_channel_puts_tilt_on_channel_two_not_pan_fine(self):
        # The single most consequential difference between the two maps. If the
        # 11-channel emission leaked into 9-channel, ch2 would carry pan's low
        # byte and the head's tilt would jerk around with sub-degree pan motion.
        cfg = config_for_channel_mode(9)
        trk = Tracker(cfg)
        f = trk.update(ONE_CONTACT, dt=SNAP)
        tilt_coarse, _ = deg_to_dmx16(f.tilt_deg, cfg.tilt_range_deg)
        _, pan_fine = deg_to_dmx16(f.pan_deg, cfg.pan_range_deg)
        self.assertEqual(tilt_coarse, f.channels[2])
        self.assertNotEqual(pan_fine, f.channels[2], "ch2 is carrying pan fine, not tilt")

    def test_the_fine_channel_is_the_low_byte_of_the_same_angle(self):
        # Wiring check, not a formula check: coarse and fine must describe one
        # 16-bit quantity. A fine channel fed from a different value would still
        # look plausible per-channel and aim wrong.
        cfg = config_for_channel_mode(11)
        trk = Tracker(cfg)
        f = trk.update([DriverThreat(rel_az_deg=17.3, size=0.37, id=1)], dt=SNAP)
        # Spans come from the config, never as literals here: this test used to
        # hardcode 540/270 and broke the moment tilt_range_deg was corrected to
        # its measured 180 -- a test repeating the implementation's magic number
        # can only ever confirm it.
        self.assertEqual(
            round(f.pan_deg / cfg.pan_range_deg * 65535),
            f.channels[1] * 256 + f.channels[2],
        )
        self.assertEqual(
            round(f.tilt_deg / cfg.tilt_range_deg * 65535),
            f.channels[3] * 256 + f.channels[4],
        )

    def test_the_fine_channel_is_what_buys_sub_coarse_resolution(self):
        # Why 11-channel exists at all: 8-bit pan over 540 deg is ~2.11 deg per
        # step, so two contacts 1 deg apart are the *same* coarse byte. In 11ch
        # the fine channel separates them; in 9ch the frames are identical.
        near = [DriverThreat(rel_az_deg=0.0, size=0.5, id=1)]
        far = [DriverThreat(rel_az_deg=1.0, size=0.5, id=1)]
        eleven = [Tracker(config_for_channel_mode(11)).update(t, dt=SNAP) for t in (near, far)]
        nine = [Tracker(config_for_channel_mode(9)).update(t, dt=SNAP) for t in (near, far)]
        self.assertEqual(eleven[0].channels[1], eleven[1].channels[1])  # same coarse
        self.assertNotEqual(eleven[0].channels[2], eleven[1].channels[2])  # fine separates
        self.assertEqual(nine[0].channels, nine[1].channels)  # 9ch cannot tell them apart

    def test_a_fixture_with_no_master_dimmer_emits_no_dimmer_channel(self):
        # dimmer_channel=None is documented as supported. The level must still be
        # reported on the frame so callers can log it.
        trk = Tracker(TrackerConfig(dimmer_channel=None))
        f = trk.update(ONE_CONTACT, dt=SNAP)
        self.assertEqual({1, 2, 3, 4}, set(f.channels))
        self.assertEqual(255, f.dimmer)

    def test_channel_numbers_come_from_the_config_not_from_constants(self):
        # Guards against anyone re-hardcoding channel numbers into _frame, which
        # is exactly how the dimmer ended up on the colour wheel once already.
        cfg = TrackerConfig(
            pan_channel=21, pan_fine_channel=22, tilt_channel=23,
            tilt_fine_channel=24, dimmer_channel=28,
        )
        f = Tracker(cfg).update(ONE_CONTACT, dt=SNAP)
        self.assertEqual({21, 22, 23, 24, 28}, set(f.channels))

    def test_park_blacks_out_the_configured_dimmer_in_either_mode(self):
        for mode, expected in ((9, 6), (11, 8)):
            with self.subTest(mode=mode):
                trk = Tracker(config_for_channel_mode(mode))
                trk.update(ONE_CONTACT, dt=SNAP)
                self.assertEqual(0, trk.park().channels[expected])

    def test_every_emitted_value_is_a_legal_dmx_byte_across_the_arc(self):
        # Sweep the reachable arc and both size extremes; nothing may leave 0-255
        # or land outside a real DMX slot.
        for mode in (9, 11):
            trk = Tracker(config_for_channel_mode(mode))
            for az in range(-90, 91, 5):
                for size in (0.0, 0.5, 1.0):
                    f = trk.update(
                        [DriverThreat(rel_az_deg=float(az), size=size, id=1)], dt=SNAP
                    )
                    for channel, value in f.channels.items():
                        self.assertTrue(1 <= channel <= 512, f"ch{channel} mode={mode}")
                        self.assertTrue(0 <= value <= 255, f"ch{channel}={value} mode={mode}")


class ChannelModeIsolationTest(unittest.TestCase):
    def test_building_a_config_does_not_mutate_the_shared_overrides(self):
        # config_for_channel_mode merges caller overrides into the module-level
        # dict; if it did so in place, the first caller's aim would leak into
        # every later 9-channel config in the process.
        before = dict(NINE_CHANNEL_OVERRIDES)
        config_for_channel_mode(9, pan_center_deg=123.0, dimmer_channel=99)
        self.assertEqual(before, NINE_CHANNEL_OVERRIDES)
        self.assertEqual(6, config_for_channel_mode(9).dimmer_channel)

    def test_eleven_channel_never_consults_the_nine_channel_map(self):
        self.assertEqual(8, config_for_channel_mode(11).dimmer_channel)
        self.assertEqual(2, config_for_channel_mode(11).pan_fine_channel)


class TiltAxisTest(unittest.TestCase):
    """The tilt axis had NO test at all until a mutation review found it: far/near
    could be swapped, or the tilt slew limiter deleted outright, and the whole
    suite stayed green. Swapped tilt puts the beam over the head of someone
    approaching and on the horizon for someone distant -- a night lost to
    calibrating around it, blaming the mount."""

    def test_tilt_tracks_size_between_the_configured_far_and_near_angles(self):
        # Expectations come from the CONFIG FIELDS, never from f.tilt_deg -- a
        # test that reads back the value it is checking agrees with any aim bug.
        cfg = TrackerConfig()
        for size, expected in ((0.0, cfg.tilt_far_deg), (1.0, cfg.tilt_near_deg)):
            with self.subTest(size=size):
                f = Tracker(cfg).update(
                    [DriverThreat(rel_az_deg=0.0, size=size, id=1)], dt=SNAP
                )
                self.assertAlmostEqual(expected, f.tilt_deg, places=3)

    def test_a_closer_contact_tilts_further_than_a_distant_one(self):
        near = Tracker(TrackerConfig()).update(
            [DriverThreat(rel_az_deg=0.0, size=0.9, id=1)], dt=SNAP)
        far = Tracker(TrackerConfig()).update(
            [DriverThreat(rel_az_deg=0.0, size=0.1, id=1)], dt=SNAP)
        self.assertGreater(near.tilt_deg, far.tilt_deg)

    def test_tilt_slew_limits_movement_per_frame(self):
        # Mirror of the pan slew test, which was the only slewing test that
        # existed. 90 deg/s * 0.1 s = 9 deg; 135 -> 160 cannot complete.
        cfg = TrackerConfig(tilt_slew_dps=90.0)
        f = Tracker(cfg).update([DriverThreat(rel_az_deg=0.0, size=1.0, id=1)], dt=0.1)
        self.assertAlmostEqual(cfg.tilt_far_deg + 9.0, f.tilt_deg, places=3)


class TiltRangeIsMeasuredNotAssumedTest(unittest.TestCase):
    """The manual (§5) says tilt is 270°. The fixture says 180°, measured three
    independent ways on the bench 2026-08-09: 64 DMX units = 45°, half-scale is
    dead vertical, and the two end stops are antiparallel and both below
    horizontal. `deg_to_dmx16` divides by this span, so the manual's number
    scaled every tilt command by 1.5 — on the axis that decides how high up a
    person's body the beam lands."""

    def test_the_measured_span_is_pinned_against_the_manual(self):
        self.assertEqual(180.0, TrackerConfig().tilt_range_deg)

    def test_ninety_degrees_lands_at_half_scale_which_is_vertical(self):
        # The observation that settled it. Under the manual's 270 this would be
        # 85 (a third of scale) and the head would sit well short of vertical.
        coarse, _ = deg_to_dmx16(90.0, TrackerConfig().tilt_range_deg)
        self.assertEqual(128, coarse)

    def test_the_bench_progression_reproduces(self):
        # 64 / 128 / 192 DMX read 45° / 90° / 135° on the real head.
        cfg = TrackerConfig()
        for deg, coarse in ((45.0, 64), (90.0, 128)):
            with self.subTest(deg=deg):
                self.assertEqual(coarse, deg_to_dmx16(deg, cfg.tilt_range_deg)[0])

    def test_the_aim_defaults_still_sit_inside_the_real_travel(self):
        # Rescaled with the span so the physical aim did not change. If either
        # ever exceeds the travel, deg_to_dmx16 silently clamps and two
        # different distances aim at the same place.
        cfg = TrackerConfig()
        for name in ("tilt_far_deg", "tilt_near_deg"):
            with self.subTest(name=name):
                self.assertLessEqual(getattr(cfg, name), cfg.tilt_range_deg)


class ConfiguredKnobsAreHonouredTest(unittest.TestCase):
    """Config added after the channel map was never wired into a test, so these
    knobs were settable but inert -- the config equivalent of dead code."""

    def test_a_narrowed_reach_arc_is_honoured_by_the_tracker(self):
        # reachable() was tested only as a free function; the Tracker always ran
        # the default arc. Re-mount the head with a narrower throw and it would
        # still have chased contacts into its own clamp.
        cfg = TrackerConfig(reach_half_deg=60.0)
        f = Tracker(cfg).update([DriverThreat(rel_az_deg=75.0, size=0.5, id=1)], dt=SNAP)
        self.assertIsNone(f.target_id)
        self.assertEqual(cfg.dimmer_idle, f.dimmer)

    def test_a_configured_park_position_is_used(self):
        cfg = TrackerConfig(park_pan_deg=10.0, park_tilt_deg=20.0)
        trk = Tracker(cfg)
        trk.update([DriverThreat(rel_az_deg=40.0, size=0.8, id=1)], dt=SNAP)
        p = trk.park()
        self.assertAlmostEqual(10.0, p.pan_deg, places=3)
        self.assertAlmostEqual(20.0, p.tilt_deg, places=3)

    def test_collision_burns_at_its_own_dimmer_level(self):
        # Both defaults are 255, so this branch was indistinguishable from dead
        # code. It stops being dead the moment collisions get their own look.
        cfg = TrackerConfig(dimmer_track=180, dimmer_collision=255)
        trk = Tracker(cfg)
        normal = trk.update([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)], dt=SNAP)
        self.assertEqual(180, normal.dimmer)
        hot = trk.update(
            [DriverThreat(rel_az_deg=0.0, size=0.5, id=1, collision=True)], dt=SNAP)
        self.assertEqual(255, hot.dimmer)


class SlewGuardTest(unittest.TestCase):
    def test_a_zero_or_negative_dt_holds_the_head_still(self):
        # app.py derives dt from a wall clock; a repeated or non-monotonic tick
        # must not become "snap straight to target", which would silently switch
        # the slew limiter off on exactly the frames the clock misbehaves.
        trk = Tracker(TrackerConfig())
        trk.update([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)], dt=SNAP)
        settled = trk.update([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)], dt=SNAP)
        for bad_dt in (0.0, -1.0):
            with self.subTest(dt=bad_dt):
                moved = trk.update(
                    [DriverThreat(rel_az_deg=40.0, size=0.5, id=1)], dt=bad_dt)
                self.assertAlmostEqual(settled.pan_deg, moved.pan_deg, places=6)


class AdHocIdTest(unittest.TestCase):
    def test_an_ad_hoc_id_zero_contact_never_takes_the_latch(self):
        # tracker.py documents that id 0 is an ad-hoc, non-stable id and must
        # never take the hysteresis latch -- the comment had no test.
        #
        # The contest has to be id-0 vs a STABLE id. Two id-0 contacts cannot
        # detect the bug: the switch-suppression test is `best.id != held.id`,
        # which is 0 != 0 -> False, so the larger contact wins either way and a
        # two-zero test passes against the missing guard. (It did. Mutation
        # testing caught it; the first version of this test was vacuous.)
        trk = Tracker(TrackerConfig())
        trk.update([DriverThreat(rel_az_deg=-20.0, size=0.5, id=0)], dt=SNAP)
        f = trk.update(
            [DriverThreat(rel_az_deg=-20.0, size=0.5, id=0),
             DriverThreat(rel_az_deg=20.0, size=0.6, id=2)],
            dt=SNAP,
        )
        # Without the guard the id-0 contact latches and its 0.15 switch margin
        # holds the beam at 250. Correct behaviour follows the larger contact.
        self.assertAlmostEqual(290.0, f.pan_deg, places=3)
        self.assertEqual(2, f.target_id)


class TrackerHysteresisTest(unittest.TestCase):
    def test_sticks_to_current_target_within_margin(self):
        trk = Tracker(TrackerConfig(switch_margin=0.15))
        trk.update([DriverThreat(rel_az_deg=0.0, size=0.60, id=1)], dt=1.0e9)
        # id 2 is nearer (0.60 vs 0.55) but not by the 0.15 margin -> keep id 1.
        f = trk.update(
            [
                DriverThreat(rel_az_deg=-5.0, size=0.55, id=1),
                DriverThreat(rel_az_deg=5.0, size=0.60, id=2),
            ],
            dt=1.0e9,
        )
        self.assertEqual(1, f.target_id)

    def test_switches_when_other_is_clearly_nearer(self):
        trk = Tracker(TrackerConfig(switch_margin=0.15))
        trk.update([DriverThreat(rel_az_deg=0.0, size=0.40, id=1)], dt=1.0e9)
        f = trk.update(
            [
                DriverThreat(rel_az_deg=-5.0, size=0.40, id=1),
                DriverThreat(rel_az_deg=5.0, size=0.60, id=2),  # beats by >= margin
            ],
            dt=1.0e9,
        )
        self.assertEqual(2, f.target_id)

    def test_collision_overrides_the_held_target(self):
        trk = Tracker(TrackerConfig())
        trk.update([DriverThreat(rel_az_deg=0.0, size=0.9, id=1)], dt=1.0e9)
        f = trk.update(
            [
                DriverThreat(rel_az_deg=0.0, size=0.9, id=1),
                DriverThreat(rel_az_deg=10.0, size=0.4, collision=True, id=2),
            ],
            dt=1.0e9,
        )
        self.assertEqual(2, f.target_id)  # collision wins regardless of size/stickiness


class TrackerSoundReactiveTest(unittest.TestCase):
    def _audio(self, rms, peak, beat):
        from zvision.audio_bus import AudioLevel

        return AudioLevel(rms=rms, peak=peak, beat=beat)

    def test_idle_pulses_to_loud_sound(self):
        trk = Tracker(TrackerConfig())  # gain 4.0
        f = trk.update([], dt=1.0e9, audio=self._audio(0.5, 0.6, False))
        self.assertEqual(255, f.dimmer)  # 0.5*4 clamps to full
        self.assertIsNone(f.target_id)

    def test_a_beat_flashes_full(self):
        trk = Tracker(TrackerConfig())
        f = trk.update([], dt=1.0e9, audio=self._audio(0.05, 0.1, True))
        self.assertEqual(255, f.dimmer) # beat overrides the quiet rms

    def test_silence_stays_dark(self):
        trk = Tracker(TrackerConfig())
        f = trk.update([], dt=1.0e9, audio=self._audio(0.001, 0.002, False))
        self.assertEqual(0, f.dimmer) # below the silence floor

    def test_quiet_music_scales_the_dimmer(self):
        trk = Tracker(TrackerConfig(sound_gain=1.0))
        f = trk.update([], dt=1.0e9, audio=self._audio(0.5, 0.6, False))
        self.assertEqual(127, f.dimmer) # 0.5 * 255

    def test_a_live_target_ignores_sound(self):
        trk = Tracker(TrackerConfig())
        f = trk.update(
            [DriverThreat(rel_az_deg=0.0, size=0.5, id=1)],
            dt=1.0e9,
            audio=self._audio(0.0, 0.0, False), # dead silence
        )
        self.assertEqual(255, f.dimmer) # full track dimmer, not the sound level
        self.assertEqual(1, f.target_id)

    def test_disabled_stays_dark_when_idle(self):
        trk = Tracker(TrackerConfig(sound_reactive=False))
        f = trk.update([], dt=1.0e9, audio=self._audio(0.9, 1.0, True))
        self.assertEqual(0, f.dimmer) # sound ignored entirely


class SternSeamTest(unittest.TestCase):
    """A fixture with more than 360 degrees of travel can reach one physical
    direction at several pan values, which makes dead astern a trap: a contact
    crossing it moves two degrees while the naive pan mapping jumps by 358."""

    def test_equivalent_pan_takes_the_short_way(self):
        # Head sits at 449 (az +179 on a 540 fixture). The contact steps across
        # the seam to az -179, whose naive pan is 91 — the same direction, 358
        # degrees away.
        self.assertAlmostEqual(451.0, nearest_equivalent_pan(91.0, 449.0, 540.0), places=6)

    def test_it_will_not_leave_the_fixtures_travel(self):
        # +360 would be mechanically unreachable, so the in-range value stands
        # even though it is further.
        self.assertAlmostEqual(10.0, nearest_equivalent_pan(10.0, 350.0, 360.0), places=6)

    def test_an_unambiguous_aim_is_unchanged(self):
        self.assertAlmostEqual(270.0, nearest_equivalent_pan(270.0, 265.0, 540.0), places=6)

    def test_an_out_of_travel_aim_uses_its_reachable_equivalent(self):
        # -20 is not reachable on a 0..540 fixture, but 340 is the same physical
        # direction and is. Returning -20 (as the old seeded search did) let the
        # caller's clamp pin the head to 0 -- an end stop pointing at nothing.
        self.assertAlmostEqual(340.0, nearest_equivalent_pan(-20.0, 60.0, 540.0), places=6)

    def test_a_calibrated_pan_centre_near_an_end_stop_still_aims_correctly(self):
        # The bug in its physical form, and the reason it stayed hidden: at the
        # default centre of 270, +/-90 of reach never leaves [180, 360] and this
        # can never fire. Mount the head so forward sits near an end of travel
        # -- entirely plausible once bodywork dictates the bracket -- and a
        # contact off that side clamps to the stop instead of using the
        # equivalent angle. On the vehicle it reads as a bad mount, not a bug.
        cfg = TrackerConfig(pan_center_deg=60.0)
        f = Tracker(cfg).update([DriverThreat(rel_az_deg=-80.0, size=0.5, id=1)], dt=SNAP)
        self.assertAlmostEqual(340.0, f.pan_deg, places=3)
        self.assertNotAlmostEqual(0.0, f.pan_deg, places=3)  # the old behaviour

    def test_the_head_does_not_sweep_the_vehicle_when_a_contact_crosses_astern(self):
        # The behaviour that matters is *cumulative travel*, not step size: the
        # slew limiter caps every individual step, so a single-step assertion
        # passes even while the head is three seconds into sweeping the wrong
        # way round the vehicle. Measure the whole journey.
        #
        # reach_half_deg=180 (wide open): at the default 90 the +/-179 contacts
        # below are outside reachable() and never get picked, so pan never
        # moves and this test passes no matter what the seam math does. Widen
        # reach here (fixture only, not a production default) so the astern
        # contacts are actually tracked and the seam-crossing math is exercised.
        cfg = TrackerConfig(pan_center_deg=270.0, pan_slew_dps=120.0, reach_half_deg=180.0)
        t = Tracker(cfg)
        for _ in range(200):  # settle onto the contact just before the seam
            f = t.update([DriverThreat(rel_az_deg=179.0, size=0.6, id=1)], 0.1)
        before = f.pan_deg

        travelled = 0.0
        prev = before
        for _ in range(200):  # let it converge on the far side of the seam
            f = t.update([DriverThreat(rel_az_deg=-179.0, size=0.6, id=1)], 0.1)
            travelled += abs(f.pan_deg - prev)
            prev = f.pan_deg

        # Two degrees of real motion. Anything approaching 358 means it went
        # the long way round, sweeping across the driver on the way.
        self.assertLess(travelled, 30.0, f"head travelled {travelled:.0f} deg for a 2 deg move")


class ParkOnExitTest(unittest.TestCase):
    """The shutdown contract: rest and black out, so the head does not freeze
    mid-sky pointing at whoever it last tracked after the service stops."""

    def test_park_blacks_out_and_drops_the_target(self):
        t = Tracker(TrackerConfig())
        t.update([DriverThreat(rel_az_deg=40.0, size=0.9, id=1)], 1.0e9)
        parked = t.park()
        self.assertEqual(0, parked.dimmer)
        self.assertIsNone(parked.target_id)

    def test_park_is_idempotent(self):
        t = Tracker(TrackerConfig())
        t.update([DriverThreat(rel_az_deg=40.0, size=0.9, id=1)], 1.0e9)
        first = t.park()
        second = t.park()
        self.assertEqual(first.pan_deg, second.pan_deg)
        self.assertEqual(0, second.dimmer)

    def test_park_writes_a_blackout_on_the_dimmer_channel(self):
        cfg = TrackerConfig()
        t = Tracker(cfg)
        t.update([DriverThreat(rel_az_deg=40.0, size=0.9, id=1)], 1.0e9)
        self.assertEqual(0, t.park().channels[cfg.dimmer_channel])


class ReachableArcTest(unittest.TestCase):
    """The camera ring sees the whole circle; the moving head does not. It is
    mounted to light forward and both sides and cannot throw behind the vehicle
    (2026-08-07, from the real fixture). Aiming at something astern is worse
    than ignoring it — pan clamps at the end of travel, so the head parks
    against its limit pointing at nothing and stops following what it could
    actually light."""

    def t(self, az, size=0.5, collision=False, tid=0):
        return DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid)

    def test_forward_and_beam_contacts_are_reachable(self):
        for az in (0.0, 45.0, -45.0, 89.9, -89.9):
            self.assertEqual(1, len(reachable([self.t(az)])), f"{az} should be reachable")

    def test_contacts_astern_are_not(self):
        for az in (91.0, 150.0, 180.0, -150.0, -91.0):
            self.assertEqual([], reachable([self.t(az)]), f"{az} should be out of reach")

    def test_the_boundary_is_inclusive(self):
        self.assertEqual(1, len(reachable([self.t(90.0)])))
        self.assertEqual(1, len(reachable([self.t(-90.0)])))

    def test_bearings_are_wrapped_before_the_test(self):
        # The wire may report 350 for a contact 10 deg to port.
        self.assertEqual(1, len(reachable([self.t(350.0)])))
        self.assertEqual([], reachable([self.t(200.0)]))

    def test_the_arc_is_configurable(self):
        self.assertEqual([], reachable([self.t(80.0)], reach_half_deg=60.0))
        self.assertEqual(1, len(reachable([self.t(80.0)], reach_half_deg=100.0)))

    def test_a_rear_contact_is_never_selected(self):
        self.assertIsNone(select_best([self.t(170.0, size=0.9)]))

    def test_an_unreachable_collision_does_not_starve_a_reachable_contact(self):
        # The ordering that matters: filter first, THEN "collision wins".
        # Otherwise a collision astern takes the light and the head slews away
        # from the person actually in front of the vehicle.
        rear = self.t(170.0, size=0.9, collision=True, tid=1)
        front = self.t(10.0, size=0.3, tid=2)
        self.assertEqual(2, select_best([rear, front]).id)

    def test_a_reachable_collision_still_wins_over_a_nearer_bystander(self):
        coll = self.t(30.0, size=0.2, collision=True, tid=1)
        near = self.t(-20.0, size=0.95, tid=2)
        self.assertEqual(1, select_best([coll, near]).id)

    def test_an_all_rear_scene_reads_as_empty(self):
        crowd = [self.t(az, size=0.8, tid=i) for i, az in enumerate((120.0, 150.0, 180.0, -140.0), 1)]
        self.assertIsNone(select_best(crowd))


class ReachableArcTrackerTest(unittest.TestCase):
    """Same rule, but through the stateful tracker — where the latch lives."""

    def t(self, az, size=0.5, collision=False, tid=0):
        return DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid)

    def test_a_target_walking_behind_the_vehicle_releases_the_latch(self):
        # Otherwise the head stays latched to an id it can never illuminate and
        # ignores everyone in front for as long as that contact persists.
        trk = Tracker(TrackerConfig())
        trk.update([self.t(40.0, size=0.6, tid=7)], dt=0.1)
        self.assertEqual(7, trk.update([self.t(40.0, size=0.6, tid=7)], dt=0.1).target_id)
        gone_astern = trk.update([self.t(160.0, size=0.6, tid=7)], dt=0.1)
        self.assertIsNone(gone_astern.target_id)

    def test_the_head_holds_aim_rather_than_slewing_to_its_limit(self):
        # The failure this prevents: pan clamps at the pan_range end, so the
        # head swings away and parks. Holding aim keeps it where it last saw
        # something real.
        trk = Tracker(TrackerConfig())
        for _ in range(30):
            trk.update([self.t(45.0, size=0.6, tid=7)], dt=0.1)
        aimed = trk._pan_deg
        for _ in range(30):
            trk.update([self.t(175.0, size=0.9, collision=True, tid=8)], dt=0.1)
        self.assertAlmostEqual(aimed, trk._pan_deg, places=6)

    def test_the_head_goes_dark_when_everything_is_astern(self):
        trk = Tracker(TrackerConfig())
        frame = trk.update([self.t(175.0, size=0.9, collision=True, tid=8)], dt=0.1)
        self.assertEqual(TrackerConfig().dimmer_idle, frame.dimmer)

    def test_it_switches_to_a_reachable_contact_instead_of_holding_a_rear_one(self):
        trk = Tracker(TrackerConfig())
        trk.update([self.t(30.0, size=0.7, tid=7)], dt=0.1)
        frame = trk.update([self.t(160.0, size=0.9, tid=7), self.t(-25.0, size=0.4, tid=9)], dt=0.1)
        self.assertEqual(9, frame.target_id)


if __name__ == "__main__":
    unittest.main()
