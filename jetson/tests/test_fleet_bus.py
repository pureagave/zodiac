"""The bus constants are one half of a cross-language rendezvous: the tablets'
Kotlin ``FleetBus`` hardcodes the same groups and ports, and the two sides
share no code — only these numbers. Nothing else in the suite exercises them
(every network test points at loopback), so before this file a one-digit typo
here passed the entire suite and simply parted the Jetson from every tablet:
green service, frames flowing, HUD dark. Changing any of these means changing
the Kotlin side in the same commit.
"""

import unittest

from zvision import fleet_bus


class FleetBusContractTest(unittest.TestCase):
    def test_threat_channel_matches_the_tablets_listener(self):
        # Kotlin NetworkThreatSource joins exactly this group and port.
        self.assertEqual("239.7.7.20", fleet_bus.THREAT_GROUP)
        self.assertEqual(10120, fleet_bus.THREAT_PORT)

    def test_telemetry_channel_matches_the_beacon_broadcast(self):
        # The $ZAUD listener rides the beacon's telemetry group.
        self.assertEqual("239.7.7.10", fleet_bus.TELEMETRY_GROUP)
        self.assertEqual(10110, fleet_bus.TELEMETRY_PORT)

    def test_frames_never_leave_the_vehicle(self):
        # TTL 1: one hop, never routed past the vehicle's own switch/AP.
        self.assertEqual(1, fleet_bus.TTL)

    def test_groups_are_administratively_scoped(self):
        # 239.0.0.0/8 is the never-routed-off-link multicast range; anything
        # else risks the vehicle's threat feed leaking through an uplink.
        for group in (fleet_bus.THREAT_GROUP, fleet_bus.TELEMETRY_GROUP):
            self.assertTrue(group.startswith("239."), group)


if __name__ == "__main__":
    unittest.main()
