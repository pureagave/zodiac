#!/usr/bin/env python3
"""Generate ``protocol/version-protocol-golden.json`` — the shared truth for the
three hand-written ``$ZVER`` implementations (FLEET-1):

  emitter/parser  app/.../core/telemetry/FleetVersionProtocol.kt   (Kotlin, tablets)
  emitter/parser  jetson/zvision/version_protocol.py                (Python, edge box)
  emitter         :beacon Nmea.kt                                    (Kotlin, sensor hub)

All test suites read the JSON this writes. This generator is a **third,
independent** encoder: it computes the XOR checksum and joins the fields inline,
importing none of the implementations — so a vector only agrees with an impl if
that impl is correct, which is the whole point.

**Do not regenerate a red suite green.** A suite failing against the frozen
corpus means an implementation drifted from the wire; fix the impl. Regenerate
only when the format is *intended* to change, and then confirm all three suites
pass against the new corpus.
"""

from __future__ import annotations

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "version-protocol-golden.json"


def xor_hex(body: str) -> str:
    c = 0
    for ch in body:
        c ^= ord(ch)
    return "%02X" % c


def frame(node, name, base, sha, dirty, epoch) -> str:
    """The canonical sentence for already-clean fields — an independent encoder."""
    body = "ZVER,%s,%s,%s,%s,%s,%d" % (node, name, base, sha, "1" if dirty else "0", epoch)
    return "$%s*%s" % (body, xor_hex(body))


# Clean inputs (each already grammar-legal, so an impl's build() is identity on
# the fields) -> exact sentence. Tests field ORDER, framing and checksum.
FORMAT = [
    ("9C1977", "SM-X810", "0.1.0", "8f531e18a", False, 1_691_900_000),   # hero, clean, current
    ("A1B2C3", "KFTUWI", "0.1.0", "deadbeef1", True, 1_691_800_000),     # Fire, dirty tree
    ("BEEF00", "zvision", "0.1.0", "unknown", True, 0),                   # edge box, unidentified
    ("F00D12", "SM-G715U", "0.1.0", "0a1b2c3d4", False, 1_690_000_000),  # beacon, clean, older
    ("ABCDE", "zvision.dev-1", "1.2.3+rc~1", "a" * 40, False, 9_999_999_999),  # punctuation + maxima
    ("Z", "a", "0", "0000000", False, 1),                                # minima
]


def fields_of(node, name, base, sha, dirty, epoch) -> dict:
    return {"node": node, "name": name, "base": base, "sha": sha, "dirty": dirty, "epoch": epoch}


