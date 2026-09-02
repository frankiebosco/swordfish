# MS-CAN decoded signals — 2026-08-27

What the bus actually carries, decoded from 125,985 clean frames across three
captures on the ridge-road loop (v76, the first build to capture at full rate).

**Decoders live in `:physics`:** `WheelSpeeds` (ID `215`), `EngineFrame` (ID `202`),
and `WheelCalibration` for the two open calibrations. All offline-testable against
saved captures via `MsCanReplay` -- no driving needed to try a new hypothesis.

**Frame counts here PRE-DATE `<DATA ERROR` recovery (v77).** Two thirds of every capture
was being discarded as unparsable when the frames were intact; re-running these analyses
on the same files should now yield roughly three times the data.

**Status of each claim is marked.** CONFIRMED means correlated against an
independent reference. INFERRED means the structure is clear but the scaling or
meaning is not yet pinned. OPEN means unidentified.

---

## STATUS: parked (2026-08-29)

**The decoding here is real and the signals are confirmed. The application it
was aimed at is not viable.**

Wheel-derived yaw was pursued to give the navball a chassis-mounted attitude
reference instead of a phone sliding on a passenger seat. It cannot serve
that purpose: **fuel, rpm and speed live on HS-CAN (500 kbaud, `ATSP6`) while
wheel speeds live on MS-CAN (125 kbaud, `STP 53`)** -- different physical pins
on the OBD-II port, one adapter, one bus at a time. A live wheel-yaw navball
would mean a dash showing no speed and no fuel flow.

What survives is genuinely useful and strictly **offline**: a saved capture is
a chassis reference to check the phone against after a drive. That is worth
having. It is not an instrument feed.

**Before resuming any of this**, measure the bus-switch latency -- it is the
one number that decides whether time-slicing the two buses is possible, and
it has never been measured. The full account, including the mistakes, is in the wheel-speed sections
below.

## The bus at a glance

20 arbitration IDs. Only **six carry moving data**; the other fourteen are
static (mostly all-zero) and are probably unpopulated on this vehicle.

| ID | frames | moving bytes | what it is | status |
|---|---|---|---|---|
| `215` | 1,711 | all 8 | **four wheel speeds** | CONFIRMED |
| `202` | 16,410 | 0-5, 7 | **engine RPM + vehicle speed** | CONFIRMED |
| `217` | 1,244 | 0-3 | four-channel, brake/torque candidate | OPEN |
| `0FD` | 7,827 | byte 2 only | 16 discrete states | OPEN |
| `47B` | 6,599 | bytes 0-1 | 4 discrete states | OPEN |
| `477` | 7,855 | byte 1 | 2 states (binary flag) | OPEN |

Static IDs: `491` `21C` `050` `492` `25D` `228` `166` `503` `4F8` `4F0` `09B`
`4F5` `09A`, plus `086` (9 frames only).

---

## `215` — four wheel speeds (CONFIRMED)

*Decoder: `WheelSpeeds`.*


Four 16-bit big-endian values in one 8-byte frame, **offset 10000**.

```
27 10 27 10 27 10 27 10   ->  [10000, 10000, 10000, 10000]   stationary
2A 91 2A CF 2A 97 2A CF   ->  [10897, 10959, 10903, 10959]   moving
```

`0x2710` = 10000 is the stationary sentinel. Subtract 10000 to get a speed
count. All four pairs correlate with the aggregate at **r = 0.999**.

### Wheel layout: positions 0,2 are one side; 1,3 are the other (CONFIRMED)

Determined by which pairing shows the largest spread through turns, since a
turn makes one SIDE consistently faster while the axles differ only slightly:

| pairing | stdev | max |
|---|---|---|
| (0,1) vs (2,3) | 20.7 | 119.5 |
| **(0,2) vs (1,3)** | **86.3** | **377.5** |
| (0,3) vs (1,2) | 5.0 | 42.5 |

