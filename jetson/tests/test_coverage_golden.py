"""The edge box's half of the cross-language ZCOVER contract (RES-P2-1).

Reads ``protocol/coverage-protocol-golden.json`` — the same file the tablet's
``CoverageProtocolGoldenTest.kt`` reads — and checks this implementation against
it. Neither the Python producer nor the Kotlin consumer is authoritative; the
corpus is. If you change the ZCOVER format, this suite fails until the corpus is
regenerated (``jetson/tools/gen_coverage_golden.py``) and the Kotlin side
agrees. That is the feature.

Like the ZTHREAT golden suite, this one fails LOUDLY if the corpus is missing or
truncated rather than passing vacuously — the failure mode this project has been
bitten by repeatedly."""

import json
import unittest
from pathlib import Path

from zvision.coverage_protocol import HEADER, MAX_ABS_START_DEG, MAX_ARCS, format_coverage, parse_coverage

# jetson/tests/this_file.py -> jetson/tests -> jetson -> repo root.
_CORPUS = Path(__file__).resolve().parents[2] / "protocol" / "coverage-protocol-golden.json"

# A corpus truncated to nothing would make every assertion vacuously true.
_MIN_PARSE_VECTORS = 12
_MIN_FORMAT_VECTORS = 5


def _load():
    if not _CORPUS.is_file():
        raise AssertionError(
            f"Coverage golden corpus not found at {_CORPUS} — this suite cannot "
            "silently pass without it. The ZCOVER wire format is a contract with "
            "the tablet's core/vision/CoverageProtocol.kt and the corpus is the "
            "only thing enforcing it. Regenerate with jetson/tools/gen_coverage_golden.py."
        )
    return json.loads(_CORPUS.read_text())


class CoverageGoldenCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.corpus = _load()

    def test_corpus_is_present_and_substantial(self):
        self.assertGreaterEqual(len(self.corpus["parse_vectors"]), _MIN_PARSE_VECTORS)
        self.assertGreaterEqual(len(self.corpus["format_vectors"]), _MIN_FORMAT_VECTORS)

    def test_shared_constants_match_this_implementation(self):
        self.assertEqual(self.corpus["header"], HEADER)
        self.assertEqual(self.corpus["max_arcs"], MAX_ARCS)
        self.assertEqual(self.corpus["max_abs_start_deg"], MAX_ABS_START_DEG)

    def test_every_parse_vector_matches(self):
        for v in self.corpus["parse_vectors"]:
            with self.subTest(v["name"]):
                actual = parse_coverage(v["frame"])
                if v["expect"] is None:
                    self.assertIsNone(actual, "expected this line to be rejected")
                    continue
                self.assertIsNotNone(actual, "expected a parsed coverage frame, got None")
                expected = [(a[0], a[1]) for a in v["expect"]]
                # Exact, not approximate: the corpus records the value a 32-bit
                # float actually holds and this side mirrors that width.
                self.assertEqual(expected, actual)

    def test_every_format_vector_matches_byte_for_byte(self):
        for v in self.corpus["format_vectors"]:
            with self.subTest(v["name"]):
                arcs = [(a[0], a[1]) for a in v["arcs"]]
                self.assertEqual(v["frame"], format_coverage(arcs))


if __name__ == "__main__":
    unittest.main()
