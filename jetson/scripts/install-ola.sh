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
# WHERE olad ACTUALLY READS CONFIG. Do not hard-code this: the Ubuntu 24.04
# arm64 package on our JetPack 7.2 image runs olad with `--config-dir /etc/ola`,
# while /var/lib/ola/conf also exists and looks plausible. Writing to the wrong
# one fails silently — every plugin keeps its default, ftdidmx stays disabled,
# and the dongle simply never appears in ola_dev_info. Cost an hour on
# 2026-08-07. Ask the daemon instead of guessing.
detect_conf_dir() {
  local d
  d=$(ps -o args= -C olad 2>/dev/null | grep -o -- '--config-dir[= ][^ ]*' | head -1 | sed 's/.*[= ]//')
  [ -n "$d" ] && { echo "$d"; return; }
  d=$(grep -ho -- '--config-dir[= ][^ "]*' /etc/default/ola* /etc/init.d/olad 2>/dev/null | head -1 | sed 's/.*[= ]//')
  [ -n "$d" ] && { echo "$d"; return; }
  [ -d /etc/ola ] && { echo /etc/ola; return; }
  echo /var/lib/ola/conf
}

echo ">> installing OLA (olad + CLI tools)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ola

echo ">> granting olad raw USB access to the FTDI dongle"
# ftdidmx drives the dongle through libftdi (raw USB), not the /dev/ttyUSB0 node
# that ftdi_sio creates — so group `dialout` is not enough. Without this, olad
# logs "Unable to fetch string information from USB device: libusb_open() failed"
# and reports zero FTDI devices. libftdi detaches ftdi_sio itself once it can
# actually open the device, so nothing needs blacklisting.
cat > /etc/udev/rules.d/60-ola-ftdi-dmx.rules <<'EOF'
# FTDI FT232 USB->DMX dongle for the zvision tracker light. plugdev is the group
# the ola package already puts the olad user in.
SUBSYSTEM=="usb", ATTR{idVendor}=="0403", ATTR{idProduct}=="6001", MODE="0664", GROUP="plugdev"
EOF
udevadm control --reload-rules && udevadm trigger --attr-match=idVendor=0403 || true

CONF="$(detect_conf_dir)"
echo ">> enabling ONLY the ftdidmx plugin in ${CONF}; disabling the ones that fight for the FT232"
# ftdidmx talks to the dongle via libftdi (direct USB). usbserial/opendmx/usbdmx
# contend for the same hardware; stageprofi is worse — it grabs /dev/ttyUSB0 on a
# timer, takes and releases the lock every few seconds, and spams the log. The
# network plugins just add devices nobody patches.
mkdir -p "$CONF"
set_plugin() {
  local f="$CONF/ola-$1.conf"
  if [ -f "$f" ]; then sed -i "s/^enabled = .*/enabled = $2/" "$f"
  else printf 'enabled = %s\n' "$2" > "$f"; fi
}
set_plugin ftdidmx true
for p in usbserial opendmx usbdmx stageprofi artnet spi osc \
         shownet espnet sandnet pathport e131 kinet milinst renard karate gpio openpixelcontrol; do
  set_plugin "$p" false
done
chown -R olad:olad "$CONF" 2>/dev/null || true

echo ">> pinning olad to core ${DMX_CORE} + making it self-heal (Restart=always)"
mkdir -p /etc/systemd/system/olad.service.d
# The stock ola package ships Restart=no, so a crashed olad stays dead until the
# next reboot. With DMX enabled olad owns the tracker-light universe and nothing
# else re-raises it, so a crash = tracker light dead for the night. Make it
# self-heal like our own zodiac-* units. StartLimitIntervalSec goes in [Unit]
# (NOT [Service]) so power-flap restarts are never throttled off — the same
# placement trap zvision.service guards against in its comments.
cat > /etc/systemd/system/olad.service.d/override.conf <<EOF
[Unit]
StartLimitIntervalSec=0
[Service]
CPUAffinity=${DMX_CORE}
Restart=always
RestartSec=2
EOF

systemctl daemon-reload
systemctl enable olad
systemctl restart olad

cat <<EOF

>> OLA installed. olad running (pinned to core ${DMX_CORE}), HTTP API on :9090.

Patch the dongle to universe ${UNIVERSE} (the dongle must be plugged in first —
the port only exists once it enumerates):

  ola_dev_info                                  # note the FT232R device + port number
  ola_patch -d <device> -p <port> -u ${UNIVERSE}       # NB: our FT232R lands on port 1, not 0
  # ch5 is the COLOUR WHEEL, not the dimmer: the old 128,0,128,0,255 form set
  # ch5=255 (auto-spin) and never touched ch8, so the head panned in the dark —
  # indistinguishable from a dead fixture (MOVING-HEAD.md §7 Trap 1).
  ola_set_dmx -u ${UNIVERSE} -d 128,0,128,0,0,0,0,255  # pan/tilt + ch8 dimmer — head moves AND lights
  python3 -m zvision --source fake --dmx ola    # live: aims at the fake contacts

If UNIVERSE is not 0, put --dmx-universe ${UNIVERSE} (and --dmx-url if olad
isn't on 127.0.0.1:9090) inside ZVISION_ARGS/ZDECK_ARGS in
/etc/default/zvision / /etc/default/zodiac-deck, not just on a hand-typed
command line — the crash fail-safe (ExecStopPost=... zvision.dmxpark) reads
those same env vars to know which universe to zero on a crash/SIGKILL.

Verify olad actually opened it — this line is the one that matters:
  journalctl -t olad | grep -i ftdi
  ... "Found FTDI device. Vendor: 'FTDI', Name: 'FT232R USB UART'"
  ... "Granularity for FTDI thread is GOOD"          <- DMX timing will hold
If instead you see "libusb_open() failed", the udev rule above did not apply;
re-plug the dongle or reboot so udev re-evaluates it.

Sanity first (no hardware needed to see the maths):
  python3 -m zvision --source fake --dmx fake -v         # logs target/pan/tilt/dim
EOF
