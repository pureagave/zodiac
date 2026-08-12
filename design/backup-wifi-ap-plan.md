# Backup WiFi AP on the Jetson — implementation plan

**Status (2026-08-12):** Design plan, not yet built. Produced by a high-thinking
planning pass over `nav`... the requirements were: keep the fleet's UDP-multicast
bus alive if the car's primary WiFi AP dies, by having the Jetson stand up a
*backup* AP on demand. **Since the plan below was written we have decided two
things and validated one:**

- **Band: 2.4 GHz, not 5 GHz** (Rob may add older 2.4-only tablets; our traffic is
  tiny; 2.4 GHz has better range through a metal car; and it sidesteps §0.1's
  biggest risk entirely). So §0.1 / the 5 GHz NO-IR concern is moot — build for
  **2.4 GHz** (channel 11 to stay off the primary's channel 1).
- **Phase 0 PASSED (2026-08-12).** The Jetson's WiFi is a **Realtek RTL8822CE**
  (driver `rtl88x2ce`) — not Intel, so no LAR/AP-mode block. A 2.4 GHz soft-AP
  came up cleanly via NetworkManager (`ipv4.method shared`, its own dnsmasq bound
  to the interface), no system-dnsmasq conflict, **Ethernet/SSH undisturbed**, and
  it tore down clean. Reg domain is `00` (world) — set it to `US` for correct
  2.4 GHz power. The `iw list` interface-combination check (AP+managed for
  probe-scan) still owes a look, but the design's recovery uses probe-down teardown
  which doesn't need concurrent scan.

Companion: the requirements doc that drove this was in the session scratchpad
(gone on reset); its essence is captured in `SYNC.md` (2026-08-12) and
`[[zodiac-resume-point]]`. **Read that plan status before building.**

---

## 0. Top open questions / decisions (resolve first)

1. **~~Can the Jetson card start a 5 GHz AP?~~ RESOLVED — going 2.4 GHz; Phase 0
   passed on the RTL8822CE (see status header).**
2. **Range**: internal/enclosure antennas on a metal art car are the big unknown.
   Phase 5's on-vehicle test decides whether we ship as-is, move the U.FL pigtail
   antennas outside the enclosure, or pivot to the travel-router contingency.
3. **Buy the $25 travel router** — not as the auto-backup, but as a powered-off
   **cold-spare clone** of the primary (same SSID/PSK/subnet/DHCP reservations, in
   a labeled box). See §7. Decision: approve $25 + 30 min of config.
4. **Accept bounded transient partitions**: the design guarantees partitions cannot
   *persist* (R6), but during the recovery transition the Fires can be blind for up
   to ~3 min. Eliminating even that needs the optional `zrelay` (Phase 6). Rob
   already accepted a 3–5 min window.
5. **Confirm the primary gateway IP + SSID** to bake into `/etc/default/zbackup`
   (subnet is 192.168.86.0/24; gateway presumably 192.168.86.1 — read
   `ip route show default` on the box; the car AP is being set up as SSID `zodiac`,
   gateway 192.168.86.1).

---

## 1. Trigger model: on-demand (Rob's preference), with two amendments — and the split-brain analysis that decides it

### Why always-on-lower-priority loses

The "simpler" always-broadcast-a-lower-priority-SSID model is built on a mechanism
that does not exist: **Android has ignored saved-network `priority` since Oreo.**
Selection is: internet-validated networks first, then RSSI. On the playa:

- Primary healthy **and Starlink up** → primary is validated, backup isn't →
  devices prefer primary. Fine.
- **Starlink drops (routine at BRC) → both networks unvalidated → the scorer
  degenerates to per-device RSSI.** A tablet at the rear of a metal car with weak
  RSSI to the primary and strong RSSI to the Jetson roams to the backup **while the
  primary is perfectly healthy**. The beacon, mounted elsewhere, stays on the
  primary → beacon on one net, a Fire on the other, **no position on the Fire,
  silently, indefinitely**. Always-on turns split-brain into a *standing daily
  lottery*, re-rolled every time Starlink blinks.
- Always-on has **no forcing function** — nothing converges the fleet.
- Heterogeneity (Samsung/Fire/API 29) makes joint state unreasonable.

### Why on-demand wins — reframed around split-brain

On-demand's real virtue is that **the Jetson controls when two APs can coexist and
owns a deterministic convergence action:**

- Steady state: backup SSID isn't on the air → devices *cannot* choose wrong.
  Split-brain is structurally impossible.
- Failure transition: activation is gated on the primary SSID being **gone from the
  air** → at the moment the backup rises there is still only one live AP.
- Recovery transition: the only two-AP window — and it ends with the Jetson
  **tearing down its AP, which deauths every backup client and forces the whole
  fleet to rescan and converge on the sole remaining AP**. Convergence by
  construction, not by hoping five OS builds roam alike.
- Failure-mode asymmetry (the deciding factor): on-demand's worst failures (daemon
  crash, detection wedge) degrade to **"no backup"** — the status quo. Always-on's
  worst failure is **silent persistent partition** — the single worst outcome.

