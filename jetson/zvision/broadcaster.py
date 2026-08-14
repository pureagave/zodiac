"""Emit ZTHREAT frames onto the fleet bus.

Sends every frame to BOTH the multicast group AND the subnet broadcast address,
so the tablets receive it whether they share the wireless segment (multicast) or
sit across a wired<->wireless router bridge that eats multicast (broadcast).
Per-target error isolation: one dead path never blocks the other — a lesson paid
for on the phone beacon.
"""

from __future__ import annotations

import socket
import struct
from typing import List, Optional

from . import fleet_bus


def local_ip(probe: str = fleet_bus.THREAT_GROUP) -> str:
    """Best-effort local IPv4 on the vehicle network. A UDP ``connect`` picks the
    outbound interface without sending a packet; falls back to loopback if the
    route can't be resolved (e.g. no network yet)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((probe, fleet_bus.THREAT_PORT))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def subnet_broadcast(ip: str) -> str:
    """Assume a /24 and return x.x.x.255 — matches the beacon's fallback so a
    router that drops multicast at the bridge is still covered."""
    octets = ip.split(".")
    if len(octets) != 4:
        return "255.255.255.255"
    return ".".join(octets[:3] + ["255"])


class ThreatBroadcaster:
    def __init__(
        self,
        group: str = fleet_bus.THREAT_GROUP,
        port: int = fleet_bus.THREAT_PORT,
        ttl: int = fleet_bus.TTL,
        iface_ip: Optional[str] = None,
        broadcast: Optional[str] = None,
        bind_ip: Optional[str] = None,
        extra_targets: Optional[List[str]] = None,
    ) -> None:
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.setsockopt(
            socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, struct.pack("b", ttl)
        )
        # Pin multicast egress to the vehicle-network NIC when it's known (the
        # Jetson may be multi-homed: wired to the router + a debug link).
        if iface_ip:
            self.sock.setsockopt(
                socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface_ip)
            )
        # Bind the source address so broadcast/unicast egress goes out the chosen
        # NIC on a multi-homed host — otherwise the OS may pick the wrong route
        # (e.g. a VPN interface) and sends fail with EHOSTUNREACH.
        if bind_ip:
            self.sock.bind((bind_ip, 0))
        ip = bind_ip or iface_ip or local_ip()
        bcast = broadcast or subnet_broadcast(ip)
        self.targets: List[tuple] = [(group, port), (bcast, port)]
        if extra_targets:
            self.targets.extend((h, port) for h in extra_targets)
        # The subnet-broadcast target is derived from the local IP, which is
        # loopback when we're constructed before the network/DHCP is up (the
        # cold-boot race: systemd's network-online.target can be satisfied by
        # link-up, not a lease). Re-derive it lazily until a real address appears,
        # then freeze — so a Jetson that starts before its lease still reaches the
        # subnet-broadcast fallback instead of being stuck at 127.0.0.255 for the
        # whole process lifetime. Only the auto-derived target is refreshed; an
        # operator override (broadcast / bind_ip / iface_ip) is taken verbatim.
        self._bcast_index = 1
        self._broadcast_ip = ip
        self._auto_broadcast = (
            broadcast is None and bind_ip is None and iface_ip is None
        )

    def _refresh_broadcast(self) -> None:
        """Recover the auto subnet-broadcast target if it froze at a loopback
        address because the network wasn't up at construction. Probes only while
        still loopback; once a real address resolves it freezes, so steady state
        costs nothing."""
        if not self._auto_broadcast or not self._broadcast_ip.startswith("127."):
            return
        ip = local_ip()
        if ip.startswith("127."):
            return
        self._broadcast_ip = ip
        self.targets[self._bcast_index] = (subnet_broadcast(ip), self.port)

    def send(self, frame: str) -> int:
        """Emit one frame to every target; returns how many sends succeeded."""
        self._refresh_broadcast()
        data = frame.encode("ascii")
        ok = 0
        for target in self.targets:
            try:
                self.sock.sendto(data, target)
                ok += 1
            except OSError:
                pass
        return ok

    def close(self) -> None:
        self.sock.close()
