#!/usr/bin/env bash
# Provision zvision on the Jetson as an auto-starting systemd service.
# Run from the repo:  sudo jetson/scripts/install.sh
set -euo pipefail

DEST=/opt/zodiac/jetson
SRC="$(cd "$(dirname "$0")/.." && pwd)"

# The documented install (DEPLOY.md §3) is a git clone directly at /opt/zodiac,
# in which case this script is already running from ${DEST} and there is
# nothing to copy — cp aborts on "identical" paths, and with set -e that killed
# the whole install before the service unit was ever written. Only copy when
# provisioning from a checkout somewhere else.
if [[ "${SRC}" != "${DEST}" ]]; then
  echo ">> installing zvision from ${SRC} to ${DEST}"
  sudo mkdir -p "${DEST}"
  sudo cp -r "${SRC}/zvision" "${DEST}/"
else
  echo ">> running from ${DEST} — service will use this checkout directly"
fi

# Default runtime args — edit here to switch source (fake|thermal|rgb), rate,
# FOV, or pin the vehicle-network NIC with --iface-ip.
if [[ ! -f /etc/default/zvision ]]; then
  echo ">> writing default config /etc/default/zvision"
  sudo tee /etc/default/zvision >/dev/null <<'EOF'
# Arguments passed to `python3 -m zvision`. See jetson/DEPLOY.md.
# Start with fake to prove the bus + HUD, then switch to the thermal. The
# thermal is serial-pinned to a stable /dev/zodiac-thermal by the udev rule
# below, so it works on ANY USB port:
#   ZVISION_ARGS=--hz 8 --camera thermal:/dev/zodiac-thermal:az=0:fov=160:lens=fisheye:fovref=d:name=thermal
ZVISION_ARGS=--source fake --hz 10
EOF
else
  echo ">> keeping existing /etc/default/zvision"
fi

echo ">> installing zodiac udev rules (device identity, port-independent)"
# Serial-pin the thermal to /dev/zodiac-thermal and give the Stream Deck its
# stable node — both survive a re-plug into any USB port. (The identity-blind
# Arducam RGB ring stays port-pinned via /dev/v4l/by-path; it has no rule here.)
sudo cp "${SRC}/systemd/70-zodiac-thermal.rules"   /etc/udev/rules.d/
sudo cp "${SRC}/systemd/70-zodiac-streamdeck.rules" /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=video4linux --action=add

echo ">> installing systemd unit"
sudo cp "${SRC}/systemd/zvision.service" /etc/systemd/system/zvision.service
sudo systemctl daemon-reload
sudo systemctl enable zvision.service
sudo systemctl restart zvision.service

echo ">> done. Live logs:  journalctl -u zvision -f"
