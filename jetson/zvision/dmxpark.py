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
import sys
import time
import urllib.parse
import urllib.request

DMX_UNIVERSE_SIZE = 512
DEFAULT_URL = "http://127.0.0.1:9090"


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
    args = p.parse_args(argv)
    ok = park(args.universe, args.url, args.timeout, args.retries)
    if ok and not args.quiet:
        print("zvision: DMX universe zeroed", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
