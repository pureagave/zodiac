"""The edge box's half of the cross-language ZVER contract (FLEET-1).

Reads ``protocol/version-protocol-golden.json`` — the same file the tablet's
``FleetVersionProtocolGoldenTest.kt`` reads — and checks this implementation
against it. Neither the Python nor the Kotlin (nor the beacon's) implementation
is authoritative; the corpus is. If you change the ZVER format, this suite fails
until the corpus is regenerated (``protocol/gen-version-golden.py``) and every
side agrees. That is the feature.

Like the ZTHREAT/ZCOVER golden suites, this fails LOUDLY if the corpus is missing
or truncated rather than passing vacuously — the failure mode this project has
been bitten by repeatedly.
"""

import json
import unittest
from pathlib import Path

from zvision.version_protocol import HEADER, FleetVersion, build, parse

# jetson/tests/this_file.py -> jetson/tests -> jetson -> repo root.
_CORPUS = Path(__file__).resolve().parents[2] / "protocol" / "version-protocol-golden.json"

_MIN_PARSE_VECTORS = 20
_MIN_FORMAT_VECTORS = 5


def _load():
    if not _CORPUS.is_file():
        raise AssertionError(
            f"Version golden corpus not found at {_CORPUS} — this suite cannot "
            "silently pass without it. The ZVER wire format is a contract with the "
            "tablet's core/telemetry/FleetVersionProtocol.kt and the :beacon builder, "
            "and the corpus is the only thing enforcing it. Regenerate with "
            "protocol/gen-version-golden.py."
        )
    return json.loads(_CORPUS.read_text())


def _version_of(f: dict) -> FleetVersion:
    return FleetVersion(f["node"], f["name"], f["base"], f["sha"], f["dirty"], f["epoch"])


class VersionGoldenCorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.corpus = _load()

    def test_corpus_is_present_and_substantial(self):
        self.assertGreaterEqual(len(self.corpus["parse_vectors"]), _MIN_PARSE_VECTORS)
        self.assertGreaterEqual(len(self.corpus["format_vectors"]), _MIN_FORMAT_VECTORS)

    def test_shared_header_matches_this_implementation(self):
        self.assertEqual(self.corpus["header"], HEADER)

    def test_every_format_vector_matches_byte_for_byte(self):
        for v in self.corpus["format_vectors"]:
            with self.subTest(v["line"]):
                # The corpus stores the sentence up to the checksum; build()
                # appends the CRLF terminator (framing, not payload) — strip it.
                self.assertEqual(v["line"], build(_version_of(v["fields"])).rstrip("\r\n"))

    def test_every_parse_vector_matches(self):
        for v in self.corpus["parse_vectors"]:
            with self.subTest(v.get("why", v["line"])):
                actual = parse(v["line"])
                if not v["valid"]:
                    self.assertIsNone(actual, "expected this line to be rejected")
                    continue
                self.assertEqual(_version_of(v["fields"]), actual)


if __name__ == "__main__":
    unittest.main()
