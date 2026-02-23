# Zodiac Control

Android tablet cockpit app for the mutant Zodiac vehicle (Judge Dredd taxi-inspired).

## Current build

- **Concept:** B (CRT Vector)
- **Package:** `ai.openclaw.zodiaccontrol`
- **minSdk:** 30 (Android 11)
- **targetSdk / compileSdk:** 35
- **UI stack:** Jetpack Compose + Material3

## What is implemented in this one-shot

- CRT-style sci-fi dashboard shell
- Left subsystem rail + right status/event rail
- Interactive center wireframe viewport (touch adjusts heading/speed)
- Scanline overlay + perspective grid
- Base Android unit/instrumented test stubs

## Run locally

1. Open project in Android Studio (latest stable).
2. Let Android Studio generate/update Gradle wrapper if prompted.
3. Run on Fire tablet or emulator:
   - Debug build: `app`

## Next sprint recommendations

- Replace placeholder wireframe with traced Zodiac geometry layers
- Add theme skinning and animation system
- Add telemetry domain model + fake feed service abstraction
- Introduce snapshot tests for critical composables
