# MX+ Arrival: What To Probe, And How To Decide

**Status: ANSWERED 2026-08-20. MS-CAN is reachable on this car.**

> **The bet paid off.** `STP 53` succeeded and `ATMA` captured **227 frames across
> 18 arbitration IDs** on a 2023 ND2 — a car outside OBDLink's documented coverage.
> The $50 was well spent.
>
> **What remains is identification, not access.** Only ID `0FD` showed a moving
> byte, which is correct for a parked car with the engine off: nothing was
> rotating, accelerating or steering. Steps 1, 4 and 5 are automated in the app
> (Swordfish -> HARDWARE -> OBD PROBE); step 3 needs a deliberate drive.
>
> **Observed IDs:** 491, 202, 21C, 050, 09A, 215, 217, 228, 477, 492, 0FD, 166,
> 25D, 503, 4F8, 4F0, 09B, 4F5.
>
> An earlier revision of this document said MS-CAN probing was deliberately kept
> out of the app, to be tried in the OBDLink app first. That was the right call
> while access was unproven; it is obsolete now that our own capture works.

The LX order was cancelled in favour of the **MX+ ($139.95)** specifically to chase
MS-CAN access to the car's own chassis sensors — yaw rate, lateral acceleration, wheel
speeds, steering angle. This document is the plan for finding out whether that works, and
what to do either way.

---

## Why the recommendation changed

The earlier analysis in `CONNECTIVITY.md` recommended the LX, and that was correct **for
the goal as it stood at the time**: a fuel-economy instrument, where every needed PID is
generic OBD-II and MS-CAN buys nothing.

The goal has since grown. Traction conditions and navball roll are now in scope, and the
car's chassis sensors are the only high-quality source for either. That makes the extra
$50 a bet on data that otherwise does not exist — a different question from the one the
LX analysis answered.

**The LX analysis is not retracted; its premise changed.** Worth being clear about that
rather than pretending the earlier reasoning was wrong.

---

## What we are hoping to find

The ND2 demonstrably measures all of this — it is how the DSC telltale knows to flash:

| Signal | Why we want it |
|---|---|
| **Yaw rate** | Direct rotation measurement. Makes the slip check trustworthy instead of inferential. |
| **Lateral acceleration** | Chassis-mounted, in a known orientation. No mount calibration, no phone-tilt ambiguity. |
| **Individual wheel speeds** | Real slip detection — compare driven against undriven wheels. Phone sensors cannot see this at all. |
| **Steering angle** | Intended path versus actual path, which is what DSC itself compares. |
| **TPMS pressures** | Would remove the user-entered tyre pressure in `Conditions.kt`. |

---

## The phone approach has a second, worse problem: it moves

Beyond the accelerometer's inability to separate roll from lateral acceleration,
there is a practical failure that decides the matter.

**The phone lives loose on the passenger seat and migrates during the drive.**
The two-stage calibration assumes a fixed mounting: gravity establishes down,
and a straight-line pull establishes forward. A phone that slides on every
corner invalidates both, repeatedly, mid-journey.

`hasMoved()` will detect it and force a re-calibration, which is the correct
behaviour — but it means the navball spends the drive cycling through
`LEVELLING` and `DRIVE TO ORIENT` rather than reading anything.

The car's own sensors are bolted to the chassis. No calibration, no drift, no
migration. **This is now the primary reason to want MS-CAN**, ahead of the
roll/lateral ambiguity.

## Why phone-derived roll is genuinely weaker

Worth stating the specific flaw rather than hand-waving, because it explains what the
car's sensors would fix.

**An accelerometer cannot distinguish body roll from lateral acceleration.** Both tip the
gravity vector in the same direction. In a steady corner,
`atan2(accel.x, accel.y)` returns the *sum* of:

- the chassis actually leaning on its springs, and
- cornering force acting on the sensor

Separating them requires integrating the gyroscope's roll rate and fusing it against the
accelerometer — which works, but drifts, and needs tuning. The car's yaw sensor has no
such problem: it measures rotation directly.

So `Attitude.kt` is a reasonable stand-in, and it is honest about what it reports (roll
*plus* camber *plus* cornering effect, undifferentiated). But if MS-CAN yields real yaw
and lateral data, that becomes the better source and the phone drops to a fallback.

---

## The probe plan

Work through in order. Steps 1-2 are the money; 3-5 are consolation prizes.

