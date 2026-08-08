"""Read-only HTTP access to the recorded track, for mirrors and for hands.

The Jetson holds the authoritative breadcrumb — it is always on and its
recorder never sleeps. Everything else takes *copies*. Serving those copies
over plain HTTP on the vehicle LAN means a tablet, a laptop or a phone can all
fetch the same bytes with no per-device push configuration and no agent to
install: `curl http://<jetson>:8087/zodiac-track-2026-08-30.csv`.

That direction matters. If the Jetson pushed, every new mirror would be a
config change on the one box that must not be fiddled with mid-burn; because
mirrors pull, adding one is a client-side decision and a failed mirror cannot
affect the recording.

**Read-only and LAN-only, deliberately.** This serves vehicle position history,
so it exposes exactly one directory, GET and HEAD only, and no path may escape
that directory — see [safe_name], which is the part worth testing.
"""

from __future__ import annotations

import http.server
import json
import os
import re
import socketserver
from urllib.parse import unquote, urlparse

DEFAULT_PORT = 8087

# Track files only. Anything else in the directory is not ours to hand out.
_NAME_RE = re.compile(r"^[A-Za-z0-9._-]+\.csv$")


def safe_name(raw: str) -> str | None:
    """The basename to serve for a request path, or None to refuse.

    Refuses anything that could leave the track directory. Traversal is the one
    way a read-only file server becomes a way to read `/etc/shadow`, so this
    rejects rather than sanitises: a request that needed cleaning up was not a
    request for a track file.
    """
    path = unquote(urlparse(raw).path).lstrip("/")
    if not path:
        return None
    # No directories at all — not "../", not "a/b", not an absolute path.
    if "/" in path or "\\" in path or path in (".", ".."):
        return None
    if not _NAME_RE.match(path):
        return None
    return path


def listing(directory: str) -> list[dict]:
    """Every track file with its size, newest first — enough for a mirror to
    decide what it still needs without downloading anything."""
    try:
        names = [n for n in os.listdir(directory) if _NAME_RE.match(n)]
    except OSError:
        return []
    out = []
    for name in sorted(names, reverse=True):
        try:
            out.append({"name": name, "bytes": os.path.getsize(os.path.join(directory, name))})
        except OSError:
            continue
    return out


class TrackHandler(http.server.BaseHTTPRequestHandler):
    directory = "/var/lib/zodiac/track"

    # Quieter journal: one line per request is noise at mirror cadence.
    def log_message(self, fmt, *args):  # noqa: A003 - stdlib signature
        pass

    def do_HEAD(self):  # noqa: N802 - stdlib signature
        self._respond(head_only=True)

    def do_GET(self):  # noqa: N802 - stdlib signature
        self._respond(head_only=False)

    def _respond(self, head_only: bool) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/index.json"):
            body = json.dumps(listing(self.directory)).encode()
            self._send(HTTPStatus_OK, "application/json", body, head_only)
            return

        name = safe_name(self.path)
        if name is None:
            self._send(HTTPStatus_FORBIDDEN, "text/plain", b"not a track file\n", head_only)
            return

        full = os.path.join(self.directory, name)
        if not os.path.isfile(full):
            self._send(HTTPStatus_NOT_FOUND, "text/plain", b"no such track\n", head_only)
            return
        try:
            with open(full, "rb") as handle:
                body = handle.read()
        except OSError:
            self._send(HTTPStatus_ERROR, "text/plain", b"unreadable\n", head_only)
            return
        self._send(HTTPStatus_OK, "text/csv", body, head_only)

    def _send(self, status: int, content_type: str, body: bytes, head_only: bool) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)


HTTPStatus_OK = 200
HTTPStatus_FORBIDDEN = 403
HTTPStatus_NOT_FOUND = 404
HTTPStatus_ERROR = 500


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(directory: str, port: int = DEFAULT_PORT) -> None:
    handler = type("BoundTrackHandler", (TrackHandler,), {"directory": directory})
    with _Server(("", port), handler) as httpd:
        httpd.serve_forever()


def main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(
        prog="zodiac-track-serve",
        description="Read-only HTTP access to the recorded track, for mirrors.",
    )
    parser.add_argument("--dir", default="/var/lib/zodiac/track")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args(argv)
    serve(args.dir, args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
