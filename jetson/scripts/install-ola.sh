#!/usr/bin/env bash
# Install + configure OLA (Open Lighting Architecture) on the Jetson to drive the
# DMX tracker light through an FTDI USB->DMX dongle. zvision's OlaDmxSink POSTs the
# universe to olad's HTTP API (127.0.0.1:9090); olad owns the dongle + DMX timing.
#
#   sudo jetson/scripts/install-ola.sh
#
# Env overrides:  DMX_CORE=5  UNIVERSE=0
set -euo pipefail

DMX_CORE="${DMX_CORE:-5}"   # pin olad to this core so the ML workload can't jitter DMX timing
UNIVERSE="${UNIVERSE:-0}"
CONF=/var/lib/ola/conf      # Debian/Ubuntu olad service config dir

echo ">> installing OLA (olad + CLI tools)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ola

echo ">> enabling ONLY the ftdidmx plugin; disabling the ones that fight for the FT232"
# ftdidmx talks to the dongle via libftdi (direct USB). usbserial/opendmx/usbdmx
# would contend for the same device, so leave only ftdidmx on. Best-effort: if the
# config dir differs on your image, set these via the web UI (:9090 -> plugins).
mkdir -p "$CONF"
set_plugin() { printf 'enabled = %s\n' "$2" > "$CONF/ola-$1.conf"; }
set_plugin ftdidmx true
set_plugin usbserial false
set_plugin opendmx false
set_plugin usbdmx false
set_plugin artnet false      # local USB path, not Art-Net
set_plugin spi false
set_plugin osc false
chown -R olad:olad "$CONF" 2>/dev/null || true

echo ">> pinning olad to CPU core ${DMX_CORE} (host-timed DMX must not be jittered)"
mkdir -p /etc/systemd/system/olad.service.d
cat > /etc/systemd/system/olad.service.d/override.conf <<EOF
[Service]
CPUAffinity=${DMX_CORE}
EOF

systemctl daemon-reload
systemctl enable olad
systemctl restart olad

cat <<EOF

>> OLA installed. olad running (pinned to core ${DMX_CORE}), HTTP API on :9090.

ONE manual step, once the FTDI dongle is plugged in (needs the port to enumerate):
  1. plug in the USB->DMX dongle + connect the XLR to the moving head
  2. ola_dev_info                                   # find the ftdidmx device + port
  3. ola_patch -d <device> -p <port> -u ${UNIVERSE}     # patch it to universe ${UNIVERSE}
                                                    # (or web UI: http://<jetson>:9090)
  4. ola_set_dmx -u ${UNIVERSE} -d 128,0,128,0,255      # pan/tilt/dimmer test — head should move
  5. python3 -m zvision --source fake --dmx ola         # live: aims at the fake contacts

Sanity first (no hardware needed to see the maths):
  python3 -m zvision --source fake --dmx fake -v         # logs target/pan/tilt/dim
EOF
