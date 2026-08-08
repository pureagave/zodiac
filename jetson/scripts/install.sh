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
# Start with fake to prove the bus + HUD, then switch to thermal:
#   ZVISION_ARGS=--source thermal --device /dev/video0 --hz 10
ZVISION_ARGS=--source fake --hz 10
EOF
else
  echo ">> keeping existing /etc/default/zvision"
fi

echo ">> installing systemd unit"
sudo cp "${SRC}/systemd/zvision.service" /etc/systemd/system/zvision.service
sudo systemctl daemon-reload
sudo systemctl enable zvision.service
sudo systemctl restart zvision.service

echo ">> done. Live logs:  journalctl -u zvision -f"