### Two amendments to the stated preference

Rob's instinct (detect via gateway reachability / DHCP / association) picks the
wrong primary signal. Amend:

1. **Primary activation signal = "primary SSID absent from the air"** (WiFi scan
   from the Jetson's idle wlan), corroborated by **"fleet bus silent"** (no UDP on
   10110, observed over wired Ethernet). The scan gate makes activation safe by
   construction (SSID gone ⇒ clients already orphaned ⇒ a second AP can't split
   anything). The wired bus-silence gate is immune to Jetson RF blindness: if the
   fleet is happily on the primary, beacon frames arrive over the wired bridge at
   4+ Hz, and activation is suppressed. Gateway ping is demoted to a *recovery*
   signal + one input of the compound zombie trigger.
2. **Recovery uses a "probe-down" forcing function**, not client-roam trust:
   confirm primary health, tear the AP down, immediately scan with the now-free
   interface to confirm the primary SSID is really on the air, and re-raise within
   seconds if it isn't. Plus an anti-flap latch (§3).

**Recommendation: on-demand.** Cost = one ~300-line, heavily unit-testable daemon.
Buys the only property that matters: partitions cannot persist.

---

## 2. Architecture

### 2.1 Jetson side

**One new component, `zbackup`** (Python 3, stdlib only, at `jetson/zbackup/`
mirroring the zvision layout), plus one NetworkManager connection profile, plus one
systemd unit. No bridges, no hostapd, no custom iptables.

**NM AP profile** (created once by the installer, idempotent — the crucial property
is `autoconnect no`, so NM can never raise the AP on its own). **2.4 GHz variant**
(per the band decision):

```
nmcli con add type wifi ifname wlP1p1s0 con-name zodiac-backup autoconnect no \
  ssid ZODIAC-BK \
  802-11-wireless.mode ap 802-11-wireless.band bg 802-11-wireless.channel 11 \
  802-11-wireless.ap-isolation 0 \
  802-11-wireless-security.key-mgmt wpa-psk 802-11-wireless-security.proto rsn \
  802-11-wireless-security.pairwise ccmp 802-11-wireless-security.group ccmp \
  802-11-wireless-security.pmf 1 \
  802-11-wireless-security.psk '<PSK>' \
  ipv4.method shared ipv4.addresses 10.77.0.1/24 ipv4.never-default yes \
  ipv6.method disabled
```

Design choices, each justified:

- **NM + `ipv4.method shared`, not hostapd.** NM uses wpa_supplicant's AP mode and
  spawns its own dnsmasq for DHCP+DNS on the interface — both already installed,
  zero new packages, survives NM restarts. hostapd is the *fallback* if Phase 0/5
  reveals group-frame delivery problems (it exposes `multicast_to_unicast=1` and
  `dtim_period=1`, which NM does not) — justified only by measurement.
- **Channel 11 (2.4 GHz)**, off the primary's channel 1. Pin it; don't let NM
  auto-select.
