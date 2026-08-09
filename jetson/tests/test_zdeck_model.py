"""The pure key model. No hardware, no DMX transport — these run anywhere.

Two classes of assertion matter here beyond "the key does the thing": that the
panel can never *claim* a state the light was not sent, and that no key press can
ever reach a channel that hands the fixture to its own programs.
"""

import unittest

from zdeck.model import (
    AMBER_BANDS,
    BLACKOUT,
    COLOUR,
    COLOUR_AUTO_SPIN_FLOOR,
    DIM_DOWN,
    DIM_UP,
    GREEN,
    GREY,
    HOME,
    KEY_COUNT,
    LAMP,
    ON_PALETTE_COLOURS,
    PURPLE,
    RED,
    DeckConfig,
    DeckModel,
    apply,
)
from zvision.tracker import NINE_CHANNEL_OVERRIDES, TrackerConfig, config_for_channel_mode


class KeyBehaviourTest(unittest.TestCase):
    def test_lamp_toggles_between_dark_and_full(self):
        m = DeckModel()
        self.assertEqual(0, m.dim)
        m = m.press(LAMP)
        self.assertEqual(255, m.dim)
        self.assertEqual(0, m.press(LAMP).dim)

    def test_blackout_kills_from_any_level(self):
        for start in (1, 32, 128, 255):
            with self.subTest(start=start):
                m = DeckModel(dim=start).press(BLACKOUT)
                self.assertEqual(0, m.dim)

    def test_blackout_is_idempotent(self):
        # It is the panic key. Pressing it twice must not toggle the light back on.
        m = DeckModel(dim=255).press(BLACKOUT).press(BLACKOUT)
        self.assertEqual(0, m.dim)

    def test_dim_steps_and_clamps_at_both_ends(self):
        cfg = DeckConfig(dim_step=32)
        m = DeckModel(cfg=cfg, dim=0)
        for _ in range(20):
            m = m.press(DIM_UP)
        self.assertEqual(255, m.dim)  # never past full
        for _ in range(20):
            m = m.press(DIM_DOWN)
        self.assertEqual(0, m.dim)  # never below dark

    def test_dim_up_from_dark_lights_the_head(self):
        # Otherwise DIM+ looks broken until LAMP is pressed first.
        self.assertTrue(DeckModel(dim=0).press(DIM_UP).lit)

    def test_colour_cycles_and_wraps(self):
        m = DeckModel()
        names = []
        for _ in range(len(ON_PALETTE_COLOURS) + 1):
            names.append(m.colour_name)
            m = m.press(COLOUR)
        self.assertEqual(names[0], names[-1])  # wrapped
        self.assertEqual(len(set(names[:-1])), len(ON_PALETTE_COLOURS))

    def test_home_returns_to_mid_travel_on_both_axes(self):
        m = DeckModel(pan=10, tilt=250).press(HOME)
        self.assertEqual(128, m.pan)
        self.assertEqual(128, m.tilt)

    def test_an_unknown_key_is_ignored_not_raised(self):
        # This arrives from a USB callback on a vehicle. A spurious index must
        # not take down the operator's only physical control.
        m = DeckModel(dim=128)
        for bad in (-1, KEY_COUNT, 99):
            with self.subTest(bad=bad):
                self.assertEqual(m, m.press(bad))

    def test_the_model_is_immutable(self):
        m = DeckModel()
        m.press(LAMP)
        self.assertEqual(0, m.dim, "press mutated the original model")


