import unittest

from zvision.threat import DriverThreat
from zvision.tracker import (
    nearest_equivalent_pan,
    reachable,
    Tracker,
    TrackerConfig,
    deg_to_dmx16,
    select_best,
)


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

    def test_the_head_does_not_sweep_the_vehicle_when_a_contact_crosses_astern(self):
        # The behaviour that matters is *cumulative travel*, not step size: the
        # slew limiter caps every individual step, so a single-step assertion
        # passes even while the head is three seconds into sweeping the wrong
        # way round the vehicle. Measure the whole journey.
        cfg = TrackerConfig(pan_center_deg=270.0, pan_slew_dps=120.0)
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


if __name__ == "__main__":
    unittest.main()


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