The 4x spread on `(0,2) vs (1,3)` identifies it as the left/right split, so the
frame is laid out **alternating by side**, not axle-by-axle.

**Cross-checked against GPS**: `(w0+w2)/2 - (w1+w3)/2` correlates with GPS yaw
rate at **r = +0.781**, with the sign consistent. That confirms the split AND
gives yaw from wheel speeds directly.

### SETTLED (2026-08-29): positions 1,3 are LEFT; 0,2 are RIGHT

`WheelSpeeds.MEASURED_CONVENTION = POS_1_3_IS_LEFT`. Two independent
controlled counter-clockwise (left-turn) circle runs, counting only genuinely
cornering frames:

| drive | cornering samples | (0,2) faster |
|---|---|---|
| 2026-08-28 | 81 | 78% |
| 2026-08-29 | 76 | 82% |

In a left turn the RIGHT wheels take the larger radius and read faster, so
the consistently-faster pair (0,2) is the right side.

**The fix was method, not data.** Three earlier attempts read 45%/33%/57% off
perfectly good captures because they (a) used 1 Hz GPS heading to pick
"cornering" frames from a 10 Hz signal, (b) counted straight-line samples
whose +/-15.7-count scatter splits 50/50, and (c) pooled bidirectional ridge road
drives with single-direction circle runs. `CORNERING_THRESHOLD = 20.0` and
`Reading.isCornering` now exist so sign questions never sample noise.

**Still unknown: which of 0/2 is front.** That needs a firm straight-line
pull, where the driven (rear) axle reads faster under power.

### Yaw rate is now available from the chassis

`Reading.yawRateRadPerSec(countToMps, trackWidthM)`. Two wheels on one axle
differ by `yawRate * track` -- plane geometry, no drift, no phone. Positive =
turning right.

Sanity check on the 2026-08-29 circle: 21 mph with a 109-count side
difference gives 11.5 deg/s and a 47 m turn radius, which is a real rotary.

Magnitudes remain provisional: yaw scales linearly with the unfitted
`countToMps` and with the catalogue `ND2_TRACK_WIDTH_M = 1.495`. The sign and
shape are solid.

**Planned test: a nearby traffic circle.** US traffic circles run counter-clockwise, which is a sustained LEFT
turn — so `turnedLeft = true`, and in a left turn the RIGHT wheels are outside
and read faster. A rotary beats a parking-lot loop: constant radius, constant
direction, and no one calls the police about a car doing laps.

Two or three laps if traffic allows. At ~10 Hz, one lap of a small rotary may
only yield 80–120 turning samples and the threshold is 100. Steady 10–15 mph;
below ~5 mph the side difference collapses toward the 0.5-count resolution
floor.

### Capture modes: discovery vs calibration (2026-08-28)

`ATMA` forwards the whole bus, and on this car that is more than the adapter
can carry. The 2026-08-28 Esplanade capture -- 3.5 laps counter-clockwise at
a steady ~19 m radius, textbook calibration input -- could not be fitted:
the adapter reported **`BUFFER FULL` twice and `STOPPED` once**, roughly half
the window delivered nothing, and only **46** usable `215` frames survived
against a threshold of 100.

The bottleneck was never the driving or the decoder. It was asking a serial
link to carry 20 IDs when the calibration needs two.

`MsCanProbe.CaptureMode` now separates the two jobs:

| mode | bus | for |
|---|---|---|
| `DISCOVERY` | unfiltered `ATMA` | finding IDs nobody has identified |
| `WHEEL_CALIBRATION` | `ATCM 7E8` / `ATCF 215` | fitting the wheel-speed signal |

Measured against that same capture: **9,278 frames observed, 1,100 kept --
88% fewer frames, 8.4x the headroom.**

No mask admits exactly `215` and `202`; `7E8`/`215` is the pair that leaks
the fewest others (exactly one, `217`, ~165 frames). The leak is harmless --
`217` is dropped by ID at parse time -- so the filter is a throughput
optimisation, never a correctness gate. **The arithmetic is asserted in
`MsCanCaptureModeTest`**, because the first draft of the mask silently
blocked `215`, which would have reproduced the exact failure it was written
to fix and looked like a quiet bus.

