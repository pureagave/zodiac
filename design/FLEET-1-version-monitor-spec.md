# FLEET-1 — fleet version monitor

**Status:** spec + phased build in progress (2026-08-12). Requested by Rob
2026-08-11 (six devices were 40/38/32/25 commits and 9 days behind, and every
one needed a USB cable to discover). Unblocked by FLEET-2 (self-identifying
builds, `c842d08`).

## Goal

Every node on the vehicle announces what build it is running on the existing
fleet bus. The **S9+ hero display** collects the announcements and flags anything
not on the newest build seen — so a stale tablet is visible at a glance, from the
vehicle, with **no server and no USB cable**. Works fully offline.

## The one rule

**Silence reads as _unknown_, never as healthy.** A device we have never heard
from is absent (we cannot report a build we have not seen). A device we _had_
been hearing from that goes quiet is shown *stale/offline*, never left displaying
its last-known build as though it were current. An unidentifiable build (dirty
tree, `unknown` sha — `BuildIdentity.known == false`) renders *unknown*, never
*current*.

## Wire format — `$ZVER` on a new fleet group

New group, following the established numbering (`.10` GPS, `.20` threats, `.30`
nav): **`239.7.7.40 : 10140`**, added to `FleetBus` (and mirrored in `:beacon`).
Same framing as every other `$Z*` sentence: `$<BODY>*<CC>\r\n`, US-ASCII,
`Locale.US`, two-hex-uppercase XOR checksum over `<BODY>`.

```
$ZVER,<node>,<name>,<base>,<sha>,<dirty>,<epoch>*CC
e.g. $ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*CC
```