- **WPA2-PSK/CCMP, PMF disabled** (`pmf 1` in NM's enum = disable): the Fire HD 10
  9th gen (2019) is WPA2-only; the XCover is API 29. WPA3/SAE or required-PMF would
  silently exclude exactly the devices the backup exists for.
- **Subnet 10.77.0.0/24**: visually unmistakable from 192.168.86.0/24 in any log.
  No DHCP reservations needed — the fleet bus is IP-agnostic (fixed multicast
  groups + /24-derived broadcast), which is why this whole design needs almost no
  code.
- **`ipv4.never-default yes` + `ipv6.method disabled`**: the AP can never perturb
  the Jetson's Ethernet default route, SSH, `olad`, `zdeck`, or zvision egress
  (R1/R8).
- **R9 (internet) comes free**: `shared` mode NATs AP clients to the Jetson's
  default route. If the failure was router-radio-death but Ethernet+Starlink live,
  clients get internet through the Jetson with zero extra config; if the router is
  fully dead, NAT goes nowhere (the accepted no-internet mode). No added risk —
  stock NM path.
- **Pre-flight check (installer)**: if a system-wide `dnsmasq.service` is enabled
  and binds 0.0.0.0:53, NM's spawned instance can fail to bind on 10.77.0.1 —
  producing the worst half-alive state (AP up, no DHCP). Installer must check
  `systemctl is-enabled dnsmasq` and disable it or set
  `except-interface=wlP1p1s0`. *(Phase 0 confirmed system dnsmasq is disabled/
  inactive on this box — good.)*

**`zbackup.service`** (mirrors `zvision.service` conventions):

```
[Unit]
Description=Zodiac Backup AP - on-demand fleet WiFi failover
After=NetworkManager.service network-online.target
Wants=network-online.target
StartLimitIntervalSec=0
[Service]
Type=simple
EnvironmentFile=/etc/default/zbackup        # REQUIRED, no leading '-' (zvision precedent)
ExecStart=/usr/bin/python3 -u -m zbackup
Restart=always
RestartSec=5
RuntimeDirectory=zodiac-zbackup             # /run/zodiac-zbackup, cleared on boot (R8)
[Install]
WantedBy=multi-user.target
```

- **No ordering relationship with zvision in either direction** (the
  `zodiac-deck.service` precedent). zbackup crashing/wedging/uninstalled cannot
  touch zvision (R8).
- `/etc/default/zbackup` carries `PRIMARY_SSID`, `PRIMARY_GW`,
  `BACKUP_CON=zodiac-backup`, and the timing knobs. Required-not-optional (loud
  failed start beats silent wrong behavior).
- **Crash/restart semantics (R8)**: if zbackup dies while ACTIVE, the AP *stays up*
  (NM owns the connection, not the daemon) — the fleet keeps working; systemd
  restarts the daemon, which **adopts** the observed NM state
  (`nmcli -t con show --active`) rather than resetting, so a restart never bounces
  the fleet. Fresh boot: `/run` empty + `autoconnect no` ⇒ AP down; daemon starts
  DORMANT and re-evaluates.
- The daemon's entire action surface: `nmcli con up/down zodiac-backup`,
  `nmcli dev wifi rescan/list ifname wlP1p1s0`, `ping -c1 -W1 <gw>`, and one passive
  UDP listen socket. Never touches `enP8p1s0`, never edits another connection,
  every subprocess call has a hard timeout so a hung `nmcli` degrades to a stale
  poll, not a wedged daemon.
- CPU/RF impact on zvision: a soft-AP serving ≤7 clients at ~1 kB/s is noise next to
  10 Hz background subtraction; the WiFi card is PCIe (no USB contention with the
  cameras). Verify in Phase 0 anyway.

**zvision egress when the AP is up — the one code change.** Today
`ThreatBroadcaster` resolves targets once at construction: multicast pinned to
`--iface-ip` (Ethernet) + the Ethernet /24 broadcast. If the router is powered off,
the Ethernet link drops and both sends fail — zvision runs but is mute. The fix
exploits two existing facts: the constructor already accepts `extra_targets`
(unwired), and per-target error isolation already swallows dead paths. So:

- Plumb a repeatable `--extra-target <ip>` argparse flag in `jetson/zvision/app.py`
  through to `ThreatBroadcaster(extra_targets=...)`, with the same `inet_aton`
  validation and config-echo as the other address flags (~15 lines + a unit test).
- Set `/etc/default/zvision` to include `--extra-target 10.77.0.255`. Because the AP
  subnet is **static**, this is a constant: AP down ⇒ the route doesn't exist and
  the send fails silently (already-caught `OSError`); AP up ⇒ ZTHREAT flows to every
  AP client as subnet broadcast. **No restarts, no dynamic reconfiguration, no
  runtime coupling between zvision and zbackup.** Do **not** use `--bind-ip` on the
  vehicle (a socket bound to the Ethernet IP breaks when that address is removed on
  link-down; `--iface-ip` pins only multicast egress and is safe).
- Consequence: **the threat feed becomes partition-immune** — zvision transmits onto
  both networks whenever both exist, so ZTHREAT never participates in split-brain.
  Only beacon telemetry can partition, and only transiently.

**Jetson-side consumers keep working with zero changes**: `audio_bus.py` and the
track logger bind wildcard `("", 10110)` with `SO_REUSEADDR`, so the beacon's
10.77.0.255 broadcast reaches them regardless of interface. zbackup's own bus
monitor also binds :10110 with `SO_REUSEADDR`/`SO_REUSEPORT`; broadcast/multicast
datagrams are delivered to all such sockets, so the daemons coexist.

### 2.2 Device side (zero app/beacon code)

- **Provision every device once** (all 5 tablets/phones): join `ZODIAC-BK` while
  it's up on the bench, and when Android asks "this network has no internet — stay
  connected?" answer **"Yes / always"** (Samsung + Fire OS persist this per-network;
  a network *connected to* auto-joins far more reliably than one merely "saved").
  Verify per-network **auto-reconnect** is on. MAC randomization on the backup can
  stay per-network persistent-random — the backup subnet uses no reservations.
