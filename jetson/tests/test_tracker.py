import unittest

from zvision.threat import DriverThreat
from zvision.tracker import Tracker, TrackerConfig, deg_to_dmx16, select_best


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
        trk = Tracker(TrackerConfig())
        trk.update([DriverThreat(rel_az_deg=25.0, size=0.9, id=1)], dt=1.0e9)
        p = trk.park()
        self.assertEqual(128, p.channels[1])  # back to pan center
        self.assertEqual(0, p.channels[5])    # dimmer off
        self.assertIsNone(p.target_id)


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


if __name__ == "__main__":
    unittest.main()
