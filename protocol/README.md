# `protocol/` — cross-language wire contracts

Artifacts shared by codebases that must agree byte-for-byte but cannot import
each other. Owned by neither side.

## `threat-protocol-golden.json` — the ZTHREAT frame

The thermal-threat channel the Jetson broadcasts and the tablets render on the
DRIVER night HUD. Two hand-written implementations:

| | file | role |
|---|---|---|
| producer | `jetson/zvision/threat_protocol.py` | `format_frame` runs on the vehicle |
| consumer | `app/.../core/vision/ThreatProtocol.kt` | `parse` runs on the vehicle |

Each also carries the *other* direction (`parse_frame` / `format`) purely as a
mirror. **Nothing on the vehicle exercises those halves** — which is exactly why
they drifted for months without anyone noticing.

Both test suites read this file:

- `jetson/tests/test_threat_protocol_golden.py`
- `app/src/test/java/.../core/vision/ThreatProtocolGoldenTest.kt`

Both fail loudly if it is missing or truncated, rather than skipping. Both CI
workflows are triggered by changes under `protocol/**`.

### The grammar is pinned, deliberately

A wire format must not inherit its host language's numeric parser. Measured
divergences that existed before this corpus (10 of 90 fuzz cases):

- Kotlin's float parser accepts Java *source* syntax — `5.0f`, `5.0d`, and hex
  floats. **`0x1p3` in an azimuth field became a live contact bearing 8°** on the
  driver's HUD, while the Python side rejected the same frame.
- Python's `int()` accepts underscores, surrounding whitespace and unbounded
  magnitudes — a 4000-digit track id parsed fine there, dropped on the tablet.
- A `size` of `3.5e38` is finite in 64-bit and infinite in 32-bit, so one side
  kept the contact and the other discarded it.

So both sides now check explicit patterns — `-?[0-9]{1,9}` for ids,
`-?[0-9]{1,9}(\.[0-9]{1,6})?` for numbers (`[0-9]`, not `\d`, because Python's
`\d` also matches Unicode digits and Java's does not) — and the Python side
rounds parsed values through 32-bit float so both agree on the range and clamp
boundaries.

### `known_precision_limits` is not a to-do list

The producer holds 64-bit floats, the tablet 32-bit. Where the two roundings
straddle a decimal boundary the sides spell the value differently (`1.45` →
`1.4` vs `1.5`). Both parsers accept either spelling and the values differ by
under one wire digit — far below anything the HUD can draw. These are recorded
so nobody "fixes" them, and kept out of `format_vectors`.

### Regenerating

The corpus was **measured, not authored**: identical inputs were run through both
implementations and only agreed-upon results were written. If you change the
format, re-run that differential comparison rather than hand-editing the JSON —
hand-editing lets you write down what you believe instead of what the code does,
which is the failure this file exists to prevent.