- **Airplane-mode roam (beacon + A54): works, with evidence + one residual test.**
  Android keeps WiFi fully operational under airplane mode incl. scanning + auto-
  join; already hardware-verified (SYNC 2026-08-12: airplane-mode XCover
  auto-reconnected across a reboot, zero interaction). Not yet covered: roaming
  **between two saved SSIDs** — Phase 4 bench drill closes it. All fleet devices are
  on vehicle power and charging devices don't Doze, so disconnected-scan intervals
  stay short (join within ~10–60 s of the backup appearing).
- **Why zero app code is genuinely sufficient** (verified in source): the beacon
  re-resolves its broadcast targets every 5 s from the current DHCP lease
  (`BeaconNet.broadcastTargets` / `TARGET_REFRESH_MS = 5_000`), so within 5 s of
  joining the backup it targets 10.77.0.255 + the group. `NetworkLocationSource`
  rebuilds its socket + rejoins the group after 20 s of silence
  (`REJOIN_SILENT_MS`). `NetworkThreatSource` never rebuilds its socket, but its
  wildcard bind receives the **broadcast** leg without group membership, so threats
  survive the move too. Both hold `MulticastLock`s.

---

## 3. Detection + activation state machine (concrete timings)

A pure, clock-injected `BackupPolicy` class (the app's `LocationFailoverPolicy`
pattern, scaled up: the app drops the beacon after 3 s and re-proves for 10 s; the
network layer acts on ~30–60 s and demands 120 s+ of proof).

**Inputs (polled every 2 s):**
- `ssid_seen`: primary SSID in the last completed scan
  (`nmcli dev wifi list ifname wlP1p1s0 --rescan yes`, every 10 s while DORMANT; a
  scan that *fails to complete* = "unknown", never "absent").
