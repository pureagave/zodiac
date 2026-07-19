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

    def send(self, frame: str) -> int:
        """Emit one frame to every target; returns how many sends succeeded."""
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