**Discovery sends `ATCRA` to clear the filter.** A filter left over from a
calibration run would make discovery blind to every ID it exists to find.

### Adapter warnings are never capped

`BUFFER FULL` / `STOPPED` now write a `"kind":"adapter"` record that is
exempt from `MAX_UNPARSED_LOGGED`, and the count reaches the stop record as
`"overflow"`.

On 2026-08-28 the 4,000-line diagnostic cap filled **41 s into an 80 s
capture**; the 5,914 lines after it -- about 1.5 of the 3.5 laps -- were
discarded at the moment of capture and were **not recoverable from the
file**. The three lines that explained the loss survived only by luck of
arriving early. A capture that lost half its window looked merely
disappointing.

### `215` has TWO frame layouts, split by speed (RESOLVED 2026-08-29)

The 2026-08-28 "truncation" is not damage. The short-frame diagnostic
preserved the raw text and it reads `215 32 F1 33` -- a **clean, complete
line**: no error marker, no `<DATA ERROR`, terminated normally. The adapter
emitted three byte pairs deliberately.

**Throughput ruled out.** Slice delivery was flat ~450/slice at `unparsed=0`
across the whole 2026-08-29 capture *including* the short stretch, so the
adapter was never behind. The earlier congestion theory (frame rate doubling
through the turn) was wrong.

**The split is by speed, and it is sharp:**

| form | w0 range (2026-08-29) | frames |
|---|---|---|
| 8-byte (four wheels) | 3062 - 7102 | 351 |
| 3-byte | 2465 - 3041 | 450 |

No overlap. Boundary near **~3050 counts, roughly 19 mph** on a crude GPS
scale. Same pattern on 2026-08-28 (the 11 apparent exceptions are a brief
recovery island mid-run, not a counterexample).

**What survives in the 3-byte form:** `w0` is complete (bytes 0-1) and byte 2
is the HIGH byte of `w1` only. That pins `w1` to +/-128 counts while the side
difference being hunted is 40-80 counts -- **the missing wheel cannot be
reconstructed**. A single-wheel speed trace IS recoverable and is gap-free at
10 Hz, which is enough for scale work but not for the side convention.

### This changes the calibration drive

The standing advice -- steady **10-15 mph** around the circle -- puts the car
squarely in the 3-byte regime. That is why three calibration attempts have
produced clean captures and no fit.

The usable window now has **both** bounds:
- **above** ~19 mph, to stay in the 8-byte four-wheel form
- **below** the speed at which the circle cannot be held safely

and the old lower bound (~5 mph, where the side difference collapses into the
0.5-count resolution floor) is now irrelevant -- it sits deep inside the
3-byte regime.

**Unverified:** the ~19 mph figure rests on a GPS-derived scale with n=75 and
should be treated as approximate until a capture straddles the boundary with
a trustworthy speed reference.

### The scale factor is NOT yet fitted

Deliberately left open rather than guessed. Two things blocked it:

1. **OBD road speed is unavailable during a capture.** The probe takes the
   socket, so `speed_mps` stops for the capture's whole duration. Verified:
   zero speed samples inside every capture window.
2. **GPS-derived speed was too noisy** in this dataset — differencing position
   fixes across the (then still present) 10 s gaps produced impossible values
   up to 65 m/s. A dense capture with no gaps should fix this.

`202` bytes 2-3 give an independent speed in the SAME units (ratio 0.998), so
once either is scaled, both are.

---

## `202` — engine RPM and vehicle speed (CONFIRMED)

*Decoder: `EngineFrame`. Both values returned RAW -- neither divisor is fitted.*


Two signals in one frame, both 16-bit big-endian.

### bytes 2-3 = vehicle speed