### 1. Confirm the basics still work

Before chasing anything exotic, verify the MX+ does everything the LX would have:

- [ ] Pairs over Bluetooth Classic SPP
- [ ] `ATZ` / `ATE0` / `ATSP0` handshake succeeds
- [ ] All nine scheduled PIDs return data (see `PollSchedule`)
- [ ] **Measure achieved commands/second** — the assumption the whole design rests on.
      Target ≥ 34 cmd/s. Log it before anything else.

### 2. Probe for MS-CAN

The MX+ exposes MS-CAN through `STP` protocol-selection commands. Approximately:

```
ATZ                 reset
STP 53              select MS-CAN (ISO 15765, 11-bit, 125 kbaud)
ATMA                monitor all traffic
```

**What success looks like:** a stream of CAN frames appears. Note the arbitration IDs.

**What failure looks like:** `NO DATA`, `CAN ERROR`, or silence. If so, either this car
does not expose MS-CAN on the OBD connector's spare pins, or it uses a different bus
configuration.

Also worth trying the OBDLink app's own **Mazda enhanced diagnostics**, which is the
supported path and will succeed or fail without any protocol fiddling. Note that the
coverage document stops at the **2017-2018 ND** — the 2023 car is outside documented
support, so this may simply not work.

### 3. If frames appear: identify them

Raw CAN IDs are meaningless without a mapping, and Mazda publishes none. Identification
is empirical:

- **Yaw rate** — sit stationary, note the idle value; turn the wheel lock to lock while
  rolling slowly and watch which bytes move.
- **Lateral acceleration** — drive a steady circle in an empty car park; the byte that
  tracks cornering direction and magnitude is the one.
- **Wheel speeds** — four values that track road speed and diverge slightly in a turn
  (outside wheels turn faster).
- **Steering angle** — moves with the wheel, stationary or not.

**Log everything to a file.** A ten-minute drive with a full CAN dump plus synchronised
phone IMU data is enough to identify all of these offline, and is far more productive than
guessing live.

### 4. If MS-CAN fails

Not a disaster, and not wasted money in the sense that matters — the MX+ is still a
first-rate adapter for the polling job, and the question needed answering. The phone-based
`Attitude.kt` and `Traction.kt` stand as designed, with their documented limitations.

Worth also checking whether the MX+'s **wider generic PID support** picks up anything the
Ancel AD310 missed. Different scan tools query different PID ranges, so a fresh
`0100`/`0120`/`0140`/`0160` sweep is cheap and might turn up something.

### 5. Either way: capture a baseline drive

Independent of the MS-CAN outcome, the first drive should log:

- All nine scheduled PIDs at their tiered rates
- Achieved poll rate over time
- Phone accelerometer, gyroscope, rotation vector, barometer
- GPS position and altitude

That dataset validates the road-load model (via `Thermodynamics.roadLoadPlausibility`),
calibrates the mount, and gives a replay source for developing against without driving.

---

## Decision record

| Question | Answer |
|---|---|
| Was the LX analysis wrong? | No — its premise (economy only) changed. |
| Is MS-CAN on this car confirmed? | **YES, 2026-08-20.** 227 frames, 18 IDs, via `STP 53`. Documented coverage stopping at 2017-2018 was silence, not a negative — that PDF is dated 12.2019. |
| Is phone roll good enough meanwhile? | Yes, with documented caveats. It stands until the CAN IDs are mapped. |
| What if MS-CAN works? | ~~Car sensors become primary; phone becomes fallback.~~ **Overstated.** Only ROLL is genuinely replaced: heading needs an absolute reference a yaw *rate* cannot give, and no chassis sensor measures road grade. The realistic end state is a fusion. |
| What if it does not? | Moot — it does. |

**The honest summary: this was a $50 bet on unconfirmed data, made with open eyes — and it
won.** MS-CAN is reachable, which is a class of data the phone cannot produce at all.

**One caveat found the same day**, which the original analysis did not anticipate: the MX+
delivered only **14.8 cmd/s** on the generic bus (66.7 ms per round trip, zero packet loss).
That is below the quoted Bluetooth Classic floor and forces the poll schedule to be
re-tiered. It has nothing to do with MS-CAN, but it does mean the assumption that "the MX+
is a first-rate adapter for the polling job" is itself now under measurement rather than
taken on faith. See `ElmProtocol.TUNING_VARIANTS`.
