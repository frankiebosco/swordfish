# First Live Test — Runbook

**Written 2026-08-20, the evening the OBDLink MX+ arrived.**

Three untested things go live at roughly the same time: the phone talking to
Android Auto, the app running on the real head unit, and the MX+ speaking to
the car. This document exists because turning all three on at once produces a
failure that tells you nothing about which one broke.

**The rule: one link at a time, in an order where each step eliminates a
suspect.**

---

## Order of operations

### Stage 0 — the native app, no Swordfish involved

Pair the MX+ in Android Settings, then run the **OBDLink app** and let it do
any firmware update it offers. This confirms the dongle is functional before
any of our code is in the picture.

While there, grab two things:

- **Idle MAF, RPM and lambda at warm idle.** The project pins idle burn at
  ~0.20 gal/h from MAF 2.31 g/s at 784 rpm — the project's only real-world
  calibration point. A second tool agreeing is a real confirmation; a second
  tool disagreeing is a finding.
- **The Mazda enhanced diagnostics section**, if the app offers one. That is
  the supported path to MS-CAN and settles the open question in
  `MX_PLUS_PROBE_PLAN.md` without any protocol fiddling. Documented coverage
  stops at the 2017-2018 ND, so absence is a plausible outcome rather than a
  fault.

**Then close the OBDLink app — properly, force-stop it.** Two apps cannot
share one SPP socket. Whichever connects second gets a failure that looks
exactly like a broken dongle, and this is the single most likely way to waste
an hour tonight.

### Stage 1 — Swordfish probe, phone only, Android Auto UNPLUGGED

Swordfish → **HARDWARE → OBD PROBE**. Ignition on, engine running or not.

This is phone-side by design. The head unit is one of the things under test,
and debugging the dongle over that same link confounds the two. If the probe
passes here, the dongle is eliminated as a suspect for whatever the head unit
does next.

The probe runs about 20 seconds and does five things in order, each gated on
the previous one succeeding:

1. **SPP socket** — secure first, insecure as fallback
2. **ELM handshake** — `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`, each
   reported separately so a failure names the step
3. **Vehicle contact** — one RPM read; `UNABLE TO CONNECT` here means the
   adapter is fine and the ignition is not
4. **PID sweep** — all four support queries, diffed against the Ancel survey
5. **Rate test** — 10 seconds of hammering `010C`, then one read of every
   scheduled PID

### Stage 2 — Android Auto, in the car, no OBD

Plug in. Confirm Swordfish appears and draws its panel on the real head unit.
It will show **DEMO** in the top centre, which is correct and expected — the
panel is running sample data.

**Result 2026-08-20: the panel rendered correctly on the real head unit**, full
layout, correctly proportioned, split-view alongside Spotify. The Car App
Library surface works on real hardware, not just the DHU.

Two follow-on findings from that session:

- An app icon **did** appear in a later attempt, so the trusted-source question
  is not the simple no it first looked like.
- Android Auto's own projection process crashed while binding our service
  (`No matching component for intent`), which tore the surface down and — before
  the fix — took Swordfish with it. `GaugeRenderer` now survives the host
  crashing.

### Stage 3 — both at once

Only now. If stages 1 and 2 both passed and this one fails, the failure is
specifically about coexistence, which is a real finding rather than a mystery.
`CONNECTIVITY.md` argues at length that there is no architectural conflict on
a wired-Android-Auto car; this is where that argument meets the road.

---

## The number that matters

**Achieved commands per second.** Everything in the poll design follows from
it, and it has never been measured on this adapter, in this car, with Android
Auto running.

`PollSchedule` demands **33.3 cmd/s** against a *quoted* 20-50 range for
Bluetooth Classic SPP. That quote is a manufacturer's marketing figure.

**Measured 2026-08-20: 14.8 cmd/s, zero packet loss, 66.7 ms mean round trip.**
Below even the supposed floor. The link is perfectly reliable and simply slow,
and since a single-frame PID takes under 2 ms on a 500 kbit CAN bus, nearly all
of that is adapter overhead rather than the car.

The probe now also races six adapter configurations against each other
(`tuneLatency`) and prints a `TUNING VERDICT` naming the fastest. **Set the poll
tiers from that, not from the range above.**

