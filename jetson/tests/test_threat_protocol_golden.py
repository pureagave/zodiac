"""The edge box's half of the cross-language ZTHREAT contract.

This suite asserts nothing of its own invention: it reads
``protocol/threat-protocol-golden.json``, the same file the tablet's
``ThreatProtocolGoldenTest.kt`` reads, and checks this implementation against
it. That is the whole point — the Python producer and the Kotlin consumer are
written by hand in different languages, and until this corpus existed the only
thing keeping them in step was that the same person had recently read both.
They had silently drifted in ten measured ways (SYNC.md 2026-08-10).

If you change the wire format, this suite fails until the corpus is regenerated
*and* the Kotlin side agrees with it. That is the feature.
"""

import json
import unittest
from pathlib import Path

from zvision.threat import DriverThreat
from zvision.threat_protocol import HEADER, MAX_ABS_AZ_DEG, MAX_CONTACTS, format_frame, parse_frame

# jetson/tests/this_file.py -> jetson/tests -> jetson -> repo root. Anchored to
# __file__ rather than the working directory so it resolves the same whether the
# suite is run from jetson/ (as CI does), from the repo root, or from an IDE.
_CORPUS = Path(__file__).resolve().parents[2] / "protocol" / "threat-protocol-golden.json"

# A corpus that failed to load, or got truncated to nothing, would make every
# assertion below vacuously true — the exact failure mode this project has been
# bitten by five times. Guard the floor.
_MIN_PARSE_VECTORS = 100
_MIN_FORMAT_VECTORS = 20


def _load():
    if not _CORPUS.is_file():
        raise AssertionError(
            f"Golden corpus not found at {_CORPUS} — this suite cannot silently pass "
            "without it. The ZTHREAT wire format is a contract with the tablet's "
            "core/vision/ThreatProtocol.kt and the corpus is the only thing enforcing it."
        )
    return json.loads(_CORPUS.read_text())


def _number(v):
    """JSON cannot carry NaN/Infinity as numbers, so the corpus spells them."""
    return float(v) if isinstance(v, str) else v


class GoldenCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.corpus = _load()

    def test_corpus_is_present_and_substantial(self):
        self.assertGreaterEqual(len(self.corpus["parse_vectors"]), _MIN_PARSE_VECTORS)
        self.assertGreaterEqual(len(self.corpus["format_vectors"]), _MIN_FORMAT_VECTORS)

    def test_shared_constants_match_this_implementation(self):
        self.assertEqual(self.corpus["header"], HEADER)
        self.assertEqual(self.corpus["max_contacts"], MAX_CONTACTS)
        self.assertEqual(self.corpus["max_abs_az_deg"], MAX_ABS_AZ_DEG)

    def test_every_parse_vector_matches(self):
        for v in self.corpus["parse_vectors"]:
            with self.subTest(v["name"]):
                actual = parse_frame(v["frame"])
                if v["expect"] is None:
                    self.assertIsNone(actual, "expected this line to be rejected")
                    continue
                self.assertIsNotNone(actual, "expected a parsed frame, got None")
                self.assertEqual(len(v["expect"]), len(actual), "contact count")
                for i, (e, a) in enumerate(zip(v["expect"], actual)):
                    self.assertEqual(e["id"], a.id, f"contact {i} id")
                    # Exact, not approximate: the corpus records the value a
                    # 32-bit float actually holds and this side mirrors that width.
                    self.assertEqual(e["az"], a.rel_az_deg, f"contact {i} az")
                    self.assertEqual(e["size"], a.size, f"contact {i} size")
                    self.assertEqual(e["collision"], a.collision, f"contact {i} collision")

    def test_every_format_vector_matches_byte_for_byte(self):
        for v in self.corpus["format_vectors"]:
            with self.subTest(v["name"]):
                threats = [
                    DriverThreat(
                        rel_az_deg=_number(c["az"]),
                        size=_number(c["size"]),
                        collision=c["col"],
                        id=c["id"],
                    )
                    for c in v["contacts"]
                ]
                self.assertEqual(v["frame"], format_frame(threats))

    def test_documented_precision_limits_still_describe_reality(self):
        """The corpus records where a 64-bit producer and a 32-bit consumer spell
        a value differently. These are NOT asserted as agreement — but they are
        asserted to still be true of this side, so the note cannot quietly rot
        into a lie about behaviour that has since changed."""
        limits = self.corpus["known_precision_limits"]
        self.assertTrue(limits, "expected the precision limits to be documented")
        for lim in limits:
            with self.subTest(lim["name"]):
                threats = [
                    DriverThreat(
                        rel_az_deg=_number(c["az"]),
                        size=_number(c["size"]),
                        collision=c["col"],
                        id=c["id"],
                    )
                    for c in lim["contacts"]
                ]
                self.assertEqual(lim["jetson_float64"], format_frame(threats))
                self.assertNotEqual(
                    lim["tablet_float32"],
                    lim["jetson_float64"],
                    "a limit that no longer diverges belongs in format_vectors",
                )

    def test_both_spellings_of_a_precision_limit_parse_to_the_same_contact(self):
        """The reason the limits above are tolerable: whichever spelling goes on
        the wire, this parser recovers the same contact to within one wire digit.
        If that ever stopped holding, the limits would be a real defect."""
        for lim in self.corpus["known_precision_limits"]:
            with self.subTest(lim["name"]):
                a = parse_frame(lim["jetson_float64"])
                b = parse_frame(lim["tablet_float32"])
                self.assertEqual(len(a), len(b))
                for x, y in zip(a, b):
                    self.assertEqual(x.id, y.id)
                    self.assertEqual(x.collision, y.collision)
                    # One unit in the last wire digit, plus a hair of slack for
                    # 32-bit representation error: 1.4 and 1.5 are one digit
                    # apart on paper but 0.10000002 apart once the tablet's float
                    # width is applied. 0.1 deg of bearing and 0.001 of size are
                    # both far below anything the HUD can draw.
                    self.assertLessEqual(abs(x.rel_az_deg - y.rel_az_deg), 0.1 + 1e-5)
                    self.assertLessEqual(abs(x.size - y.size), 0.001 + 1e-7)


if __name__ == "__main__":
    unittest.main()