| Field | Grammar | Meaning |
|---|---|---|
| `node` | `[A-Z0-9]{1,8}` | stable unique key — last 6 of `Settings.Secure.ANDROID_ID`, uppercased (same derivation as `$ZNAV`'s `src`). The Jetson/beacon use their own stable id. **The table's primary key.** |
| `name` | `[A-Za-z0-9._-]{1,16}` | human label for display: `Build.MODEL` on tablets/beacon (`SM-X810`, `KFTUWI`, …), hostname on the Jetson (`zvision` — lowercase, hence the mixed-case class). Unique per device in this fleet, so the operator reads "SM-X810 = the hero" without any hero/driver derivation the app can't do reliably. |
| `base` | `[0-9A-Za-z.+~-]{1,16}` | `VERSION_BASE` (`0.1.0`) |
| `sha` | `[0-9a-f]{7,40}` \| `unknown` | `GIT_SHA` (9 hex normally); `unknown` when the build couldn't identify itself |
| `dirty` | `0` \| `1` | `GIT_DIRTY` — a dirty build is never "current" |
| `epoch` | `[0-9]{1,10}` | `GIT_COMMIT_EPOCH_SECONDS` — **the newest-wins comparator**. `0` = unknown. |

Any violation → `parse` returns null, never throws (same discipline as
`NavShareProtocol` / `NmeaParser`). Grammar pinned with explicit regexes
(ZTHREAT discipline), not a trusting host-language split.

## "Latest" and per-node status (pure aggregation)

`FleetRoster` is a pure function of *(the set of observations, now)* → a table.
No I/O, fully unit-tested, no clock of its own (now is injected).

- **latest** = the greatest `epoch` among all *known* observations (including
  this device's own). Newest commit wins — no server, no notion of "HEAD",
  just the newest build any peer is actually running.
- Per node status:
  - **CURRENT** — `known`, not dirty, `epoch == latest`.
  - **BEHIND (n)** — `known`, `epoch < latest`. (We can show the epoch gap; we
    cannot count commits without the graph, so "behind" is by build-age, which
    is what actually matters operationally.)
  - **UNKNOWN** — `!known` or dirty or `epoch == 0`: cannot be trusted as current.
  - **OFFLINE** — last heard from > `staleAfter` ago. Its last-known build is
    kept for reference but the row reads offline (the one rule).
- A node never heard from is simply not in the table. There is **no expected
  roster** baked in — the monitor reports what it hears, which is what keeps it
  serverless and offline-first. (A future refinement could add an expected-set so
  a totally-dark device is a visible gap; deliberately out of scope for v1.)

## Cadence and timing

- Emit every **10 s** (`REBROADCAST_MS`). Version changes rarely; the cadence
  only needs to (a) let a freshly-flashed device appear within seconds and (b)
  survive a lost datagram. Sent **twice** (multicast + `/24` subnet broadcast),
  same as the beacon and `$ZNAV`.
- **staleAfter = 35 s** (~3½ missed ticks) before a peer flips to OFFLINE.
- Self is always in the table at full freshness (we know our own build directly,
  not off the wire).

## Who emits, who parses — cross-language, so: golden corpus

- **Emits:** every `:app` tablet, the `:beacon`, and the Jetson (`zvision`). Three
  hand-written builders (`:app` and `:beacon` share no code — decision 10; the
  Jetson is Python). Tablets are consumers-only today and must *learn to emit*.
- **Parses:** the `:app` side (the S9+ aggregates; every tablet can, but only the
  hero renders it).
- Kotlin parses what Python and a second Kotlin module build ⇒ a **cross-language
  contract with three independent implementations** ⇒ it gets a measured **golden
  corpus** at `protocol/version-protocol-golden.json`, read by the `:app`,
  `:beacon` and `jetson` suites, exactly like `ZTHREAT`. The format is far
  simpler than ZTHREAT (strings + ints, no floats), so the drift surface is
  small — but the rule is the rule, and it is cheap here.

## UI — one card on the hero, no new chrome elsewhere

The S9+ shows the roster in a spare card (placement TBD on-device — the passenger
carousel and/or a dedicated hidden-gesture overlay are the candidates; this needs
Rob's eye on the real display, so the card is the **last** phase). Rows sorted
worst-status-first so a problem is at the top. Colours follow the semantic system
(§ConceptTheme): CURRENT = blue (status-ok), BEHIND/OFFLINE/UNKNOWN = red (fault).
Followers (Fires) still run the receiver but need not render the card.

## Phasing (each phase green + runnable, committed separately)

1. **Protocol** — `core/telemetry/FleetVersion*` (message type + build + parse) +
   tests. Pure, wired to nothing. — **DONE 2026-08-12 (`1c74cc5`).**
2. **Aggregator** — `core/telemetry/FleetRoster` (pure table math) + tests. —
   **DONE 2026-08-12 (`def9eb6`).**
3. **Plumbing** —
   - **3a** `core/telemetry/FleetPeerTable` — the pure receive-side fold (parse →
     upsert by node), kept off the socket loop + tests. — **DONE (`517da46`).**
   - **3b** `data/fleet/FleetVersionReceiver` + `FleetVersionSender` — the socket
     loops (mirror `NavShareReceiver`/`NavShareSender`; the receiver just calls
     `FleetPeerTable.ingest`, the sender broadcasts one fixed sentence every
     10 s). Thin glue, verified on hardware. — **TODO.**
4. **App integration** — a self-contained `FleetVersionMonitor` in
   `ZodiacApplication`: combine `FleetPeerTable` peers + self + a periodic tick
   through `FleetRoster.compute` into a `StateFlow<List<FleetRosterEntry>>`. Kept
   **off** `CockpitUiState` (it is a slow side-channel, not per-frame cockpit
   state — the A5 hot-path stays lean). Self identity = `FleetVersion(navSrcId,
   Build.MODEL, BuildIdentity.parse(BuildConfig.VERSION_NAME,
   GIT_COMMIT_EPOCH_SECONDS))`. A log line makes it observable without the card;
   verify emit on hardware with a `zver_listen.py` sniffer. — **TODO.**
5. **UI card** on the hero (needs a device for placement/legibility — Rob's eye).
6. **Beacon + Jetson emit** + the golden corpus (`protocol/version-protocol-golden.json`).

**Status:** the pure, tested core (phases 1, 2, 3a) is done and mutation-checked —
everything with real logic. Remaining: the socket glue (3b), the DI wiring (4),
the card (5, needs the S9+), and cross-language emit + corpus (6). This doc is the
contract; the code wins if they disagree (fix the doc, note it in `SYNC.md`).