`ProbeSession.judge` maps a measured rate onto four outcomes:

| Verdict | Meaning | What to do |
|---|---|---|
| `HEADROOM` (≥50 cmd/s) | Comfortably above the full schedule | The fast tier could rise above 10 Hz |
| `ADEQUATE` (≥33.3) | Full schedule fits | Ship as designed |
| `DEGRADED_ONLY` (≥18.3) | Full schedule will stall | Halve the fast tier; `PollSchedule.degraded()` exists for this |
| `INSUFFICIENT` (<18.3) | Cannot sustain even degraded | Something is wrong — check for a competing app on the socket |

Read the **drop rate and p95 latency** alongside it. A high raw rate with 20%
drops is not throughput, and a 30 ms mean hiding a 400 ms stall reads as
smooth on average and looks like a freeze on the display.

---

## The logs

Every command, reply and timestamp goes to NDJSON at:

```
/sdcard/Android/data/dev.swordfish/files/probe/probe-<timestamp>.ndjson
```

Pull them with `tools\pull-probe.bat`.

**The log is the point, not the screen.** Whatever is on screen in the car
will be misremembered by the time you are back at a desk. NDJSON was chosen
because it survives truncation — a killed process loses the last line, not the
file — and appends without rewriting.

The `"kind":"cmd"` records are the most valuable thing the first run produces:
real ND2 reply strings from this specific adapter. `ObdPid.extractDataBytes`
is currently tested only against synthetic frames, so these become test
fixtures that pin the parser against the hardware it actually has to survive.

---

## What the probe deliberately does not do

**~~No MS-CAN probing.~~ Superseded — it is built and it works.** The probe now
runs `STP 53` / `ATH1` / `ATMA` as its final phase and captures raw frames for
8 seconds. Confirmed on 2026-08-20: 227 frames across 18 arbitration IDs.

It runs **last** because `STP 53` moves the adapter onto a different bus, which
takes away the connection every other phase needs.

Run this phase **parked with the engine running** — it needs the DSC module
awake, and nobody should be watching a phone for CAN frames while driving.

**~~No tiered poller, and no live gauge.~~ Also superseded.** Both are built
(`ObdPoller`, `TelemetryService`, and `GaugeScreen` wired to live telemetry).
The panel now prefers real data and falls back to a labelled DEMO frame.

**The tier RATES, however, are still provisional** — they were designed against
the quoted 20-50 cmd/s and the measured figure is 14.8. That is what
`tuneLatency` exists to settle.

---

## Failure modes, and what each one means

| Symptom | Almost certainly |
|---|---|
| No devices in the picker | `BLUETOOTH_CONNECT` not granted, or the MX+ not paired in Android Settings |
| Socket opens, `ATZ` times out | Another app holds the SPP socket — the OBDLink app is still running |
| `ATZ` answers `STOPPED` | The adapter was left mid-command by an earlier session. **Handled automatically now** — the probe retries 3×. Previously this aborted the run, and opening/closing the OBDLink app "fixed" it only by resetting the adapter as a side effect. |
| Handshake fine, `UNABLE TO CONNECT` | Ignition off or in accessory mode |
| Handshake fine, `NO DATA` on one PID | That PID genuinely is unsupported. A fact about the car, not a fault — this is why `ElmProtocol` classifies `NO_DATA` separately from `ERROR` |
| Rate under 20 cmd/s | Something else is contending. Retest with Bluetooth earbuds off and the OBDLink app force-stopped |
| App missing on the real head unit | The trusted-source restriction may apply after all — see Stage 2 |

---

## After the drive

1. `tools\pull-probe.bat`
2. Compare the PID sweep against `VehicleCapabilities.ND2_2023_OBSERVED`.
   Additions are new capabilities; **disappearances matter more**, since they
   mean either the survey or the sweep is wrong.
3. Feed real reply strings into `ObdPidTest` as fixtures.
4. Set the poll tier rates from the measured figure, and record it in
   `docs/` so it stops being an assumption.
5. If `015E` turned up, stop and re-read — the whole fuel model is built on
   its absence, and a native fuel-rate PID would replace the MAF path
   outright.
