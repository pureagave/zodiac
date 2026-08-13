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
| BLACKOUT | dimmer → 0, **and** zeroes the whole universe via `dmxpark.park()` | **red** when the head is lit, **grey** when already dark — a kill control that looks armed over a dark head is noise. Deliberately **bypasses `OlaDmxSink`**, which swallows its own send failures by design — right for a tracker frame, wrong for a kill. `park()` retries and reports |
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

# the zdeck package itself must be where the unit runs it from
sudo mkdir -p /opt/zodiac/jetson
sudo cp -r jetson/zdeck /opt/zodiac/jetson/

sudo cp jetson/systemd/zodiac-deck.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now zodiac-deck
```

> **The `cp -r jetson/zdeck` step is not optional and nothing else does it.**
> `zodiac-deck.service` runs `-m zdeck` with `WorkingDirectory=/opt/zodiac/jetson`,
> but `scripts/install.sh` copies only the `zvision` package and `pyproject.toml`
> packages only `zvision`. On a plain `git clone` to `/opt/zodiac` it happens to
> work because the checkout is already there; on an `install.sh`-from-elsewhere
> box it fails with `ModuleNotFoundError: zdeck`. Re-copy after every update.

Run it by hand instead: `/opt/zodiac-deck/venv/bin/python -m zdeck --dmx fake`

> **`zdeck`'s `--dmx` defaults to `ola`** — the opposite of `zvision`, whose DMX
> output defaults to `none`. That asymmetry is deliberate (the deck exists to
> drive the light; the vision runner does not, yet) and it is what §3's safety
> argument rests on. Pass `--dmx fake` explicitly for a dry run.

---

## 3. ⚠️ Arbitration with the tracker is UNRESOLVED

**Today this works only because `zvision` runs `--dmx none`**, so nothing else
writes the universe and the deck owns it outright.

The moment the tracker is live at 8 Hz it overwrites anything the deck writes
within ~125 ms. BLACKOUT would *flicker*, not kill — which is worse than not
having the key at all, because the operator would believe the light was off.

**Do not enable `--dmx ola` on zvision and this service at the same time**
until an authority mechanism exists. Tracked in `tasks/open.md`.

The crash fail-safe (`ExecStopPost=... zvision.dmxpark`) on both units now
reads its own service's `ZVISION_ARGS`/`ZDECK_ARGS` (via `--from-args-env`) to
find the universe/URL it should zero, and — for `zvision.service` specifically
— **skips the park entirely** when `ZVISION_ARGS` doesn't ask for `--dmx ola`
(its own CLI default). That means a routine `systemctl restart zvision` in the
documented split layout (zvision `--dmx none`, deck `--dmx ola`) no longer
blacks out the deck-owned universe. It does **not** fix the two-writer case
above: if both are set to `--dmx ola`, both still write, and only a live
authority latch (not yet built) would stop the flicker.

---

## 4. Design notes

**Separate process, deliberately.** A wedged USB button hub must never be able
to stall the threat broadcaster the driver's night HUD depends on — the same
rule that makes `OlaDmxSink` swallow its send failures. The unit is
`After=olad` but explicitly **not** `After=zvision`: the operator's physical
control should come up whether or not the vision box is healthy.

**A failed kill is visible.** If the blackout send throws, key 0 re-renders as
**`DMX FAIL`** in red and LAMP turns red too. A calm grey panel over a live beam
is the one lie that matters on this device, so the panel says it does not know
rather than saying "dark".

**Never leaves the head hot.** The runner parks on every exit path — *and when
the deck itself disappears*, which is the case most likely to happen on the
vehicle: while the panel is gone the operator has no kill control, so the beam
must not be left burning. The unit also carries the same
`ExecStopPost=-... zvision.dmxpark` fail-safe as zvision, which systemd runs on
crash and kill. Necessary because `olad` owns frame timing and
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
