# Build, test and deploy

Covers all three parts. The short version:

```bash
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug   # Android, both modules
cd jetson && python3 -m unittest discover -s tests -t .                  # edge box
```

Run the Android line before **every** commit — those are the CI gates plus
Android Lint, and the project's rule is that main stays green.

---

## 1. Toolchain

| | Version | Notes |
|---|---|---|
| Kotlin | 2.0.21 | with the Compose compiler plugin |
| JDK | 17 | source, target and `jvmTarget` |
| Android Gradle Plugin | 8.7.3 | |
| Gradle | 8.10.2 | pinned by the wrapper — always use `./gradlew` |
| Compose BOM | 2024.11.00 | |
| detekt | 1.23.7 | config at `config/detekt/detekt.yml` |
| ktlint plugin | 12.1.2 | Android mode, `ignoreFailures = false` |
| Python | ≥ 3.8 | `zvision` core is standard-library only |

Java and the Android SDK are frequently **not on `PATH`** on a dev machine. If
`./gradlew` cannot find them, set them inline per command:

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk ./gradlew ...
```

`adb` lives at `$ANDROID_HOME/platform-tools/adb`.

### `local.properties`

Gitignored, and optional. `app/build.gradle.kts` reads one key from it:

```properties
BM_API_KEY=<burning man api key>
```

Absent — as on CI — the key compiles to `""`, and the Burning Man discovery
fetch will fail its HTTP call. That is not fatal: `DiscoveryRepository` keeps
serving whatever is in its on-disk cache and logs the failure. Art and camp
markers simply never appear.

---

## 2. Modules

`settings.gradle.kts` includes two Android modules; `jetson/` is not a Gradle
project.

| Module | Package | minSdk | targetSdk / compileSdk | Version |
|---|---|---|---|---|
| `:app` | `org.pureagave.zodiac.control` | **28** (Android 9 / Fire OS 7) | 35 | 1 / `0.1.0` |
| `:beacon` | `org.pureagave.zodiac.beacon` | **29** (the XCover Pro is Android 10) | 35 | 1 / `0.1.0` |

`:app` is `screenOrientation="fullUser"` — each concept reflows for landscape or
portrait, so it runs on a portrait-mounted small tablet too.

minSdk 28 exists so the Fire HD 10 9th gen and Fire HD 8 10th gen can serve as
passenger displays. Nothing in the app needs 30; every API gate in the code is
≥ 31. Older 8" Fires are Fire OS 6/5 (API 25/22) and would need a further drop —
check any candidate with `adb shell getprop ro.build.version.sdk`.

---

## 3. Android — `:app` and `:beacon`

### Commands

```bash
./gradlew assembleDebug              # build both debug APKs
./gradlew testDebugUnitTest          # unit tests, both modules
./gradlew detekt                     # static analysis
./gradlew ktlintCheck                # style check
./gradlew ktlintFormat               # auto-fix style
./gradlew lintDebug                  # Android Lint (manifest / permission / API)
./gradlew assembleRelease            # R8 minify + resource shrink
```

Prefix a task with a module to scope it: `./gradlew :beacon:testDebugUnitTest`.

### Test counts

Measured 2026-08-14, all green:

| Suite | Tests |
|---|---|
| `:app` | **1008** |
| `:beacon` | **109** |
| `jetson` | **475** |

Tests are JUnit 4 with `kotlinx-coroutines-test` (`runTest`, `advanceUntilIdle`)
and a `MainDispatcherRule` `TestWatcher` for dispatcher setup. Four `:beacon`
suites use Robolectric; because `TelemetryBroadcaster` is a process singleton
they all stop it and clear the injected GPS handle in `@After`.

The `:app` suite includes `ThreatProtocolGoldenTest`, which reads
`protocol/threat-protocol-golden.json` from the repository root by walking up
from the Gradle working directory. It fails with the paths it tried rather than a
bare `FileNotFoundException`, and refuses to pass at all if the corpus is missing
or truncated.

### Lint and static analysis

- **ktlint** — Android mode, strict. Fails the build on any violation.
- **detekt** — `config/detekt/detekt.yml`, built on the default config.
  `MagicNumber`, `MaxLineLength` and `LongMethod` are off; `ReturnCount` is
  relaxed to 3 for guard-clause validation; `LongParameterList` and
  `TooManyFunctions` are raised with a written rationale for each. Broad
  `catch (Exception)` at hardware and IO boundaries is `@Suppress`ed locally with
  a reason rather than disabled as a rule.
- **Android Lint** — `abortOnError = true` in **both** modules. Warnings still
  only report; only errors abort.

### Release builds

```bash
./gradlew assembleRelease
```

`:app` release runs R8 shrink plus resource shrinking (~36 MB → ~2.4 MB).
`proguard-rules.pro` keeps the `usb-serial-for-android` driver classes that the
prober resolves reflectively at runtime.

Signing is opt-in. Set `ZODIAC_KEYSTORE_FILE` plus `ZODIAC_KEYSTORE_PASSWORD`,
`ZODIAC_KEY_ALIAS` and `ZODIAC_KEY_PASSWORD` (environment variables, or the
matching `zodiac.*` Gradle properties). Without them the release builds
**unsigned** — R8 still runs, so CI and local builds can verify shrinking with no
keystore.

> **Validate the R8-shrunk APK on a real tablet before fleet distribution.** This
> has not been done.

`:beacon` release does **not** minify and has no ProGuard rules.

---

## 4. `jetson/` — `zvision` and `zdeck`

### Tests

```bash
cd jetson
python3 -m unittest discover -s tests -t .
```

Pure `unittest`, no pytest. The suite runs with **no** `numpy`, `opencv`,
`StreamDeck` or `PIL` installed — `zvision/normalize.py` exists precisely so the
array-free arithmetic is testable without them.

It is not, however, self-contained any more: `tests/test_threat_protocol_golden.py`
resolves `protocol/threat-protocol-golden.json` at the **repository root** and
fails if it is missing. Run the suite from a full checkout, not a copy of
`jetson/` alone.

`tests/test_service_config.py` parses the actual shipped `systemd/zvision.service`
and `scripts/install.sh` and pins their contents — the `EnvironmentFile` not
being `-`-prefixed, `StartLimitIntervalSec=0` living in `[Unit]`,
`Restart=always`, the working directory, and that `install.sh` writes its config
before restarting the service.

### Running without hardware

```bash
cd jetson
python3 -m zvision --source fake -v      # synthetic contacts on the real bus
python3 -m zvision --check --camera thermal:/dev/video0:az=0:fov=160
```

`--source fake` needs nothing installed and is how the bus and the DRIVER HUD are
proved before any camera exists. `--check` validates the whole configuration,
prints the resolved rig and its blind arcs, and exits **without** opening a
camera or touching the network — run it before writing anything into
`/etc/default/zvision`, because the service is `Restart=always` and an
unvalidated typo is a crash loop.

### Dependencies

- `zvision` core: **none.** Standard library only.
- Optional `camera` extra: `numpy>=1.24`, `opencv-python>=4.8`, imported lazily.
- `zdeck`: `streamdeck` + `pillow`, installed into a separate venv on the box.
- DMX output: the apt package `ola` (`olad`); no Python OLA binding is used.

> **On the Jetson, do not `pip install opencv-python`** if a CUDA-enabled build is
> already present — it shadows it. `jetson/DEPLOY.md` records the measured state
> of a fresh JetPack image and what actually needs installing; follow that over
> the comment in `requirements.txt`.

### Deployment

`scripts/install.sh` provisions to `/opt/zodiac/jetson` and enables + starts
`zvision.service`. Note what it does **not** do:

- it copies **only** the `zvision` package — not `zdeck`, not `tests`
- it installs **only** `zvision.service` — not the deck, track or track-serve
  units, and not the Stream Deck udev rule
- the config it writes is `ZVISION_ARGS=--source fake --hz 10`, i.e. **no real
  cameras** until you edit `/etc/default/zvision`

Full procedure — flash, network, prove-with-fake, camera, permanent install, DMX
— in [`../jetson/DEPLOY.md`](../jetson/DEPLOY.md). `zdeck` deployment is
[`../jetson/DECK.md`](../jetson/DECK.md). OLA/DMX bring-up is
`scripts/install-ola.sh`.

---

## 5. CI

Two GitHub Actions workflows, both on push and pull request to `main`.

### `.github/workflows/android-ci.yml`

Ubuntu, JDK 17 (Temurin), Android SDK, the project's Gradle wrapper. Five steps,
in order, **unscoped** so both modules are covered:

1. `./gradlew ktlintCheck`
2. `./gradlew detekt`
3. `./gradlew lintDebug`
4. `./gradlew testDebugUnitTest`
5. `./gradlew assembleDebug`

> These were `:app:`-scoped until 2026-08-10, which meant `:beacon` was built and
> tested by nobody but the author. **Run them unscoped locally too** — a
> `:app:`-prefixed task silently skips the beacon, which is exactly how the gap
> survived.

### `.github/workflows/jetson-ci.yml`

Path-filtered to `jetson/**`, `protocol/**` and the workflow file itself. Python
3.11, no install step (the core is stdlib-only):

1. `python -m unittest discover -s tests -t . -v`
2. `python -m zvision --once -v` — a byte-check that the runner emits a frame

The `protocol/**` filter is deliberate: a change to the ZTHREAT golden corpus
must re-run the Python half of the contract, not just the Kotlin half.

---

## 6. Deploying to a tablet

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n org.pureagave.zodiac.control/.MainActivity
```

Or `./gradlew installDebug`.

Grant runtime permissions up front to skip the first-launch dialogs:

```bash
adb shell pm grant org.pureagave.zodiac.control android.permission.ACCESS_FINE_LOCATION
adb shell pm grant org.pureagave.zodiac.control android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant org.pureagave.zodiac.control android.permission.BLUETOOTH_CONNECT
adb shell pm grant org.pureagave.zodiac.control android.permission.BLUETOOTH_SCAN
```

Screenshot the app framebuffer with `adb exec-out screencap -p > out.png`. After
`am start` the first capture can race the launch transition and catch the home
screen — confirm focus with `adb shell dumpsys window | grep mCurrentFocus`, then
re-capture.

### Retrieving the on-device log

```bash
adb pull /sdcard/Android/data/org.pureagave.zodiac.control/files/logs
```

**This does not work on a Fire tablet.** Fire OS denies shell access to
`/sdcard/Android/data`, so the pull fails with "Permission denied". On a Fire the
hidden bottom-right long-press log viewer is the only way in — which is why it
exists.

### Kiosk provisioning

Per-tablet, after a factory reset. See [`KIOSK.md`](KIOSK.md).

### Device-specific gotchas

Individual devices in the fleet have their own quirks — DeX/desktop windowing on
the S9+, wireless-debugging-only pairing on the A54, OLED brightness discipline
during testing. Those live in [`DEVICES.md`](DEVICES.md).

---

## 7. Repository conventions

- **`SYNC.md` is append-only.** Anything significant that is decided, learned or
  built gets a dated entry, newest on top. Never rewrite a past entry — supersede
  it with a new one.
- **`README.md` is the current-state snapshot**, not a changelog. Update it when a
  major feature ships or the architecture changes.
- **Commit in small runnable increments** and keep `main` green. Phased features
  get phased commits — data, then render, then integrate — each leaving the app
  runnable.
- **Push after each phase commit.** CI runs the same gates upstream, so anything
  green locally should stay green.
- Pause and confirm before any destructive remote operation (force-push, branch
  delete, history rewrite).
