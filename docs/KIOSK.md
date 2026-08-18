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

   ⚠️ **Provision with a release-signed APK from a stable, backed-up keystore** —
   not a per-machine `app-debug.apk`. A device-owner app can't be uninstalled and
   can only be *updated* by an APK signed with the **same** key, so a tablet
   provisioned with one laptop's debug keystore can never be moved to a release
   build (or a debug build from any other machine) without a factory reset. The
   `app-debug.apk` above is fine for a throwaway test tablet; use the release APK
   for anything that stays in the fleet.

5. Launch the app. It locks itself on resume.

### Runtime permissions

A device owner does **not** auto-grant runtime permissions — and once
`engage()` disables the status bar and enters lock task, there is no way for a
passenger (or an operator) to reach Settings to grant one that's missing. A
dangerous permission not already held before the tablet was kiosked would
otherwise be trapped, ungrantable, forever. `engage()` closes that trap by
pre-granting the app's own requested set
(`requiredCockpitPermissions`): `ACCESS_FINE_LOCATION` always, plus
`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` on API 31+ (the Samsungs). It grants
nothing beyond what the app already declares in the manifest and already asks
for at runtime.

### Auto-relaunch after a reboot

A kiosked tablet **relaunches the cockpit by itself** after a reboot or power
loss — the failure the fleet can least afford is a mounted display sitting on the
stock (ad) launcher until someone finds and taps the app. `engage()` makes the
cockpit the device's **Home app** (a persistent preferred Home, so no chooser
appears), which is the one reliable auto-launch on modern Android: a
`BOOT_COMPLETED` receiver is **blocked from starting an activity in the
background**, but the system always launches Home at boot, and the cockpit's
`onResume()` then re-enters lock task. Keyguard is already disabled, so nothing
stands between power-on and the cockpit.

The Home capability rides a normally-**disabled** `<activity-alias>`
(`KioskHomeActivity`) that `engage()` enables and the exit code disables again —
so a **non-provisioned device is never offered as a launcher**, exactly like
every other kiosk policy here being a no-op without device owner. This is
device-owner-only and cannot be exercised in CI; verify it by rebooting a
provisioned tablet (below).

### Verify

```sh
adb shell dumpsys device_policy | grep -i "device owner"
adb logcat -d | grep "kiosk:"          # "kiosk: locked to org.pureagave..."
adb logcat -d | grep "kiosk: pre-granted"   # lists ACCESS_FINE_LOCATION (+ the two Bluetooth perms on a Samsung)
```

⚠️ Confirm the location permission reads granted (Settings, or
`dumpsys package org.pureagave.zodiac.control | grep ACCESS_FINE_LOCATION`)
with **no dialog ever shown** — the pre-grant path is device-owner-only and
cannot be exercised in CI.

⚠️ **Verify auto-relaunch — the whole point of the Home step.** After
provisioning, `adb reboot` the tablet and confirm the **cockpit comes back on its
own** with no tap:

```sh
adb reboot
# after boot (Samsung delivers Home ~a couple minutes after boot — be patient):
adb shell dumpsys window | grep -i mCurrentFocus   # -> org.pureagave.zodiac.control/.MainActivity
adb logcat -d | grep "kiosk: set as preferred Home"
adb shell cmd package resolve-activity -c android.intent.category.HOME | grep -i zodiac
```

`mCurrentFocus` on the cockpit (not the stock launcher) after a cold boot is the
pass. This is the one check CI can never do; do it on a test tablet before
trusting the fleet to recover unattended.

## Getting back out

There is **no adb un-provision** on the shipped build. `dpm remove-active-admin`
throws `SecurityException: Attempt to remove non-test admin` (it only works on a
`testOnly` APK, which the fleet is not), and a device-owner app **cannot be
uninstalled** (`DELETE_FAILED_DEVICE_POLICY_MANAGER`). So there are exactly two
ways out:

