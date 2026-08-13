"""Zero the DMX universe — the fail-safe a crash cannot skip, and the operator kill.

``app.py`` parks the head in a ``finally``, which covers a clean exit and a
signal but **not** SIGKILL, a segfault or an OOM-kill. That gap is dangerous
rather than untidy. ``olad`` owns the frame timing (see ``dmx.py``) and keeps
streaming the last universe at 30 Hz indefinitely, so if zvision dies while olad
lives the fixture goes on seeing *perfectly valid* DMX and holds its last
command: a full-brightness beam frozen in the **fixture's** frame while the
vehicle turns, raking across whoever happens to be there. The head's own
``BLnd=blac`` blackout cannot save us — from its point of view the signal never
stopped. Measured on the bench 2026-08-08: a universe set by hand stayed lit for
many minutes with no client connected.

Two jobs, one module:

* ``ExecStopPost=`` on the zvision unit — systemd runs it on crash and kill, not
  just on a clean stop.
* **Operator kill.** ``python3 -m zvision.dmxpark`` douses the head by hand.
  It cannot help when the *Jetson itself* dies; that case is the fixture's
  ``BLnd=blac``, because then DMX genuinely does stop. A software kill and the
  hardware switch cover different failures and neither replaces the other.

Zeroes **all 512 slots**, not just the dimmer: correct whatever channel
personality the fixture is in (the dimmer is ch8 in 11-channel mode and ch6 in
9-channel), and it also clears the auto-program channels that a dimmer-only
blackout would leave set. The head goes dark and drifts to its home position.

Imports nothing else from zvision — no cv2, no camera — so it still runs when
the thing that killed zvision was an import or a driver.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import os
import shlex
import sys
import time
import urllib.parse
import urllib.request
from typing import Optional, Tuple

DMX_UNIVERSE_SIZE = 512
DEFAULT_URL = "http://127.0.0.1:9090"
#: The DMX kinds a service's own --dmx flag can take (mirrors zvision/zdeck).
_DMX_KINDS = ("none", "fake", "ola")


def park(
    universe: int = 0,
    base_url: str = DEFAULT_URL,
    timeout: float = 1.0,
    retries: int = 3,
    pause: float = 0.3,
) -> bool:
    """POST an all-zero universe to ``olad``. True if one attempt succeeded.

    Retries because this runs while the machine is busy tearing a crashed
    process down, and a single refused connection is not evidence that olad is
    gone. Never raises: a fail-safe that throws on the way out is not one.
    """
    body = urllib.parse.urlencode(
        {"u": universe, "d": ",".join(["0"] * DMX_UNIVERSE_SIZE)}
    ).encode("ascii")
    url = base_url.rstrip("/") + "/set_dmx"
    last = ""
    for attempt in range(max(1, retries)):
        try:
            with urllib.request.urlopen(url, data=body, timeout=timeout):
                return True
        # Whatever went wrong, the next attempt (or the caller) handles it. This
        # is the last code to run before a hot universe is left on the wire.
        except Exception as exc:  # noqa: BLE001 - see rationale above
            last = str(exc)
            if attempt + 1 < max(1, retries):
                time.sleep(pause)
    print(
        f"zvision: DMX park FAILED against {url} after {max(1, retries)} attempts: {last}"
        " -- the universe may still be hot",
        file=sys.stderr,
        flush=True,
    )
    return False


def _parse_from_args_env(raw: str, default_dmx: str) -> Optional[Tuple[str, int, str]]:
    """Pull --dmx / --dmx-universe / --dmx-url out of a service's own argument
    string (the same ``ZVISION_ARGS`` / ``ZDECK_ARGS`` value the unit's
    ``EnvironmentFile`` already exports to ``ExecStopPost``), so the crash
    fail-safe parks the universe the service actually lit instead of always
    universe 0 @ localhost.

    Returns ``(effective_dmx, universe, base_url)``, or ``None`` if ``raw``
    can't be parsed as a shell-style argument string -- the caller falls back
    to today's exact default behaviour rather than guessing.

    ``default_dmx`` stands in for whatever the *service's own* argparse
    default is when ``raw`` doesn't mention ``--dmx`` at all (zvision
    defaults to "none", zdeck to "ola" -- they differ, so this can't be
    hard-coded here).
    """
    try:
        tokens = shlex.split(raw)
    except ValueError:
        return None
    mini = argparse.ArgumentParser(add_help=False)
    mini.add_argument("--dmx", choices=_DMX_KINDS, default=None)
    mini.add_argument("--dmx-universe", type=int, default=None)
    mini.add_argument("--dmx-url", default=None)
    try:
        # argparse writes usage/error text to stderr and calls sys.exit() on a
        # bad token (e.g. a non-integer --dmx-universe); swallow both rather
        # than let a malformed env var either crash the fail-safe or spam the
        # crash-teardown log with an argparse usage banner.
        with contextlib.redirect_stderr(io.StringIO()):
            parsed, _unknown = mini.parse_known_args(tokens)
    except SystemExit:
        return None
    effective_dmx = parsed.dmx if parsed.dmx is not None else default_dmx
    universe = parsed.dmx_universe if parsed.dmx_universe is not None else 0
    base_url = parsed.dmx_url if parsed.dmx_url is not None else DEFAULT_URL
    return effective_dmx, universe, base_url


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="python3 -m zvision.dmxpark",
        description="Zero the DMX universe (crash fail-safe / operator kill).",
    )
    p.add_argument("--universe", type=int, default=0)
    p.add_argument("--url", default=DEFAULT_URL, help="olad HTTP API base URL")
    p.add_argument("--timeout", type=float, default=1.0)
    p.add_argument("--retries", type=int, default=3)
    p.add_argument("--quiet", action="store_true")
    p.add_argument(
        "--from-args-env", default=None, metavar="NAME",
        help="derive --universe/--url from the service's own argument string in "
        "this env var (e.g. ZVISION_ARGS/ZDECK_ARGS, already exported by the "
        "unit's EnvironmentFile to ExecStopPost too). An absent, empty or "
        "unparseable var falls back to --universe/--url above -- today's "
        "exact behaviour.",
    )
    p.add_argument(
        "--default-dmx", choices=_DMX_KINDS, default="none",
        help="assumed --dmx when the --from-args-env string doesn't mention "
        "--dmx at all; match the service's own argparse default (zvision: "
        "none, zdeck: ola)",
    )
    args = p.parse_args(argv)

    universe, base_url = args.universe, args.url
    if args.from_args_env:
        raw = os.environ.get(args.from_args_env, "")
        parsed = _parse_from_args_env(raw, args.default_dmx) if raw.strip() else None
        if parsed is None:
            # Unset, empty or unparseable: don't guess -- park universe 0 @
            # localhost, exactly as this tool always has.
            universe, base_url = 0, DEFAULT_URL
        else:
            effective_dmx, universe, base_url = parsed
            if effective_dmx != "ola":
                # This service isn't the one writing the universe (see the
                # single-writer split in DECK.md §3) -- parking here would
                # blackout the *other* writer's light on every routine
                # restart of this one.
                if not args.quiet:
                    print(
                        f"zvision: DMX park skipped ({args.from_args_env} says "
                        f"--dmx {effective_dmx}, not ola)",
                        flush=True,
                    )
                return 0

    ok = park(universe, base_url, args.timeout, args.retries)
    if ok and not args.quiet:
        print("zvision: DMX universe zeroed", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
