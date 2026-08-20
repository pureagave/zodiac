"""Wire format for the fleet build-version channel — ``ZVER`` — the Python
mirror of the tablet's Kotlin ``FleetVersionProtocol`` and the ``:beacon``
builder (FLEET-1). One of three hand-written implementations of the same wire,
so the grammar is the contract, not any one parser; they are pinned together by
the checked-in golden corpus ``protocol/version-protocol-golden.json`` that all
three test suites read.

    ``$ZVER,<node>,<name>,<base>,<sha>,<dirty>,<epoch>*CC``
    e.g. ``$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*7A``

Unlike ``ZTHREAT``/``ZCOVER`` (``;``-separated, no checksum) this is an
NMEA-style ``$…*CC`` sentence like ``$ZNAV``: US-ASCII, a two-hex-uppercase XOR
checksum over the body between ``$`` and ``*``. The wire is deliberately
cross-language-safe — only ASCII digits, lowercase hex for the sha (or the
literal ``unknown``), and ``0``/``1`` for dirty; no locale-formatted numbers, no
floats — so the three implementations cannot drift the way ``ZTHREAT`` once did.

:func:`parse` never raises: any malformation (bad checksum, wrong field count, a
field that violates its grammar) returns ``None``. Every field is pinned with an
explicit regex rather than trusting a host-language split, exactly as the Kotlin
side does.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

HEADER = "ZVER"
_FIELD_COUNT = 7  # header + 6 body fields
_UNKNOWN_SHA = "unknown"

# Largest value the epoch grammar ([0-9]{1,10}) can carry: ten nines. Past year
# 2286, so it only ever bounds pathological input (mirrors the Kotlin clamp).
_MAX_EPOCH = 9_999_999_999

_NODE_MAX_LEN = 8
_LABEL_MAX_LEN = 16

# Field grammars — the exact mirror of FleetVersionProtocol.kt. Explicit, not a
# trusting split: this is a cross-language contract.
_CHECKSUM_RE = re.compile(r"[0-9A-Fa-f]{1,2}")
_FIELD_RES = (
    re.compile(r"[A-Z0-9]{1,8}"),          # node
    re.compile(r"[A-Za-z0-9._-]{1,16}"),   # name
    re.compile(r"[0-9A-Za-z.+~-]{1,16}"),  # base
    re.compile(r"[0-9a-f]{7,40}|" + _UNKNOWN_SHA),  # sha
    re.compile(r"[01]"),                   # dirty
    re.compile(r"[0-9]{1,10}"),            # epoch
)


@dataclass(frozen=True)
class FleetVersion:
    """One node's build announcement: who it is and what it is running."""

    node: str
    name: str
    base: str
    sha: str
    dirty: bool
    epoch: int


def _xor(body: str) -> int:
    c = 0
    for ch in body:
        c ^= ord(ch)
    return c


def _sanitize_node(raw: str) -> str:
    cleaned = "".join(ch for ch in raw.upper() if ch.isascii() and (ch.isdigit() or "A" <= ch <= "Z"))
    return cleaned[-_NODE_MAX_LEN:] or "0"


def _sanitize_label(raw: str, extra: str, fallback: str) -> str:
    def ok(ch: str) -> bool:
        return ch.isascii() and (ch.isdigit() or "a" <= ch <= "z" or "A" <= ch <= "Z" or ch in extra)

    cleaned = "".join(ch for ch in raw if ok(ch))
    return cleaned[:_LABEL_MAX_LEN] or fallback


def build(version: FleetVersion) -> str:
    """``$ZVER,…*CC\\r\\n`` for *version*. Fields are sanitized so the output
    always parses back (mirrors ``FleetVersionProtocol.build``)."""
    node = _sanitize_node(version.node)
    name = _sanitize_label(version.name, "._-", "node")
    base = _sanitize_label(version.base, ".+~-", "0.0.0")
    dirty = "1" if version.dirty else "0"
    epoch = max(0, min(version.epoch, _MAX_EPOCH))
    body = f"{HEADER},{node},{name},{base},{version.sha},{dirty},{epoch}"
    return f"${body}*{_xor(body):02X}\r\n"


def parse(line: str) -> Optional[FleetVersion]:
    """A ``$ZVER`` sentence as a :class:`FleetVersion`, or ``None`` on any
    malformation. Never raises."""
    sentence = line.strip().rstrip("\r\n")
    star = sentence.find("*")
    if not sentence.startswith("$") or star < 0:
        return None
    body = sentence[1:star]
    cc = sentence[star + 1:]
    if not _CHECKSUM_RE.fullmatch(cc) or _xor(body) != int(cc, 16):
        return None
    fields = body.split(",")
    if len(fields) != _FIELD_COUNT or fields[0] != HEADER:
        return None
    values = fields[1:]
    if any(not regex.fullmatch(v) for regex, v in zip(_FIELD_RES, values)):
        return None
    return FleetVersion(
        node=values[0],
        name=values[1],
        base=values[2],
        sha=values[3],
        dirty=values[4] == "1",
        epoch=int(values[5]),
    )
