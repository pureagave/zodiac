# Kiosk provisioning

Locks a fleet tablet to the cockpit so a passenger can't leave the app, the
lockscreen ads never appear, and the tablet can't reboot into an OS update
mid-event.

## Why device owner, and why a factory reset

Android has two kiosk grades:

| | Escapable? | Survives reboot? | Kills lockscreen? | Needs reset? |
|---|---|---|---|---|
| Screen pinning | yes, back+recents | no | no | no |
| **Lock task as device owner** | no | yes | yes | **yes** |

Screen pinning tells the user how to leave. That's fine for a demo and useless
for a tablet strangers will poke all week.

Device owner can only be set on a device with **no accounts configured**, which
in practice means straight after a factory reset. That is the entire reason the
reset is needed — it is not superstition, the `dpm` command refuses otherwise.

## Steps, per tablet

1. **Factory reset.** Settings → Device Options → Reset to Factory Defaults.
2. **Skip account sign-in during setup.** This is the step that matters. If an
   Amazon/Google account gets added, `dpm` will refuse and you have to reset
   again. Skip Wi-Fi too if it pushes you toward signing in; add it afterwards.
3. Enable developer options and ADB (Settings → Device Options → About →
   tap Serial Number ×7, then Developer Options → ADB debugging).
4. Install and provision:

   ```sh
   adb install -r app-debug.apk
   adb shell dpm set-device-owner \
     org.pureagave.zodiac.control/.kiosk.ZodiacDeviceAdminReceiver
   ```

   Success prints `Active admin set to component {...}`.

5. Launch the app. It locks itself on resume.

### Verify

```sh
adb shell dumpsys device_policy | grep -i "device owner"
adb logcat -d | grep "kiosk:"          # "kiosk: locked to org.pureagave..."
```

## Getting back out

Device owner **cannot be removed without another factory reset** — that is the
point of it. For servicing, uninstalling the app is the escape hatch:

```sh
adb shell dpm remove-active-admin \
  org.pureagave.zodiac.control/.kiosk.ZodiacDeviceAdminReceiver
```

Plan on this being a one-way door per tablet, and provision the fleet only once
the build is settled.

## Which tablets should get it

- **Passenger displays** — yes. This is exactly what it's for.
- **S9+ hero dashboard** — yes, if it's permanently mounted.
- **A54 driver phone** — probably not. Kiosk means no maps, no phone, no
  camera on that device, which is a lot to give up on the one screen the driver
  might genuinely need something else from.
- **XCover beacon** — no. It's headless already; kiosk buys nothing.

## Gotcha

Renaming or deleting `ZodiacDeviceAdminReceiver` breaks provisioning on every
already-provisioned tablet, and the only fix is another factory reset. The class
is intentionally empty so there is never a reason to touch it.
