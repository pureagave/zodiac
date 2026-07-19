"""The wire format is a cross-language contract with the tablet's Kotlin
``ThreatProtocol``. These tests pin the exact bytes and the parse behaviour so a
change here can't silently break the HUD on the other side."""

import unittest

from zvision.threat import DriverThreat
from zvision.threat_protocol import HEADER, format_frame, parse_frame


class FormatTest(unittest.TestCase):
    def test_empty_list_is_bare_header_all_clear(self):
        self.assertEqual(HEADER, format_frame([]))

    def test_single_contact_wire_shape(self):
        frame = format_frame([DriverThreat(rel_az_deg=-12.0, size=0.30, collision=False, id=1)])
        self.assertEqual("ZTHREAT;1:-12.0:0.300:0", frame)

    def test_collision_flag_is_one(self):
        frame = format_frame([DriverThreat(rel_az_deg=4.5, size=0.90, collision=True, id=2)])
        self.assertEqual("ZTHREAT;2:4.5:0.900:1", frame)

    def test_multiple_contacts_are_semicolon_separated(self):
        frame = format_frame(
            [
                DriverThreat(rel_az_deg=-12.0, size=0.30, collision=False, id=1),
                DriverThreat(rel_az_deg=4.5, size=0.90, collision=True, id=2),
            ]
        )
        self.assertEqual("ZTHREAT;1:-12.0:0.300:0;2:4.5:0.900:1", frame)


class ParseTest(unittest.TestCase):
    def test_parses_a_tablet_shaped_frame(self):
        # The exact example from the Kotlin ThreatProtocol docstring.
        contacts = parse_frame("ZTHREAT;1:-12.0:0.30:0;2:4.5:0.90:1")
        self.assertEqual(2, len(contacts))
        self.assertEqual(1, contacts[0].id)
        self.assertAlmostEqual(-12.0, contacts[0].rel_az_deg, places=3)
        self.assertFalse(contacts[0].collision)
        self.assertTrue(contacts[1].collision)

    def test_bare_header_is_empty_list_not_none(self):
        self.assertEqual([], parse_frame("ZTHREAT"))

    def test_non_frame_is_none(self):
        self.assertIsNone(parse_frame("$GPGGA,123"))
        self.assertIsNone(parse_frame(""))

    def test_malformed_contact_is_skipped_not_fatal(self):
        contacts = parse_frame("ZTHREAT;garbage;2:4.5:0.90:1")
        self.assertEqual(1, len(contacts))
        self.assertEqual(2, contacts[0].id)


class ValidationTest(unittest.TestCase):
    def test_rejects_non_finite_az_and_size(self):
        self.assertEqual([], parse_frame("ZTHREAT;1:NaN:0.5:0"))
        self.assertEqual([], parse_frame("ZTHREAT;2:5.0:Infinity:1"))
        self.assertEqual([], parse_frame("ZTHREAT;3:-Infinity:0.5:0"))

    def test_clamps_size_to_unit_range(self):
        self.assertEqual(1.0, parse_frame("ZTHREAT;1:0.0:9.0:0")[0].size)
        self.assertEqual(0.0, parse_frame("ZTHREAT;1:0.0:-4.0:0")[0].size)

    def test_drops_contacts_outside_the_forward_arc(self):
        # az beyond ±90 isn't in front of the vehicle.
        self.assertEqual([], parse_frame("ZTHREAT;1:120.0:0.5:0"))
        self.assertEqual(1, len(parse_frame("ZTHREAT;1:89.0:0.5:0")))

    def test_caps_contact_count(self):
        frame = "ZTHREAT" + "".join(f";{i}:0.0:0.5:0" for i in range(100))
        from zvision.threat_protocol import MAX_CONTACTS

        self.assertEqual(MAX_CONTACTS, len(parse_frame(frame)))

    def test_format_caps_and_keeps_collisions(self):
        from zvision.threat_protocol import MAX_CONTACTS

        many = [DriverThreat(rel_az_deg=0.0, size=0.1, collision=False, id=i) for i in range(100)]
        many.append(DriverThreat(rel_az_deg=1.0, size=0.9, collision=True, id=999))
        parsed = parse_frame(format_frame(many))
        self.assertLessEqual(len(parsed), MAX_CONTACTS)
        self.assertTrue(any(c.collision for c in parsed))  # the collision survived the cap
        self.assertLess(len(format_frame(many).encode()), 1200)  # stays under one MTU


class RoundTripTest(unittest.TestCase):
    def test_format_then_parse_recovers_values(self):
        original = [
            DriverThreat(rel_az_deg=-12.3, size=0.256, collision=False, id=7),
            DriverThreat(rel_az_deg=33.1, size=0.812, collision=True, id=8),
        ]
        recovered = parse_frame(format_frame(original))
        self.assertEqual(len(original), len(recovered))
        for a, b in zip(original, recovered):
            self.assertEqual(a.id, b.id)
            self.assertAlmostEqual(a.rel_az_deg, b.rel_az_deg, places=1)
            self.assertAlmostEqual(a.size, b.size, places=3)
            self.assertEqual(a.collision, b.collision)


if __name__ == "__main__":
    unittest.main()