1. **The hidden exit code — primary, no reset.** On the running cockpit, tap the
   two hidden right-edge corners — **bottom-right, then top-right, alternating,
   six taps total** — each within 2 s of the last. The app un-provisions itself
   (`KioskController.exitKiosk` → `clearDeviceOwnerApp`): it leaves lock task,
   **gives Home back to the stock launcher** (clears the persistent preferred Home
   and disables the `KioskHomeActivity` alias), and relinquishes device owner, so
   the tablet is unlocked and can be serviced, updated, or uninstalled. `engage()`
   then no-ops on resume (no longer owner), so it stays unlocked. Confirm:

   ```sh
   adb logcat -d | grep "kiosk:"                              # "kiosk: device owner cleared; ..."
   adb shell dumpsys device_policy | grep -i "device owner"   # now empty
   ```

   The sequence is `KioskExitCode.DEFAULT_CODE` (a constant — change it there if it
   ever leaks). Passengers can't guess it; the trade is that an operator who
   forgets it falls back to (2).

2. **Factory reset — fallback.** If the app is wedged and won't take the code, a
   reset is the only other way; it wipes the tablet and you re-provision from
   step 1 of the previous section.

⚠️ **Verify the exit code on a test tablet before committing any device to the
fleet** — provision one, enter the code, confirm `dumpsys device_policy` shows no
device owner. The device-owner path cannot be exercised in CI, so this on-device
dry-run is the only real check. Provision the fleet only once the build is
settled.

## ⚠️ Fire tablets CANNOT be kiosked — measured 2026-08-18

This is the painful one, established by exhausting every option on a real Fire HD
10 (both a 9th-gen `KFMAWI`/API 28 and an 11th-gen `KFTUWI`/API 30):

**Fire OS ships `com.amazon.parentalcontrols` as a *profile owner* baked into the
OS image.** A profile owner blocks `dpm set-device-owner` outright. And it is
**unremovable** without root:

- Survives a **factory reset** (it's in the system image, re-applied on every boot).
- Survives an **online device deregister** at Manage-Your-Content-and-Devices.
- `dpm remove-active-admin` → `SecurityException: non-test admin`.
- `pm uninstall --user 0 com.amazon.parentalcontrols` → `DELETE_FAILED_INTERNAL_ERROR`.
- Clearing the account packages (`pm clear com.amazon.dcp`/`com.amazon.imp`) leaves
  both the accounts *and* the profile owner in place.

By contrast, **the Samsungs (S9+, A54) have `owners=0`** — no profile owner — so
they provision cleanly. This confirms it is a **Fire OS built-in**, not a user
Kids-mode setup. The only way to remove it is **root**, which on Fire tablets is a
hard, risky project not worth it for this fleet.

**Consequence:** the passenger **Fires cannot be device-owner kiosked**, and the
auto-relaunch-after-reboot feature (which needs device-owner) does **not** apply to
them. Realistic options for the Fires: run **unlocked** (accept the ad lockscreen +
a manual tap after each power-cycle), lighter **screen-pinning** (escapable, no
auto-relaunch), or swap the passenger displays for non-Fire Android tablets.

## Which tablets should get it

- **S9+ hero dashboard** — **yes** (Samsung, provisions cleanly; mounted, worth it).
- **A54 driver phone** — Samsung, *can* be kiosked, but **probably not**: kiosk
  means no maps/phone/camera on the one screen the driver might need something
  else from.
- **Passenger Fires** — **cannot** (see the box above). Run unlocked or swap the
  hardware.
- **XCover beacon** — no. It's headless already; kiosk buys nothing.

## Gotcha

Renaming or deleting `ZodiacDeviceAdminReceiver` breaks provisioning on every
already-provisioned tablet, and the only fix is another factory reset. The class
is intentionally empty so there is never a reason to touch it.

The same caveat applies to the `KioskHomeActivity` `<activity-alias>`:
`KioskController` references it by string (an alias is not a Kotlin class), so a
rename breaks auto-relaunch on already-provisioned tablets silently — the manifest
name and the string in `KioskController` must stay in lockstep.
