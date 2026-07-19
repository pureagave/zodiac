"""Proves the send path end-to-end over the loopback: a real UDP socket receives
what the broadcaster emits, and it parses back to the contacts we put in. This is
the same round-trip the tablet does on the wire, minus the physical network."""

import socket
import unittest

from zvision.broadcaster import ThreatBroadcaster, subnet_broadcast
from zvision.threat import DriverThreat
from zvision.threat_protocol import format_frame, parse_frame


class SubnetBroadcastTest(unittest.TestCase):
    def test_slash24_broadcast_address(self):
        self.assertEqual("192.168.0.255", subnet_broadcast("192.168.0.234"))

    def test_garbage_falls_back_to_global_broadcast(self):
        self.assertEqual("255.255.255.255", subnet_broadcast("not-an-ip"))


class TargetsTest(unittest.TestCase):
    def test_extra_targets_are_appended_with_the_shared_port(self):
        tx = ThreatBroadcaster(
            group="127.0.0.1", port=10120, broadcast="127.0.0.1", extra_targets=["10.0.0.5", "10.0.0.6"]
        )
        try:
            self.assertIn(("10.0.0.5", 10120), tx.targets)
            self.assertIn(("10.0.0.6", 10120), tx.targets)
        finally:
            tx.close()

    def test_send_returns_the_success_count(self):
        tx = ThreatBroadcaster(group="127.0.0.1", port=9, broadcast="127.0.0.1")
        try:
            # Two loopback targets; UDP sends succeed with no receiver.
            self.assertEqual(2, tx.send(format_frame([])))
        finally:
            tx.close()

    def test_one_dead_target_does_not_block_the_others(self):
        # The module's headline claim: one failing path never blocks the other.
        # A builtin socket's sendto is read-only, so wrap it.
        class FlakySock:
            def __init__(self, real):
                self._real = real
                self.calls = 0

            def sendto(self, data, target):
                self.calls += 1
                if self.calls == 1:
                    raise OSError("simulated dead path")
                return self._real.sendto(data, target)

            def close(self):
                self._real.close()

        tx = ThreatBroadcaster(group="127.0.0.1", port=9, broadcast="127.0.0.1")
        tx.sock = FlakySock(tx.sock)
        try:
            sent = tx.send(format_frame([DriverThreat(rel_az_deg=0.0, size=0.5, id=1)]))
        finally:
            tx.close()
        self.assertEqual(1, sent)  # first path died, second still delivered


class LoopbackSendTest(unittest.TestCase):
    def test_broadcast_frame_is_received_and_parses(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        rx.bind(("127.0.0.1", 0))
        rx.settimeout(2.0)
        port = rx.getsockname()[1]

        # Point both the "group" and "broadcast" targets at loopback so the test
        # never depends on real multicast/broadcast routing.
        tx = ThreatBroadcaster(group="127.0.0.1", port=port, broadcast="127.0.0.1")
        threats = [
            DriverThreat(rel_az_deg=-8.0, size=0.30, collision=False, id=1),
            DriverThreat(rel_az_deg=5.0, size=0.75, collision=True, id=2),
        ]
        try:
            sent = tx.send(format_frame(threats))
            self.assertGreaterEqual(sent, 1)
            data, _ = rx.recvfrom(4096)
        finally:
            tx.close()
            rx.close()

        parsed = parse_frame(data.decode("ascii"))
        self.assertEqual(2, len(parsed))
        self.assertEqual(1, parsed[0].id)
        self.assertTrue(parsed[1].collision)

    def test_bind_ip_pins_the_source_address(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.bind(("127.0.0.1", 0))
        rx.settimeout(2.0)
        port = rx.getsockname()[1]
        # Bind the sender to loopback and confirm the datagram arrives from it.
        tx = ThreatBroadcaster(
            group="127.0.0.1", port=port, broadcast="127.0.0.1", bind_ip="127.0.0.1"
        )
        try:
            tx.send(format_frame([DriverThreat(rel_az_deg=0.0, size=0.5, id=9)]))
            _, addr = rx.recvfrom(4096)
        finally:
            tx.close()
            rx.close()
        self.assertEqual("127.0.0.1", addr[0])

    def test_all_clear_frame_round_trips(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.bind(("127.0.0.1", 0))
        rx.settimeout(2.0)
        port = rx.getsockname()[1]
        tx = ThreatBroadcaster(group="127.0.0.1", port=port, broadcast="127.0.0.1")
        try:
            tx.send(format_frame([]))
            data, _ = rx.recvfrom(4096)
        finally:
            tx.close()
            rx.close()
        self.assertEqual([], parse_frame(data.decode("ascii")))


if __name__ == "__main__":
    unittest.main()
