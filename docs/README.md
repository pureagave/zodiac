# `docs/` — index

Reference documentation for the Zodiac vehicle software. Start at the
repository [`README.md`](../README.md) for what the system is.

## Core

| Doc | What it answers |
|---|---|
| [`../README.md`](../README.md) | What is Zodiac, what runs where, what is and is not built |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) | How all three codebases are layered, and where the seams are |
| [`BUILD.md`](BUILD.md) | How to build, test and deploy each part; the exact CI gates |
| [`PROTOCOLS.md`](PROTOCOLS.md) | The wire formats between the parts — NMEA sentences and `ZTHREAT` |
| [`DEVICES.md`](DEVICES.md) | Which device plays which role, and the per-device gotchas |

## Operations

| Doc | What it answers |
|---|---|
| [`KIOSK.md`](KIOSK.md) | Locking a fleet tablet to the cockpit (device-owner provisioning; note: Fires can't be kiosked) |
| [`FAILURE-MODES.md`](FAILURE-MODES.md) | Graceful degradation — what still works when the beacon / Jetson / WiFi / a tablet dies |
| [`MOUNTING.md`](MOUNTING.md) | Vibration-proof tablet mounts — the RAM X-Grip shopping list + fiberglass fastening method |
| [`AUDIT-2026-08-09.md`](AUDIT-2026-08-09.md) | Historical: the 20-finding Android audit and its evidence. All closed |
| [`AUDIT-2026-08-13.md`](AUDIT-2026-08-13.md) | The 8-agent fleet-wide bug-hunt catalogue (~40 findings; P1/P2 fixed) |
| [`AUDIT-2026-08-13-resilience.md`](AUDIT-2026-08-13-resilience.md) | Does the fleet recover by itself after a power event? (power-loss / unplug / reboot auto-recovery) |

## Elsewhere in the repository

| Location | Contents |
|---|---|
| [`../jetson/`](../jetson/) | Edge-box docs — `README`, `DEPLOY`, `HARDWARE`, `MOVING-HEAD`, `DETECTOR`, `TRAINING`, `DECK` |
| [`../protocol/`](../protocol/) | The owner-neutral `ZTHREAT` golden corpus and its rationale |
| [`../design/`](../design/) | UI concept work, burn-in design, performance backlog, surround-HUD design |
| [`../SYNC.md`](../SYNC.md) | Append-only decision log — the project's working memory |
| [`../tasks/open.md`](../tasks/open.md) | Active work items |

## Conventions

- `SYNC.md` records *what happened and why*, append-only, newest first. It is
  never rewritten.
- `README.md` records *what is true now*. It is not a changelog.
- Everything in `docs/` is reference material that should match the code. Where a
  doc and the code disagree, the code wins — fix the doc and say so in `SYNC.md`.
- Where something cannot be verified from the repository (it needs hardware, a
  device, or the owner's knowledge), the doc says so rather than guessing.