- `bus_rx_age`: seconds since any UDP on :10110 (passive wildcard socket; also
  records **source IP** — distinguishes "beacon on 192.168.86.x" from "on
  10.77.0.x"; logged always, used as a recovery accelerator).
- `gw_ok`: `ping -c1 -W1 $PRIMARY_GW` once per 2 s.
- Ethernet carrier (`/sys/class/net/enP8p1s0/carrier`): **logged only, never a
  trigger** — an unplugged Jetson cable would otherwise mimic a dead fleet and
  false-activate.

**States: `DORMANT → ACTIVE → (PROBING) → DORMANT`, plus `LATCHED`.**

**DORMANT → ACTIVE — either:**
- **A (radio death, the safe trigger):** primary SSID absent from **3 consecutive
  completed scans (~30 s)** AND `bus_rx_age > 15 s`. SSID-gone ⇒ clients provably
  orphaned (one live AP at activation ⇒ no split possible); the wired-bus
  corroboration vetoes scan false-negatives.
- **B (zombie AP — beaconing but dead):** `gw_ok` false for **60 s continuous** AND
  `bus_rx_age > 60 s`. Both, never either (gateway-only = LAN glitch; bus-only = the
  beacon phone died — an AP fixes nothing and creates a two-AP window). Longer dwell
  because B *does* create two SSIDs; a device stuck on a zombie primary may need a
  human router power-cycle — equally true with no backup.

On activation: `nmcli con up zodiac-backup`, log reason + signal values, write
`/run/zodiac-zbackup/state.json`.

**ACTIVE → PROBING (recovery detection):**
- Minimum ACTIVE dwell: **5 min** (never probe sooner).
- If activation was B, or A-with-gateway-down: probe when `gw_ok` continuously true
  for **120 s**.
- If activation was A with gateway up throughout (router radio died, Ethernet alive
  → gateway useless as recovery signal): slow timer — first at **+10 min**, then
  30 min, then every 60 min.
- Accelerator: if beacon frames are seen sourced from **192.168.86.x** while ACTIVE
  (beacon roamed home ⇒ primary is serving WiFi), probe immediately after min-dwell.

**PROBING (the forcing function):** `nmcli con down zodiac-backup` (deauths all
clients) → scan up to **20 s** → primary SSID seen ⇒ **DORMANT** (orphaned clients
converge on the only AP). Not seen ⇒ AP back up within seconds (total fleet gap
~15–25 s, inside `NetworkLocationSource`'s 20 s rejoin window), mark a failed probe,
back off. Scan-while-AP (a second managed vif) is attempted first if `iw list`
combos allow it, but iwlwifi's same-channel constraint (and here the Realtek's) make
probe-down the design of record.

**Anti-flap latch:** re-activation within 10 min of a deactivation raises the next
recovery bar to 10 min of continuous gateway health. **≥3 activations in one hour ⇒
LATCHED**: hold ACTIVE for 60 min regardless of primary state, log at CRIT. A
flapping primary is strictly worse than a stable backup.

**Split-brain prevention through both transitions (R6, the crux):**
- *Failure:* A activates only when the primary is off the air — one live AP at every
  instant; devices can only converge. B's two-AP window is bounded and biased safe
  (devices don't leave an associated network for an unvalidated one).
- *Recovery:* two-AP window from primary return to probe-down completion — worst
  ~7 min (5 min dwell + 120 s confirm), usually less. During it, devices that drift
  home early lose beacon telemetry only until teardown: **Samsungs feel nothing**
  (`FailoverLocationSource` carries them on own-GPS within 8 s), **threats never
  partition** (zvision transmits on both nets), and the **Fires' position gap is
  bounded at ~3 min worst case**. Teardown deauths every remaining backup client →
  provable convergence. No state persists past one probe cycle.
- *Degenerate cases:* beacon dead + primary healthy → no activation (A vetoed by
  scan, B by gateway). Beacon dead + router dead → activate anyway (threats +
  Samsung GPS + inter-device coordination still ride the backup). Jetson Ethernet
  unplugged → no trigger (carrier is log-only; A requires SSID absence).

**Fleet-restoration budget after total router death:** detect ~30–45 s → AP up ~5 s
→ devices scan/join/DHCP ~10–60 s → beacon re-targets ≤5 s → tablets rebuild sockets
≤20 s. **Fires have position again ~1–2.5 min after the router dies**, unattended, at
4 am. Status quo = dead until a human notices.

---

## 4. Multicast over the soft-AP (R5)

- **Client↔client forwarding:** mac80211 AP interfaces do intra-BSS forwarding
  in-kernel by default; the profile pins `ap-isolation 0` explicitly. A
  group-addressed frame from the beacon (sent unicast to the AP per 802.11 ToDS) is
  re-broadcast to all stations. **No IGMP machinery on a soft-AP** — no snooping
  switch in the path; group frames go to everyone. Simpler than the primary router
  path (which needed the broadcast leg to cross the IGMP-snooping wired↔wireless
  bridge).
- **Subnet broadcast:** the beacon derives x.x.x.255 → 10.77.0.255 within 5 s;
  broadcast takes the same intra-BSS path. Both belt-and-suspenders legs arrive; the
  tablets' `$ZSHK` dedup handles the duplication.
- **Known soft-spot + levers:** group-addressed 802.11 frames are unacked, sent at a
  low basic rate, buffered until DTIM for power-save clients (wpa_supplicant default
  `dtim_period=2` ≈ ≤200 ms — irrelevant at 4–15 Hz). If Phase 5 shows group-frame
  loss at marginal RSSI, escalate to hostapd with `multicast_to_unicast=1` +
  `dtim_period=1` — a contained AP-backend swap, nothing else changes. Don't pay
  that before measurement.
- **Android side:** both consumers hold `MulticastLock`s (verified), so the driver's
  multicast filter is disabled.
- **zvision egress:** unchanged multicast+broadcast on Ethernet, plus the constant
  `--extra-target 10.77.0.255` onto the AP subnet (§2.1). Receivers bind wildcard, so
  the broadcast leg alone suffices on the AP subnet even before any multicast join.
- **Jetson ingress:** the beacon's broadcast copy reaches every wildcard-bound :10110
  socket regardless of arrival interface.

## 5. Unavoidable code changes — the complete list

1. **zvision:** plumb `--extra-target` (repeatable) → existing
   `ThreatBroadcaster(extra_targets=...)`, with validation + config echo + unit test.
   ~15 lines in `jetson/zvision/app.py`. No wire-format change; the golden corpus is
   untouched.
2. **New:** `zbackup` daemon (~300 lines incl. the pure `BackupPolicy` class +
   tests), `zbackup.service`, `/etc/default/zbackup`, installer additions (profile
   creation, dnsmasq conflict check).
3. **App: zero. Beacon: zero.** (Verified against source, §2.2.)
4. Config-only: `/etc/default/zvision` gains the extra target; device provisioning
   (§2.2).

## 6. Test / validation plan

*Phase 0 (bench, gating) — DONE 2026-08-12, PASSED on 2.4 GHz.* Remaining Phase-0
nicety: `iw list` interface combos (scan-while-AP?) and a **zvision perf guard** —
run `--source thermal --hz 10` with the AP up, 5 associated clients + iperf3 load,
assert broadcast Hz + detector latency unchanged (tegrastats logged).

*Bench functional:* beacon + S9+ + one Fire on the backup AP → position on the Fire,
ZTHREAT on all, `$ZAUD` reaching the Jetson; tcpdump on `wlP1p1s0` confirming both
multicast and 10.77.0.255 legs; NM's dnsmasq leasing correctly.

*On-vehicle range/coverage (the big unknown):* Jetson in final enclosure/position;
at every tablet mount + the beacon mount, measure RSSI
(`iw dev wlP1p1s0 station dump`) and delivery (NMEA sentence rate at the tablet vs the
beacon's `sent` counter over 5 min). **Pass: ≥95% delivery and ≥ −70 dBm at the worst
mount, engine running, humans aboard.** Fail → antenna repositioning → travel-router
contingency.

*Failover drill:* kill router power. Stopwatch: activation ≤60 s of SSID loss; every
device joined ≤3 min; Fires showing live position; ZTHREAT live; correct reason
logged.

*Recovery drill:* restore router power. Assert: no deactivation before min-dwell +
120 s stable gateway; probe-down deauths; all devices back on primary ≤2 min after
teardown; **AP stays down**; measure the Fires' actual position gap (budget ≤3 min).

*Anti-flap drill:* cycle router power every 3 min ×3 → escalating recovery bars, then
LATCHED + CRIT log; fleet stable on backup throughout.

*Split-brain drills:* (1) SSID-rename on the router (radio-death-with-live-Ethernet) →
condition A fires with gateway up; restore → slow-cadence probe recovers. (2) Power
off the **beacon** with the router healthy → assert **no activation** (the
false-positive test). (3) During recovery, verify per-device network assignment,
Samsung own-GPS engagement (logcat `NET -> SYSTEM failover`), and that no device
remains on the backup 5 min after teardown. (4) Jetson reboot while fleet on backup →
AP returns, fleet re-converges, total gap recorded.

*R8 drill:* `kill -9` zbackup while ACTIVE → AP persists, fleet unaffected, daemon
adopts on restart. `SIGSTOP` wedge → zvision/deck/olad untouched.

## 7. Risks and what we're missing

- **Jetson antennas in a metal car** — §0.2. Range test decides; U.FL pigtails are
  repositionable.
- **Shared power failure domain**: the Jetson and router likely share the same 12 V
  rail. An electrical fault kills primary *and* backup together (the phones have
  batteries; the infrastructure doesn't). **Separate fusing for router vs Jetson** is
  a wiring task worth an hour — and the strongest argument for the battery-capable
  travel-router spare. *(This is the "power session" Rob flagged as the top next
  step.)*
- **The $25 travel router — buy it, but as a cold-spare clone, not the auto-backup.**
  As an *always-on* second AP it inherits every split-brain problem in §1. As a
  **powered-off clone of the primary** (same SSID/PSK/subnet/reservations) it is the
  *repair* path the Jetson can't be: if the primary router is truly fried, a human
  swap restores full service (internet NAT, reservations, real antennas) for the
  event, with zero roam behavior to reason about (same SSID; never powered
  concurrently with the primary). Jetson = automatic instant unattended bridge;
  spare router = permanent fix. They complement.
- **Zombie-AP residual**: an AP that beacons but won't pass traffic can hold devices
  hostage; condition B raises the backup, but some devices may stick to the zombie
  until its power is pulled. True today without any backup; document "power-cycle the
  router" as the ops action.
- **Hardcoded 192.168.86.235 workflows** (SSH runbooks, `trackserve` fetches) don't
  work in backup mode — the Jetson is `10.77.0.1` there. One line in the ops doc.
- **"No internet" OS drift**: a device parked for days on an unvalidated network is a
  low-but-nonzero disconnect risk, and the unattended beacon can't answer prompts.
  Provisioning answers the prompt once; the optional Phase 6 "204 shim" (dnsmasq DNS
  override + an HTTP 204 responder on the Jetson answering Android's connectivity
  check) removes the risk entirely — safe *only because* the trigger is on-demand.
- **Observability (R7)**: journald with structured reasons + `/run/zodiac-zbackup/
  state.json`, and (optional) a **zdeck tile** reading that file — the Stream Deck is
  the already-accepted head-down ops surface, so it respects the windshield-is-primary
  principle. **No new driver-facing UI, no protocol change**: a converged fleet on the
  backup is functionally identical for the driver; the app already surfaces what
  matters (beacon-health footer, `NO VISION` rim, `usingFallback` logging). Consistent
  with the rejected-badge precedent.
- Minor: expect ~200 ms group-frame jitter from DTIM buffering (harmless); backup PSK
  hygiene = same as primary (anyone on either SSID can spoof the bus — pre-existing,
  proportionate risk).

## 8. Phased execution (each phase leaves the system working; parallelizable)

| Phase | Deliverable | Depends | Working-state guarantee |
|---|---|---|---|
| **0. Hardware truth (gate)** | ~~Card/NO-IR/AP-start~~ **DONE, 2.4 GHz** + remaining: interface-combos + zvision-perf-under-AP measurement | — | Test profiles deleted; nothing installed |
| **1. Static AP + bus proof** | `zodiac-backup` NM profile (autoconnect no) + manual bring-up; beacon + 2 tablets verified end-to-end on the AP subnet | 0 | Profile down by default; primary untouched |
| **2. zvision `--extra-target`** | CLI plumbing + validation + unit test + `/etc/default/zvision` update | — (∥ with 1) | Send to a nonexistent subnet fails silently per existing per-target isolation; normal-mode byte-identical |
| **3. zbackup daemon** | `BackupPolicy` (pure, clock-injected, unit-tested) + daemon + unit + installer additions | 1 | Ships disabled until Phase 5 signs off; worst crash = "no backup" |
| **4. Device provisioning** | All devices joined once to `ZODIAC-BK`, no-internet prompt answered, auto-reconnect verified; **airplane-mode two-SSID roam bench drill** (beacon + A54); A54 radio config completed | 1 | Devices merely gain a saved network |
| **5. Vehicle validation** | Range/coverage, failover, recovery, anti-flap, split-brain, reboot, R8 drills per §6; enable `zbackup.service` on pass | 2,3,4 | Every drill ends with the fleet on the primary |
| **6. Optional hardening** | 204 connectivity shim; zdeck status tile; `zrelay` cross-network beacon re-broadcast (closes the last ~3-min Fire gap); hostapd swap if group-frame loss measured | 5 | Each independent + individually revertible |

Phases 1+2 and 3+4 run as parallel pairs; the critical path is 0 → 1 → 3 → 5,
comfortably inside three weeks with the range test scheduled early in week 2 so the
travel-router/antenna contingency still has runway.

### Critical files
- `jetson/zvision/app.py` — plumb the existing `extra_targets` as a repeatable `--extra-target` flag (validation ~line 244, `ThreatBroadcaster(...)` ~line 311)
- `jetson/zvision/broadcaster.py` — `ThreatBroadcaster.extra_targets` + per-target error isolation this leans on
- `jetson/systemd/zvision.service` — unit-file conventions the new `zbackup.service` mirrors
- `jetson/scripts/install.sh` — extend to create the NM profile, install the unit + `/etc/default/zbackup`, run the dnsmasq pre-flight
- `beacon/src/main/java/org/pureagave/zodiac/beacon/BeaconNet.kt` — the 5 s target re-resolution that makes zero beacon changes possible (read-only reference; must not regress)