Correlates with `215` aggregate at **r = 0.916**, and the direct ratio is
**0.998** (median over 1,039 paired samples) — the same units as `215`, from a
different module. Reads **exactly 0** when stopped, unlike `215` which reads
its 10000 sentinel.

### bytes 0-1 = engine RPM

Range 4,115–12,283. Nonzero (~4,100–5,400) when the car is stopped, which is
what an idling engine looks like and what rules out its being another speed.

**The proof is the gear signature.** RPM-to-road-speed ratio, over 1,039
samples, is not a smooth spread — it has two sharp peaks:

```
  2.9 :  24  ########
  3.0 : 366  ##################################################
  3.1 : 119  #######################################
  ...
  5.2 : 167  ##################################################
  5.3 : 137  #############################################
  5.4 :  51  #################
```

Two clusters at **~3.0** and **~5.3** = two gears, which matches the drive
(ridge-road cruise in top gear, town driving in a lower one). A quantity whose ratio to
road speed clusters at discrete values IS engine speed, by definition of a
gearbox.

**The exact RPM divisor is unfitted** — the raw range /4 gives 1,029–3,071 rpm
which is plausible, but idle-RPM alignment could not be checked because `215`
and `202` timestamps never coincided closely enough at a full stop in this
dataset. One idle capture settles it.

**This makes gear detection free**: ratio ≈ 3.0 vs ≈ 5.3 tells you which gear
without any additional signal.

---

## Traction and slip — the structure is there

**MX-5 is RWD, so front wheels are undriven and rear are driven.** The
front-rear difference IS wheelspin; no separate slip signal is needed or
exists.

Measured over 637 moving frames:

| split | mean | max |
|---|---|---|
| left-right (cornering) | 50.9 | 377.5 |
| **front-rear (slip)** | **11.7** | **119.5** |

The front-rear split is small on this drive — as expected, since normal road
driving in the dry produces almost no wheelspin. **The signal exists and is
measurable; this drive simply had nothing to measure.**

To see real slip: a deliberate hard launch on a wet or loose surface, in a safe
place. That would move the front-rear split well outside its 11.7 baseline and
confirm the sign (rear faster under power).

**This is the answer to "can we get traction from MS-CAN": yes, arithmetically,
once the layout's front/rear assignment is confirmed.** It does not need a
dedicated slip signal.

---

## Lateral acceleration — NOT FOUND, and probably not on this bus

Every byte, signed byte, and 16-bit pair of all 20 IDs was correlated against
GPS-derived yaw rate. **Nothing exceeded r = 0.45.**

The only yaw-correlated quantity is the derived left-right wheel difference
(r = 0.781), which is computed, not transmitted.

Two possible readings, and this dataset cannot separate them:

1. A lateral-G / yaw sensor is on a bus we are not monitoring, or behind a
   different `STP` protocol selection.
2. This car's DSC derives yaw from wheel speeds too, and never puts a raw
   lateral-G value on MS-CAN.

**Worth noting the reference was weak**: GPS yaw rate at ~1 Hz against a signal
that would change much faster. A rerun with the phone IMU's own yaw as
reference, on a dense gap-free capture, would be a fairer test before
concluding it is absent.

---

## What would settle each open question

| Question | Test |
|---|---|
| Wheel speed scale factor | Dense capture + steady GPS-tracked cruise at known speed |
| RPM divisor | Any capture with the car idling and both `202`/`215` frames present |
| Which side is left | One sustained turn in a known direction (roundabout / parking-lot circle) |
| Which position is front | Same test — undriven axle lags under acceleration |
| Real slip magnitude | Deliberate hard launch on a low-grip surface |
| Lateral G exists? | Rerun the yaw correlation with IMU yaw, on a gap-free capture |
| `217` / `0FD` / `47B` / `477` | Targeted state tests — brake pedal, indicators, lights, doors |

`0FD` (16 states, one byte), `47B` (4 states), and `477` (binary) look like
discrete status fields rather than continuous sensors — the fastest way to
identify them is to operate one control at a time while capturing.
