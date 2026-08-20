"""Focused unit tests for the Python $ZVER codec's own logic — the sanitisation
and clamping branches the golden corpus (clean inputs only) does not exercise.
The cross-language byte-for-byte agreement lives in test_version_protocol_golden.
"""

import unittest

from zvision.version_protocol import FleetVersion, build, parse


def _v(node="9C1977", name="SM-X810", base="0.1.0", sha="8f531e18a", dirty=False, epoch=1_691_900_000):
    return FleetVersion(node, name, base, sha, dirty, epoch)


class VersionProtocolTest(unittest.TestCase):
    def test_build_output_always_parses_back(self):
        # Every sanitised build must round-trip, even from dirty input.
        for v in (
            _v(),
            _v(dirty=True),
            _v(name="my host name!", base="v1.0 (rc)"),
            _v(node="lower_case-9c1977ff00"),
            _v(epoch=-5),
            _v(epoch=10 ** 12),
            _v(sha="unknown", dirty=True, epoch=0),
        ):
            got = parse(build(v))
            self.assertIsNotNone(got, f"build({v}) did not parse back")

    def test_node_is_uppercased_filtered_and_last_8(self):
        got = parse(build(_v(node="ab-cd-ef-12-34-56-78-90")))
        # Non-[A-Z0-9] stripped, uppercased, last 8 kept.
        self.assertEqual("34567890", got.node)

    def test_illegal_characters_are_stripped_from_name_and_base(self):
        got = parse(build(_v(name="host name!*,", base="1.0 beta,;")))
        self.assertEqual("hostname", got.name)   # space, !, *, comma dropped
        self.assertEqual("1.0beta", got.base)    # space, comma, ; dropped

    def test_empty_after_sanitising_falls_back(self):
        got = parse(build(_v(node="!!!", name=",,,", base=" ")))
        self.assertEqual("0", got.node)
        self.assertEqual("node", got.name)
        self.assertEqual("0.0.0", got.base)

    def test_epoch_is_clamped_into_the_grammar(self):
        self.assertEqual(0, parse(build(_v(epoch=-1))).epoch)
        self.assertEqual(9_999_999_999, parse(build(_v(epoch=10 ** 15))).epoch)

    def test_dirty_flag_round_trips(self):
        self.assertTrue(parse(build(_v(dirty=True))).dirty)
        self.assertFalse(parse(build(_v(dirty=False))).dirty)

    def test_parse_rejects_a_corrupted_checksum(self):
        good = build(_v()).rstrip("\r\n")
        self.assertIsNone(parse(good[:-2] + "00"))
        self.assertIsNotNone(parse(good), "positive control: the intact sentence parses")


if __name__ == "__main__":
    unittest.main()
