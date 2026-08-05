#!/usr/bin/env bash
# Pre-flight for flashing the Jetson Orin Nano. Run this on the flash host
# (grr) BEFORE putting the board in recovery mode.
#
# Everything here is read-only. The point is to fail in a warm room with the
# board still in its box, rather than after the jumper dance — the flash writes
# QSPI as well as the NVMe, and that is not a step you want to start twice.
#
#   ./scripts/preflight-flash.sh            # checks only
#   ./scripts/preflight-flash.sh --command  # also print the exact flash command
set -uo pipefail

L4T="${L4T_DIR:-$HOME/jetson/Linux_for_Tegra}"
BOARD="jetson-orin-nano-devkit-super"
ok=0; warn=0; fail=0
pass() { printf '  \033[32mOK\033[0m    %s\n' "$1"; ok=$((ok+1)); }
note() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; warn=$((warn+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }

echo "== BSP staging =="
if [ -d "$L4T" ]; then
  pass "Linux_for_Tegra at $L4T"
  [ -x "$L4T/tools/kernel_flash/l4t_initrd_flash.sh" ] \
    && pass "l4t_initrd_flash.sh present" || bad "l4t_initrd_flash.sh missing"
  [ -f "$L4T/$BOARD.conf" ] \
    && pass "board config $BOARD.conf" || bad "board config $BOARD.conf missing"
  [ -f "$L4T/tools/kernel_flash/flash_l4t_t234_nvme.xml" ] \
    && pass "t234 NVMe layout (t264 is Thor — wrong file)" \
    || bad "flash_l4t_t234_nvme.xml missing"
  # apply_binaries leaves NVIDIA libs in the rootfs; without it you flash a
  # stock Ubuntu with no Tegra support and only find out on first boot.
  [ -d "$L4T/rootfs/usr/lib/aarch64-linux-gnu/tegra" ] \
    && pass "apply_binaries.sh has been run (tegra libs in rootfs)" \
    || bad "rootfs has no tegra libs — run sudo ./apply_binaries.sh"
else
  bad "no BSP at $L4T (set L4T_DIR)"
fi

echo
echo "== host dependencies =="
for c in abootimg sshpass xmllint xmlstarlet zstd uuidgen; do
  command -v "$c" >/dev/null 2>&1 && pass "$c" || bad "$c missing (apt-get install)"
done
# Not the Ubuntu-standard name on 26.04; L4T hard-codes the -static suffix.
[ -e /usr/bin/qemu-aarch64-static ] \
  && pass "qemu-aarch64-static (symlink to qemu-user on 26.04)" \
  || bad "/usr/bin/qemu-aarch64-static missing — ln -sf /usr/bin/qemu-aarch64 it"

echo
echo "== pre-seeded image (headless first boot) =="
R="$L4T/rootfs"
if sudo test -f "$R/etc/passwd" 2>/dev/null; then
  u=$(sudo grep -m1 ':x:1000:' "$R/etc/passwd" 2>/dev/null | cut -d: -f1)
  [ -n "$u" ] && pass "user '$u' baked in (no oem-config stall)" \
              || bad "no uid-1000 user — run tools/l4t_create_default_user.sh"
  n=$(sudo grep -c '^ssh-' "$R/home/$u/.ssh/authorized_keys" 2>/dev/null || echo 0)
  [ "$n" -gt 0 ] && pass "$n ssh key(s) installed" || note "no ssh keys in the image"
  sudo test -f "$R/etc/sudoers.d/010-zodiac-nopasswd" \
    && pass "passwordless sudo" || note "no NOPASSWD sudo drop-in"
  sudo test -e "$R/etc/systemd/system/sockets.target.wants/ssh.socket" \
    && pass "sshd enabled on boot" || bad "ssh not enabled — headless box will be unreachable"
else
  bad "cannot read rootfs (need sudo?)"
fi

echo
echo "== disk =="
avail=$(df -BG --output=avail "$L4T" 2>/dev/null | tail -1 | tr -dc '0-9')
[ "${avail:-0}" -ge 40 ] && pass "${avail}G free (flash needs ~40G headroom)" \
                         || note "${avail:-?}G free — flash builds several GB of images"

echo
echo "== board =="
if lsusb 2>/dev/null | grep -q '0955:'; then
  pass "Jetson detected in RECOVERY mode ($(lsusb | grep -m1 '0955:' | sed 's/.*ID //'))"
else
  note "no 0955:xxxx on USB — board not in recovery yet (expected if you haven't done the jumper dance)"
fi

echo
printf '%d ok, %d warn, %d fail\n' "$ok" "$warn" "$fail"
if [ "${1:-}" = "--command" ]; then
  echo
  echo "Flash command (run from $L4T):"
  cat <<CMD
  sudo ./tools/kernel_flash/l4t_initrd_flash.sh \\
       --external-device nvme0n1p1 \\
       -c ./tools/kernel_flash/flash_l4t_t234_nvme.xml \\
       --showlogs --network usb0 \\
       $BOARD internal
CMD
  echo
  echo "  'internal' writes QSPI as well as the NVMe. DO NOT INTERRUPT IT."
fi
[ "$fail" -eq 0 ] || exit 1