def main() -> int:
    format_vectors = []
    parse_vectors = []
    for row in FORMAT:
        line = frame(*row)
        format_vectors.append({"fields": fields_of(*row), "line": line})
        # Every clean sentence is also a valid parse vector.
        parse_vectors.append({"line": line, "valid": True, "fields": fields_of(*row)})

    # Positive control for the case-insensitive checksum: the SAME canonical
    # sentence with its checksum lowercased must still parse (the grammar allows
    # [0-9A-Fa-f]). Guards against an impl that only accepts uppercase.
    canon = FORMAT[0]
    canon_line = frame(*canon)
    parse_vectors.append(
        {"line": canon_line[:-2] + canon_line[-2:].lower(), "valid": True, "fields": fields_of(*canon)}
    )

    # Negative parse vectors. Each isolates ONE fault: where the fault is not the
    # checksum, the checksum is left VALID so parse rejects on the field itself
    # (pair every negative with a positive control). Only "bad_checksum" carries
    # a wrong CC.
    good = frame("9C1977", "SM-X810", "0.1.0", "8f531e18a", False, 1_691_900_000)

    def bad(body: str) -> str:
        """A sentence with a *correct* checksum for the given body, so any
        rejection is due to the body, not the checksum."""
        return "$%s*%s" % (body, xor_hex(body))

    negatives = [
        (good[:-2] + "00", "bad_checksum"),
        (bad("ZVER,9C1977,SM-X810"), "too_few_fields"),
        (bad("ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000,EXTRA"), "too_many_fields"),
        (bad("ZVER,ABCDEFGHI,SM-X810,0.1.0,8f531e18a,0,1691900000"), "node_too_long_9"),
        (bad("ZVER,9c1977,SM-X810,0.1.0,8f531e18a,0,1691900000"), "node_lowercase_illegal"),
        (bad("ZVER,9C1977,ABCDEFGHIJKLMNOPQ,0.1.0,8f531e18a,0,1691900000"), "name_too_long_17"),
        (bad("ZVER,9C1977,SM-X810,0.1.0,8F531E18A,0,1691900000"), "sha_uppercase_illegal"),
        (bad("ZVER,9C1977,SM-X810,0.1.0,8f531e,0,1691900000"), "sha_too_short_6"),
        (bad("ZVER,9C1977,SM-X810,0.1.0,8f531e18a,2,1691900000"), "dirty_not_0_or_1"),
        (bad("ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,12345678901"), "epoch_11_digits"),
        (bad("ZNAV,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000"), "wrong_header"),
        # A signed checksum must be rejected. This carries the CORRECT checksum
        # value with a leading '+': a naive int(cc,16)/toInt(16) would ACCEPT it
        # (both languages parse a leading +), so only the explicit [0-9A-Fa-f]
        # regex guard rejects it. The discriminating negative for the checksum.
        (signed_checksum(),  "signed_checksum_plus"),
        # Three-digit checksum — the {1,2} bound.
        ("$" + good_body() + "*" + xor_hex(good_body()) + "0", "checksum_too_long"),
        ("ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*7A", "no_dollar"),
        ("", "empty"),
    ]
    for line, why in negatives:
        parse_vectors.append({"line": line, "valid": False, "why": why})

    corpus = {
        "_comment": [
            "GOLDEN CORPUS for the ZVER wire format (FLEET-1) — the shared truth for",
            "three hand-written implementations in two languages:",
            "  Kotlin  app/.../core/telemetry/FleetVersionProtocol.kt   (tablets)",
            "  Python  jetson/zvision/version_protocol.py               (edge box)",
            "  Kotlin  :beacon Nmea.kt                                   (sensor hub, emit-only)",
            "All suites read THIS FILE. Generated by protocol/gen-version-golden.py,",
            "an independent encoder. Do NOT regenerate a red suite green — a mismatch",
            "means an implementation drifted from the wire; fix the impl.",
            "format_vectors: clean fields -> the exact sentence build() must produce",
            "  (compare after stripping the CRLF terminator each impl appends).",
            "parse_vectors: a line -> the FleetVersion parse() must return, or valid=false",
            "  for a line parse() must reject as None. Negatives isolate one fault and,",
            "  except bad_checksum, carry a valid checksum so the field is what is tested.",
        ],
        "header": "ZVER",
        "field_order": ["node", "name", "base", "sha", "dirty", "epoch"],
        "grammar": {
            "node": "[A-Z0-9]{1,8}",
            "name": "[A-Za-z0-9._-]{1,16}",
            "base": "[0-9A-Za-z.+~-]{1,16}",
            "sha": "[0-9a-f]{7,40}|unknown",
            "dirty": "[01]",
            "epoch": "[0-9]{1,10}",
            "checksum": "[0-9A-Fa-f]{1,2}, XOR over the body between $ and *",
        },
        "format_vectors": format_vectors,
        "parse_vectors": parse_vectors,
    }
    OUT.write_text(json.dumps(corpus, indent=2) + "\n")
    print("wrote %s: %d format, %d parse vectors"
          % (OUT.name, len(format_vectors), len(parse_vectors)))
    return 0


def good_body() -> str:
    return "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000"


def signed_checksum() -> str:
    """The canonical body with its CORRECT checksum prefixed by '+'. A guarded
    parser rejects it on the [0-9A-Fa-f] regex; a naive int(cc,16) accepts it."""
    body = good_body()
    return "$%s*+%s" % (body, xor_hex(body))


if __name__ == "__main__":
    import sys
    sys.exit(main())
