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
