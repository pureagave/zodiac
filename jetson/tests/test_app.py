"""End-to-end smoke of the runner over a loopback socket: `--once` must emit one
detection frame and then the guaranteed all-clear frame on exit (the "HUD doesn't
freeze" contract)."""

import socket
import unittest

from zvision.app import main
from zvision.threat_protocol import parse_frame


class OnceRunTest(unittest.TestCase):
    def test_once_emits_one_frame_then_all_clear(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.bind(("127.0.0.1", 0))
        rx.settimeout(2.0)
        port = rx.getsockname()[1]

        # rx is already bound, so datagrams main() sends are buffered for us to
        # read after it returns — no threading needed.
        rc = main(
            [
                "--source", "fake", "--once",
                "--group", "127.0.0.1", "--broadcast", "127.0.0.1", "--port", str(port),
            ]
        )
        self.assertEqual(0, rc)

        frames = []
        try:
            while True:
                data, _ = rx.recvfrom(4096)
                frames.append(parse_frame(data.decode("ascii")))
        except socket.timeout:
            pass
        finally:
            rx.close()

        self.assertGreaterEqual(len(frames), 2)
        self.assertEqual(3, len(frames[0]))  # first frame: the 3 fake contacts
        self.assertIn([], frames)            # the exit all-clear frame is present


if __name__ == "__main__":
    unittest.main()
