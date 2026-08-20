"""The edge box's own ``$ZVER`` identity and emit cadence (FLEET-1).

zvision announces what build it is running on the version bus so the hero tablet
can flag a stale edge box the same way it flags a stale tablet — no server, no
SSH, works offline. The identity is derived once at startup:

  * ``base``  — :data:`zvision.__version__`.
  * ``sha`` / ``dirty`` / ``epoch`` — from ``git`` in the deployed checkout
    (``/opt/zodiac`` is a real clone that can ``git`` at runtime). **Every unknown
    fails toward unknown/dirty**, exactly like the app's ``BuildIdentity``: a box
    that cannot identify its build must read *unknown*, never a confident current.
  * ``node`` — a stable per-box key from ``/etc/machine-id`` (the roster's primary
    key); ``name`` — the hostname (``zvision``), the human label.

Kept import-light and standard-library only (zvision's rule): ``git`` is shelled
out with a short timeout and any failure is swallowed to the unknown fallback, so
this never blocks the pipeline and runs fine where there is no git (CI, a tarball
deploy, ``--source fake``).
"""

from __future__ import annotations

import re
import socket
import subprocess
import time
from pathlib import Path
from typing import Callable, List, Optional

from . import __version__
from .version_protocol import FleetVersion

# jetson/zvision/version_report.py -> jetson/zvision -> jetson -> repo root.
_REPO = Path(__file__).resolve().parents[2]
_MACHINE_ID = "/etc/machine-id"
_SHA_RE = re.compile(r"[0-9a-f]{7,40}")
_GIT_TIMEOUT_SECS = 3.0
_NODE_LEN = 6

DEFAULT_PERIOD_SECS = 10.0

GitRunner = Callable[[List[str], Path], Optional[str]]


def _run_git(args: List[str], cwd: Path) -> Optional[str]:
    """Run ``git -C cwd <args>`` and return trimmed stdout, or ``None`` on any
    failure (git absent, not a repo, non-zero exit, timeout)."""
    try:
        out = subprocess.run(
            ["git", "-C", str(cwd), *args],
            capture_output=True,
            text=True,
            timeout=_GIT_TIMEOUT_SECS,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def _stable_node(machine_id_path: str, hostname: str) -> str:
    """A stable, mostly-unique key: the last 6 of ``/etc/machine-id`` (uppercased
    hex), falling back to the hostname. ``build()`` sanitises it again, so this
    only needs to be stable and reasonably unique per box."""
    try:
        mid = Path(machine_id_path).read_text().strip()
    except OSError:
        mid = ""
    source = mid or hostname
    cleaned = "".join(ch for ch in source.upper() if ch.isdigit() or "A" <= ch <= "Z")
    return cleaned[-_NODE_LEN:] or "0"


def self_version(
    run_git: GitRunner = _run_git,
    repo: Path = _REPO,
    hostname: Optional[str] = None,
    machine_id_path: str = _MACHINE_ID,
    version: str = __version__,
) -> FleetVersion:
    """This box's build as a :class:`FleetVersion`. Every git failure degrades to
    the unknown/dirty fallback rather than raising."""
    host = hostname or socket.gethostname()
    raw_sha = run_git(["rev-parse", "--short=9", "HEAD"], repo)
    porcelain = run_git(["status", "--porcelain"], repo)
    raw_epoch = run_git(["show", "-s", "--format=%ct", "HEAD"], repo)

    sha = raw_sha if (raw_sha is not None and _SHA_RE.fullmatch(raw_sha)) else "unknown"
    # git failed (None) -> dirty; a non-empty porcelain -> dirty; empty -> clean.
    dirty = porcelain is None or bool(porcelain)
    epoch = int(raw_epoch) if (raw_epoch is not None and raw_epoch.isdigit()) else 0
    return FleetVersion(node=_stable_node(machine_id_path, host), name=host, base=version, sha=sha, dirty=dirty, epoch=epoch)


class VersionScheduler:
    """Decides when to (re)send the fixed version sentence — pure, on an
    injectable clock, so the cadence is unit-tested without a running loop. The
    first :meth:`due` returns True (announce at once); thereafter at most once per
    ``period`` (a heartbeat, so a tablet that just joined re-learns within one
    period)."""

    def __init__(self, period: float = DEFAULT_PERIOD_SECS, clock=None) -> None:
        self._period = period
        self._clock = clock or time.monotonic
        self._last_sent_at: Optional[float] = None

    def due(self) -> bool:
        now = self._clock()
        if self._last_sent_at is None or (now - self._last_sent_at) >= self._period:
            self._last_sent_at = now
            return True
        return False