class ChannelSafetyTest(unittest.TestCase):
    """What a press is allowed to put on the wire."""

    def test_the_auto_program_and_reset_channels_are_never_written(self):
        # ch10 above 59 hands the head to its internal programs; ch11 held at
        # 250-255 for 5 s is a MOTOR RESET. Neither may ever appear, in any
        # state reachable by pressing keys.
        m = DeckModel()
        for key in range(KEY_COUNT):
            for _ in range(3):
                m = m.press(key)
                for forbidden in TrackerConfig().forbidden_channels:
                    self.assertNotIn(forbidden, m.channels(),
                                     f"ch{forbidden} written after key {key}")

    def test_no_state_can_emit_an_auto_spin_colour(self):
        # ch5 >= 140 spins the colour wheel continuously. Every configured slot
        # must sit below that, or a press starts a disco.
        for name, value in ON_PALETTE_COLOURS:
            with self.subTest(colour=name):
                self.assertLess(value, COLOUR_AUTO_SPIN_FLOOR)

    def test_an_amber_slot_is_rejected_at_construction(self):
        # Mutation testing caught this: the "no amber" render test passed even
        # with an amber slot added, because the LABEL colour never changes. The
        # rule has to bite on the wheel VALUE, so it is enforced in the config.
        for value in (54, 100, 44):
            with self.subTest(value=value), self.assertRaises(ValueError):
                DeckConfig(colours=(("WHITE", 0), ("AMBER", value)))

    def test_an_auto_spin_colour_is_rejected_at_construction(self):
        with self.assertRaises(ValueError):
            DeckConfig(colours=(("SPIN", COLOUR_AUTO_SPIN_FLOOR),))

    def test_the_shipped_slots_pass_their_own_rules(self):
        DeckConfig(colours=ON_PALETTE_COLOURS)  # must not raise
        for name, value in ON_PALETTE_COLOURS:
            for lo, hi in AMBER_BANDS:
                self.assertFalse(lo <= value <= hi, f"{name} at ch5={value} is amber")

    def test_a_nonsense_dim_step_is_rejected(self):
        for bad in (0, -8):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                DeckConfig(dim_step=bad)

    def test_a_wiring_clash_with_a_forbidden_channel_raises(self):
        # The guard only earns its keep if something can trip it. A fixture map
        # that puts the dimmer on ch10 -- the auto-program channel -- must fail
        # loudly, not quietly hand the head to its internal show at full
        # brightness. Mutation testing showed the guard was unreachable without
        # this case, i.e. it could have been deleted with no test noticing.
        cfg = DeckConfig(fixture=TrackerConfig(dimmer_channel=10))
        with self.assertRaises(ValueError):
            DeckModel(cfg=cfg, dim=255).channels()

    def test_the_guard_names_the_offending_channel(self):
        cfg = DeckConfig(fixture=TrackerConfig(dimmer_channel=11))
        with self.assertRaises(ValueError) as ctx:
            DeckModel(cfg=cfg).channels()
        self.assertIn("11", str(ctx.exception))

    def test_every_emitted_value_is_a_legal_dmx_byte(self):
        m = DeckModel()
        for key in (DIM_UP,) * 12 + (COLOUR, HOME, LAMP, DIM_DOWN):
            m = m.press(key)
            for ch, val in m.channels().items():
                self.assertTrue(1 <= ch <= 512, f"channel {ch} out of universe")
                self.assertTrue(0 <= val <= 255, f"ch{ch}={val} not a byte")

    def test_channel_numbers_come_from_the_fixture_config(self):
        # Guards the same mistake that put the dimmer on the colour wheel once:
        # a literal channel number here would silently diverge from the tracker.
        cfg = DeckConfig(fixture=TrackerConfig(
            pan_channel=21, pan_fine_channel=22, tilt_channel=23,
            tilt_fine_channel=24, dimmer_channel=28))
        ch = DeckModel(cfg=cfg, dim=200).channels()
        self.assertEqual(200, ch[28])
        self.assertIn(21, ch)
        self.assertIn(23, ch)
        self.assertNotIn(8, ch, "wrote the default dimmer channel, not the configured one")

    def test_a_nine_channel_fixture_moves_the_dimmer_and_the_colour_wheel(self):
        # 9-channel shifts everything above pan down by two: dimmer 8 -> 6 and
        # colour 5 -> 3. Writing brightness to a stale channel number is how the
        # head ends up looking dead while the colour wheel spins.
        cfg = DeckConfig(fixture=config_for_channel_mode(9))
        ch = DeckModel(cfg=cfg, dim=200).channels()
        self.assertEqual(200, ch[NINE_CHANNEL_OVERRIDES["dimmer_channel"]])
        self.assertEqual(200, ch[6])
        self.assertIn(3, ch)  # colour, derived from the dimmer
        self.assertNotIn(8, ch)

    def test_nine_channel_writes_stay_inside_the_nine_channel_footprint(self):
        cfg = DeckConfig(fixture=config_for_channel_mode(9))
        m = DeckModel(cfg=cfg)
        for key in range(KEY_COUNT):
            m = m.press(key)
            for ch in m.channels():
                self.assertLessEqual(ch, 9, f"ch{ch} is outside 9-channel mode")

    def test_a_fixture_with_no_dimmer_channel_emits_none(self):
        cfg = DeckConfig(fixture=TrackerConfig(dimmer_channel=None))
        ch = DeckModel(cfg=cfg, dim=255).channels()
        self.assertNotIn(8, ch)


class RenderTest(unittest.TestCase):
    """The panel must not lie about the light."""

    def test_the_frame_renders_the_same_state_it_sends(self):
        # channels() and render() drifting apart is its own class of fault: a
        # panel showing OFF over a live head.
        m = DeckModel().press(LAMP)
        frame = m.frame()
        dimmer_ch = m.cfg.fixture.dimmer_channel
        shown_on = frame.keys[LAMP].value == "ON"
        self.assertEqual(shown_on, frame.channels[dimmer_ch] > 0)

    def test_blackout_greys_out_when_there_is_nothing_to_kill(self):
        # A kill control that looks armed over a dark head is noise.
        self.assertEqual(GREY, DeckModel(dim=0).render()[BLACKOUT].colour)
        self.assertEqual(RED, DeckModel(dim=200).render()[BLACKOUT].colour)

    def test_the_dim_keys_show_the_live_level(self):
        m = DeckModel(dim=64)
        self.assertEqual("64", m.render()[DIM_DOWN].value)
        self.assertEqual("64", m.render()[DIM_UP].value)

    def test_controls_are_green_and_only_faults_are_red(self):
        # Palette rule from ConceptTheme; red is reserved for faults/kill.
        for dim in (0, 255):
            with self.subTest(dim=dim):
                for i, k in enumerate(DeckModel(dim=dim).render()):
                    if i == BLACKOUT:
                        continue
                    self.assertEqual(GREEN, k.colour, f"key {i} is not a control colour")

    def test_no_key_ever_renders_amber(self):
        # Amber is banned project-wide, and a third of this fixture's colour
        # wheel is amber -- so it must not reach the panel either.
        m = DeckModel()
        for _ in range(len(ON_PALETTE_COLOURS) * 2):
            m = m.press(COLOUR)
            for k in m.render():
                self.assertIn(k.colour, {GREEN, PURPLE, RED, GREY})

    def test_render_covers_every_key(self):
        self.assertEqual(KEY_COUNT, len(DeckModel().render()))

    def test_apply_returns_the_frame_for_the_new_state_not_the_old(self):
        m = DeckModel()
        nxt, frame = apply(m, LAMP)
        self.assertEqual(255, nxt.dim)
        self.assertEqual("ON", frame.keys[LAMP].value)


if __name__ == "__main__":
    unittest.main()
