# Zodiac Deck — physical control surface

Six physical keys in the cab driving the tracker light, so nobody is aiming a
moving head by typing DMX values over ssh, or by tapping a touchscreen while
driving.

**Hardware:** Elgato Stream Deck Mini, `0fd9:0063`, firmware 3.03.002, 6 keys in
a 2×3 layout, 80×80 BMP key images. Verified working on the vehicle's unit
2026-08-09 — both directions, rendering and input.

**No USB hub required.** The Jetson devkit's four USB-A connectors are fed by one
onboard 4-port hub; the RGB camera, the DMX dongle and the thermal camera take
three and the deck takes the fourth. **Do not re-cable the cameras to make room**
— inserting a hub upstream changes every `by-path` in `ZVISION_ARGS` and the
cameras stop resolving.

---

## 1. Layout

```
 BLACKOUT     LAMP        HOME
 DIM -        DIM +       COLOUR
```

| Key | Does | Notes |
|---|---|---|
| BLACKOUT | dimmer → 0 | **red** when the head is lit, **grey** when already dark — a kill control that looks armed over a dark head is noise |
| LAMP | toggle 0 ↔ full | shows `ON` / `OFF` |
| HOME | pan+tilt to mid-travel | mid-pan is where the head should be mounted (§8.6b); mid-tilt is dead vertical (§8.6e) |
| DIM − / DIM + | step by 32 | both keys show the live level in purple |
| COLOUR | cycle on-palette slots | white / blue 34 / green 128 / purple 78 |

Colours follow `ui/concepts/ConceptTheme` exactly as the screens do: green for
controls, purple for live values, red for faults and kill only. **The amber
bands of the colour wheel (ch5 40–58, 90–118) are rejected at config
construction**, not merely avoided by convention.

---

## 2. Install

```bash
# device access — group, not TAG+="uaccess": the service is headless
sudo cp jetson/systemd/70-zodiac-streamdeck.rules /etc/udev/rules.d/
sudo usermod -aG plugdev zodiac
sudo udevadm control --reload-rules && sudo udevadm trigger

# isolated venv: StreamDeck and Pillow are NOT zvision dependencies and must
# not be installed into the system python it runs on
sudo apt-get install -y python3-venv libhidapi-libusb0
sudo mkdir -p /opt/zodiac-deck && sudo chown zodiac:zodiac /opt/zodiac-deck
python3 -m venv /opt/zodiac-deck/venv
/opt/zodiac-deck/venv/bin/pip install streamdeck pillow

sudo cp jetson/systemd/zodiac-deck.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now zodiac-deck
```

Run it by hand instead: `/opt/zodiac-deck/venv/bin/python -m zdeck --dmx fake`

---

## 3. ⚠️ Arbitration with the tracker is UNRESOLVED

**Today this works only because `zvision` runs `--dmx none`**, so nothing else
writes the universe and the deck owns it outright.

The moment the tracker is live at 8 Hz it overwrites anything the deck writes
within ~125 ms. BLACKOUT would *flicker*, not kill — which is worse than not
having the key at all, because the operator would believe the light was off.

**Do not enable `--dmx ola` on zvision and this service at the same time**
until an authority mechanism exists. Tracked in `tasks/open.md`.

---

## 4. Design notes

**Separate process, deliberately.** A wedged USB button hub must never be able
to stall the threat broadcaster the driver's night HUD depends on — the same
rule that makes `OlaDmxSink` swallow its send failures. The unit is
`After=olad` but explicitly **not** `After=zvision`: the operator's physical
control should come up whether or not the vision box is healthy.

**Never leaves the head hot.** The runner parks on every exit path, and the unit
carries the same `ExecStopPost=-... zvision.dmxpark` fail-safe as zvision, which
systemd runs on crash and kill. Necessary because `olad` owns frame timing and
retransmits the last universe forever, so a dead writer leaves a frozen beam —
and the fixture's own `BLnd=blac` cannot help, since from its side the signal
never stopped.

**A missing deck is normal, not an error.** This rig loses USB devices over
bumps. The runner waits and re-enumerates rather than exiting into a systemd
restart loop.

**Brightness starts at 20/100.** A Stream Deck at default brightness is a small
floodlight in a dark cab, and it would undo the same night-vision work the OLED
discipline and the `$ZENV` auto-dim exist for.

**Fixture wiring is borrowed, not restated.** Channel numbers come from
`zvision.tracker.TrackerConfig`, so the deck and the tracker cannot disagree
about which channel is the dimmer — a disagreement that on this fixture puts
brightness on the colour wheel and makes the head look dead (§7).
