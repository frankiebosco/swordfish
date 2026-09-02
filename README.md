# Swordfish

A spacecraft-style instrument panel for a 2023 Mazda MX-5 ND2 Club (6MT), drawn on the
car's own head unit: **a delta-V budget, a live NOAA weather radar scope, and an attitude
navball with compass.**

The delta-V readout reskins fuel economy as an orbital fuel budget - remaining petrol
becomes propellant, the car becomes a stage, and light-throttle cruising in 6th sends your
specific impulse climbing. The aim is to make hypermiling feel like flying an efficient
trajectory rather than driving slowly.

The **radar scope** is heading-up, like an aircraft display: the top of the scope is
always where you are pointed. It pulls the National Weather Service's public base
reflectivity mosaic - free, keyless, no quota - so it needs no account and no subscription.

The **navball** is a real attitude indicator driven by the phone's IMU, with pitch, roll
and a compass ribbon, sitting beside the numbers rather than replacing them.

All of it renders over Android Auto as a real gauge - **not a phone clipped to the
windscreen.** As of 2026-08-23 it has its own tile on the Mazda Connect launcher, with live
OBD-II telemetry over Bluetooth, verified across multi-hour drives.

**MIT licensed.** Built against one car, so it will not run unmodified on yours - but the
physics module is plain Kotlin with no Android dependencies, and the hard-won parts (the
OBD survey, the MS-CAN decoding, the Android Auto traps) are all written down. See
[CONTRIBUTING.md](CONTRIBUTING.md) - **data from a different car is the single most useful
thing anyone could contribute.**

## Status

Running on the real car. Version 0.17.8, **1030 tests green** (949 physics, 50 app, 31 harness).

| Piece | State |
|---|---|
| Physics core (`:physics`) | **Done** - 949 tests, no Android deps |
| OBD-II PID decoders | **Done** - validated against the real car |
| Vehicle survey | **Done** - 59 PIDs found by our own sweep; `docs/VEHICLE_SURVEY.md` |
| Fuel tracking + grade estimation | **Done** - flow-integrating, slosh-filtered |
| Bluetooth ELM327 transport | **Done** - OBDLink MX+, ~15 cmd/s measured |
| Live telemetry poller | **Done** - most-overdue scheduling, one-way degradation |
| Android Auto app | **Done** - NavigationTemplate + Canvas, runs on real hardware |
| Instrument panel | **Done** - navball, compass, stat rows; `docs/INSTRUMENT_PANEL.md` |
| Delta-V budget | **Done** - drains monotonically, verified over 63 min |
| Launcher tile on the car | **Done** - required a Play-attributed install |
| Drive recording | **Done** - NDJSON per second, pulled with `tools/pull-probe.bat` |
| MS-CAN access | **Working and dense** - 68k frames at ~76/sec. Two signals decoded: `215` four wheel speeds, `202` engine RPM + vehicle speed. Scale factor and left/right convention still need one calibration drive each; see [docs/MSCAN_SIGNALS.md](docs/MSCAN_SIGNALS.md) |
| Weather radar scope | **Done** - heading-up NOAA imagery in the centre column; 33 fetches verified 2026-08-24 |
| Logbook | **Done** - phone-side directory of past drives, stats, three charts and an efficiency-coloured route retrace |
| Logbook charts | **Done** - Isp by speed, energy waterfall, time in state; hand-rolled, no charting library |
| Units | **Done** - metric/imperial toggle; delta-V and Isp stay SI whatever it says |
| Crash recovery | **Done** - an interrupted recording is resumed rather than fragmented; unproven in the field |
| Layout on the head unit | **Verified 2026-08-24** - tuned layout, radar toggle, range control and collapsed/full-width switching all behave in accessory mode |
| Absolute altitude | **Rebuilt on surveyed USGS data** - weather-independent by construction; the open question is network coverage on the ridge road, unverified on the road |
| Public distribution | Source is MIT and public. The Play build is private internal testing only. |

### What is verified, and how

The 2026-08-23 reference drive: 63.5 minutes, 3669 samples, 99.8% link uptime, no crashes,
delta-V draining 7129 -> 3075 m/s, effectively monotonic -- zero large jumps, and 66
increases with a median of +1.1 m/s, which is 0.02% of full scale and reads as tank slosh.

Raw drive logs are not committed. A drive log is a GPS trace, and for a car parked at home
each night that is a home address with timestamps -- see [CONTRIBUTING.md](CONTRIBUTING.md).
Findings from a drive belong in `docs/`; the traces stay on the machine that recorded them.

## Running the tests

The physics module is pure Kotlin/JVM, so it needs **no Android SDK, no hardware and no
car**. If you only want to read the model, start here:

```bash
./gradlew :physics:test           # 949 tests, ~20 s
./gradlew :app:testDebugUnitTest  # 50 tests
./gradlew :layout-harness:test    # 31 tests, the panel-preview tooling
./gradlew test                    # all three
```

Note `tools\ship.bat` runs only `:physics:test` — run all three before any upload. Both
shipped regressions were caught by the other two suites.

## Building

A clean clone builds without any of the author's signing material: the release signing
config is skipped when `keystore.properties` is absent, so `./gradlew :app:assembleDebug`
works out of the box. You need an Android SDK and a `local.properties` pointing at it.

Two lanes, because Play re-signs the shipped app and `adb install` can no longer update it:

```bash
tools\dbg.bat     # dev.swordfish.debug -> sideload to the Desktop Head Unit
tools\ship.bat    # signed AAB -> Play internal testing -> the real car
```

The debug build installs alongside the shipped one as **Swordfish DBG**, so fast visual
iteration never risks the Play attribution the launcher tile depends on. See `docs/`.

## The model

### The right reference is a jet, not a rocket

**Read `docs/THE_JET_ANALOGY.md` first** — it is the clearest statement of what this
project is, and it is verified against real KSP readings.

Short version: a rocket is a poor comparison because it is mostly propellant, so its Δv is
dominated by the mass ratio. A Miata's mass ratio is ~1.03. But an **air-breathing jet**
is a near-perfect comparison: it does not haul oxidiser, its Isp is recomputed live rather
than fixed, its Δv is enormous, and the budget collapses when the pilot demands thrust
instead of efficiency.

Readings from a KSP turbofan aircraft at two throttle settings confirm the game uses
exactly our formulas — TWR matches to 2 decimals, Δv to under 2%. Backing off the throttle
took its Isp from 4,000 s to 9,000 s and its budget from 18,525 to 57,984 m/s on the same
fuel. That is precisely the instrument we are building, one level down: a passenger car
instead of a fighter jet.

Notably, **the Miata at wide-open throttle scores about the same Isp as that jet at
cruise** — because a car does not spend thrust holding itself up.

### Why the rocket equation needs care here

Tsiolkovsky says `dv = Isp * g0 * ln(m0/mf)`. It works for rockets because they are
mostly propellant — a Falcon 9 is about 95% fuel by mass, so the logarithm does real
work.

A Miata is not. Dry mass is ~2268 lb, a full tank is ~73 lb, so the mass ratio is
about **1.031** and `ln(1.031) ~ 0.030`. The logarithm is operating on its near-linear
region, which means **a literal delta-V figure is very nearly proportional to fuel
remaining** — a fuel gauge in a costume.

That is not a flaw in the analogy; it is the analogy correctly reporting that a car is
a terrible rocket. The fix is to put the interesting term in the foreground.

### Effective specific impulse — the hero stat

Specific impulse is thrust per unit weight-flow of propellant. A car in steady cruise
makes exactly enough tractive force to balance road load, so:

```
Isp_eff = F_resist / (mdot_fuel * g0)

F_resist = F_aero + F_rolling + F_grade
         = 0.5*rho*Cd*A*v^2  +  Crr*m*g*cos(theta)  +  m*g*sin(theta)
```

This behaves exactly the way the game wants:

- **High** when you overcome a lot of force per unit fuel — tall gear, light load
- **Low** when you burn a lot for little useful force — low gear, high rpm
- **Zero at a standstill**, where fuel flows and no work is done

Isp swings by an order of magnitude with driving style, which is why the UI should
lead with it.

### An eyebrow-raising result

Effective Isp at highway cruise comes out around **30,000 seconds**. An RS-25 manages
452 s in vacuum. This is not a units bug — it is real, and it has a clean explanation:
a rocket must carry its own oxidiser and hurl reaction mass overboard at kilometres
per second, while a car takes oxygen from the atmosphere for free and pushes against
the entire planet. Per unit of fuel burned, the car wins enormously.

Worth putting on screen. Cruising in 6th, the Miata outscores a Saturn V by about 100x.

### Two delta-V numbers

Both are reported, because they answer different questions:

- **`deltaVRemaining`** — the honest Tsiolkovsky figure. Thematically correct, and
  nearly fuel-linear for the reason above.
- **`rangeEquivalentDeltaV`** — the linearised form, `Isp * g0 * (fuel / m_total)`.
  Proportional to achievable distance, so it behaves the way a driver expects a fuel
  gauge to behave. It is the first-order Taylor expansion of the Tsiolkovsky form, and
  a test pins the two within 2% of each other.

### Gravity losses

Fuel spent climbing is buying potential energy rather than velocity — precisely a
rocket's gravity losses. `gravityLossWatts = m * g * sin(theta) * v`, positive uphill
and negative on the way back down, where the energy is returned. This is where the
metaphor stops being a skin and becomes the same maths.

## Architecture

```
physics/                     Pure Kotlin/JVM. No Android dependencies. 42 files.
  Units.kt                   SI conversions. Convert at boundaries only.
  Vehicle.kt                 Vehicle params + Telemetry sample type.
  DeltaVModel.kt             Road load, Isp, delta-V, gravity loss, gear inference.
  Thermodynamics.kt          Energy-route Isp, cross-checking the momentum route.
  Thrust.kt                  Torque curve, tractive force, thrust-to-weight ratio.
  Traction.kt                Friction-circle usage and a slip check.
  OperatingState.kt          CRUISE / IDLE / DFCO / DESCENT / OFF.
  EfficiencyBand.kt          Assessment of how well the right foot is doing.
  Payload.kt                 Crew and cargo, with a no-disclosure entry path.
  FuelTracker.kt             Flow integration + slosh-resistant tank-sensor fusion.
  GradeEstimator.kt          Barometer/GPS complementary filter -> road grade + altitude.
  Attitude.kt                Roll/pitch/G from raw accelerometer, + MountCalibration.
  MountAutoCalibrator.kt     Two-stage auto-levelling, move detection, persistence.
  ElectricalState.kt         Bus voltage interpretation.
  ObdPid.kt                  PID constants, frame parsing, decoders, capability probe.
  ElmProtocol.kt             ELM327 reply classification; retryable vs fatal.
  PollSchedule.kt            Tiered OBD poll rates and the transport budget.
  PollCursor.kt              Most-overdue scheduling + staleness clock.
  TelemetryAssembler.kt      Fresh readings -> a Telemetry sample.
  ProbeSession.kt            Bring-up probe decisions and verdicts.
  MsCanProbe.kt              MS-CAN frame capture and moving-byte summary.
  PanelLayout.kt             All panel geometry, as testable arithmetic.
  RadarLayout.kt             Radar-mode layout transform + heading-up scope projection.
  RadarTile.kt               nowCOAST WMS URL building, refresh + distance maths.
  DriveLog.kt                Reads a recorded drive back: distance, fuel, mpg, delta-V spent.
  DriveTrack.kt              Projects a drive's GPS trace, coloured by efficiency.
  DriveResume.kt             Decides whether a log was abandoned mid-drive, and what to restore.
  DriveCharts.kt             Reduces a drive to the three shapes worth plotting.
  ChartScale.kt              Axis ticks, value-to-pixel mapping, donut angles.
  TrackMerge.kt              Merges drives by position AND direction of travel.
  FixGate.kt                 Rejects GPS fixes that teleport or are too rough to trust.
  ElevationQuery.kt          USGS 3DEP surveyed ground elevation: URL, parsing, fetch policy.
  UnitSystem.kt              Metric/imperial display formatting. Delta-V stays SI, always.
  MsCanIdentify.kt           Correlates MS-CAN bytes against a reference to identify signals.
  MsCanCapture.kt            Pairs each frame with what the car was doing at that instant.
  MsCanReplay.kt             Re-analyses a saved capture against a drive log, offline.
  NavballScale.kt            Pitch ladder, roll ticks, compass tape positions.
  OrbitalScale.kt            Mapping delta-V onto recognisable orbital milestones.
  SevenSegment.kt            Segment glyph definitions.
  DemoFrame.kt               Static sample frames for deskside panel review.
  Conditions.kt              Air density and ambient handling.
  LinkState.kt               Connection state machine for the status banner.

app/car/                     Car App Service, Screen, Canvas renderer, segment display.
app/obd/                     Bluetooth transport, poller, IMU, recorder, probe, radar,
                             elevation, MS-CAN capture.
app/ui/                      Phone-side configuration, the bring-up probe, and the logbook.
```

Keeping OBD decoding and **all layout arithmetic** in the pure module means frame formats
and panel geometry are unit-tested without an emulator or a car. The Android layer owns the
Bluetooth transport, the sensors and the Canvas, and nothing else. Every panel dimension
that has ever been "tuned by looking at a screenshot" has later broken at another aspect
ratio - `PanelLayout` exists so those values can be checked numerically instead.

## Design decisions

**Fuel flow comes from MAF, corrected.** The car was surveyed on 2026-08-19 and
**PID `015E` (engine fuel rate) is not supported** — so MAF (`0110`) is not a fallback
here, it is the only path. Fortunately the ND2 does report commanded lambda (`0144`) and
both fuel trims (`0106`/`0107`), which fixes the naive `MAF ÷ 14.7` conversion's two real
weaknesses: open-loop enrichment (up to +25% fuel at WOT) and closed-loop trim (+11.6% at
the observed idle). See `docs/VEHICLE_SURVEY.md`. `VehicleCapabilities` still probes on
connect so the code stays honest on any other car.

**Tank level is not trusted raw.** PID `012F` *is* supported (observed at 83.1%), so no
manual fill-up flow is needed — but it is still slosh-noisy, and in a Miata on the roads
you actually want to drive the float swings hard. `FuelTracker` integrates fuel flow for
the live number and lets the sensor correct long-term drift through a deliberately slow
complementary filter, rejecting readings that disagree by more than 15% of capacity.

**Grade fuses two bad sensors into one good one.** The barometer resolves sub-metre
altitude changes instantly but drifts with weather; GPS altitude does not drift but is
noisy at +/-10 m. Barometer drives short-term change, GPS trims accumulated offset.
Grade is clamped to 30%, since no public road exceeds that and anything beyond is
sensor error headed straight for the gravity-loss display.

**Two pressure sources, two different jobs.** The car reports its own barometer (`0133`)
and true ambient air temp (`0146`), which beat the phone for *air density* — no weather
drift, and real outside air rather than the 45 C heat-soaked intake reading. But vehicle
BARO is quantised to 1 kPa (~85 m per count), far too coarse to resolve a hill, so
*grade* still comes from the phone. Vehicle sensors for drag, phone sensors for gravity
losses.

**Payload treats occupant privacy as a constraint.** Every occupant is entered as either
a standard adult mass or an exact figure, and neither is privileged — asking a passenger
their exact weight is an unpleasant prompt, so the averages make disclosure unnecessary
while exact entry stays available. A test pins the honest justification: 30 lb moves
delta-V by under 2%, so rounding genuinely does not matter.

**Gearing is stock and now correctly modelled.** The published ND 6MT ratios were always
right; the bug was a tire radius 6% too large. Corrected to the 0.2989 m loaded radius,
the model says ~2456 rpm at 60 mph in 6th and ~2661 at 65 — the ND is a famously busy
highway cruiser. A top-speed cross-check corroborates the ratio/radius pair.

**Deceleration fuel cutoff is a state, not a number.** Coasting in gear, the ECU shuts
the injectors entirely: flow is zero and instantaneous efficiency is infinite. Rather
than divide by zero, the model flags `inDeceleratingFuelCutoff` so the UI can show
something special — thematically, an unpowered coast.

## The weather scope

The centre column switches between the instrument readouts and a **heading-up weather
radar**, toggled from the action strip. A convertible driver genuinely wants to know
whether it is about to rain, and a threat display fits the jet analogy better than a
moving map does.

**This is not a Play-compliance play and must not be justified as one.** Adding a map
does not make the navball or the delta-V readout compliant; those violate the guidelines
regardless, and they are the product. The scope exists because it is useful.

Imagery is NOAA nowCOAST MRMS base reflectivity over standard WMS — free, no key, no
quota. Fetches run on a single background thread and the render path only ever reads a
field, so a slow or failing fetch costs a stale picture rather than a frozen gauge. The
picture refreshes every 150 s, and a range change invalidates it outright: the same
pixels drawn against rings that now mean a different distance is worse than showing
nothing.

`RadarTile` (URL and refresh maths) and `RadarLayout` (the layout transform and the
heading-up projection) are both in `:physics`, so the geometry is unit-tested without a
car. `RadarSource` in `:app` owns only the HTTP transport and the bitmap.

Verified on the 2026-08-24 drive: 33 fetches, zero failures, both ranges exercised, and
the phone serves the fetches while projecting. What is *not* yet proven is whether the
action-strip buttons can be reached with the rotary controller in motion.

## The logbook

Every drive is recorded to NDJSON, one row a second. The phone app reads them back: a
directory of past drives with distance, duration, economy, the delta-V the trip cost, and
a route retrace coloured by how efficiently each stretch was driven.

It is a phone screen and never a head-unit one. Reading back a drive is a parked activity,
and a directory of past journeys on the car screen would invite exactly the behaviour the
instrument was designed to avoid.

**The retrace has no basemap**, for the same reason the radar has no roads under it: a
tile source means an API key, a quota and a network dependency, and it answers a question
the logbook is not asking. The shape of the route plus where the good and bad stretches
were is the interesting part.

**A drive survives the app dying.** Android Auto restarts a crashed service in about a
second, and until 2026-08-24 each restart began a new recording — one journey became three
files and the trip's starting budget reset each time. A log with no closing row and a
recent last row is now resumed rather than orphaned.

## Open questions

Nothing blocking, and the model itself is settled. What remains:

- **Absolute altitude drifts** - 68 m over a single drive that returned to the same spot.
  The barometer is referenced to the standard atmosphere and the GPS trim appeared never
  to arrive. The multi-provider location fix now demonstrably works — `gps_alt_fixes`
  climbed 0 -> 4472 on the 2026-08-24 drive — but whether the *drift* is gone needs a
  there-and-back check. Relative altitude and grade are unaffected, because the reference
  cancels out of a difference.
- **Two MS-CAN calibrations are open, and both need one specific drive each.** Wheel
  speeds (`215`) and engine RPM + road speed (`202`) are decoded, but the counts-to-m/s
  scale factor is unfitted and it is not yet known which frame positions are the left-hand
  wheels. `WheelCalibration` implements both fits with acceptance thresholds fixed in
  advance; only the data is missing. Note the scale **cannot** be fitted against OBD road
  speed - the probe owns the socket, so `speed_mps` stops for the whole capture, and
  GPS-derived speed is the only available cross-check.
- **No lateral-G or yaw sensor has been found on MS-CAN.** Every byte and byte-pair of all
  20 observed arbitration IDs was correlated against GPS-derived yaw rate; nothing exceeded
  r = 0.45. Either it lives on a bus that is not being monitored, or this car's DSC derives
  yaw from wheel speeds as well. The test used a 1 Hz reference against a 10 Hz signal, so
  it is suggestive rather than conclusive. Full findings in
  [docs/MSCAN_SIGNALS.md](docs/MSCAN_SIGNALS.md).
- **The phone slides on the passenger seat**, forcing repeated re-levelling. Calibration
  now survives a restart, which hides it well, but the real fix is a cradle.
- **`015E` engine fuel rate** answers but has only ever read zero, so the mixture-corrected
  MAF path remains the only fuel-flow source. Needs a warm-idle re-run to settle.

## Platform — head unit is the product

**Goal: a real gauge on the Mazda Connect screen**, not a phone on a windshield mount.
The phone is a development and configuration surface; the head unit is where you read it.

This is achievable. See `docs/ANDROID_AUTO_RESEARCH.md` for the full research.

**Android Auto supplies no vehicle telemetry.** The USB link is one-way UI projection —
the phone sends a rendered UI, the head unit returns touch input. It is not a CAN tap.
All data comes from the OBD-II port via a Bluetooth ELM327 dongle.

**But Android Auto does allow custom drawing.** Navigation, POI and weather apps can take
a real `Surface` from `NavigationTemplate` / `MapWithContentTemplate` and render to it
with Canvas, a VirtualDisplay, or Jetpack Compose. It is the same mechanism Maps and Waze
use for their live maps. Requires `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE` permissions
and the `androidx.car.app.category.NAVIGATION` category.

**Plain sideloading does not work for Car App Library apps.** Android Auto's developer
"Unknown sources" toggle covers media, messaging and parked apps but explicitly *not*
apps built on the Car App Library. `adb install` will not get the app onto a real head
unit.

**Play Internal App Sharing does work, with no review.** Google documents it as the
supported route to test on real vehicles "without going through the Google Play review
process". Cost: a **$25 one-time Play Console account**. No car-category declaration, no
manual review, up to 100 testers, links valid 60 days.

**Swordfish is distributed privately, through Play internal testing.** Google's car app
quality criteria - no animated elements (`SA-1`), only map content on the navigation
surface (`NF-2`), real turn-by-turn required (`NF-6`) - are **publishing requirements
enforced by manual review**, not technical limits in the framework. A live delta-V gauge
violates all three, so it will not go on the public store as it stands. Internal testing is
not reviewed, so none of them gate a private build.

**Correction to an earlier plan recorded here:** this was going to be plain sideloading via
Internal App Sharing, and that turned out to be impossible in both halves. Sideloading a
Car App Library app is refused by the real head unit outright, and internal app sharing
itself *requires the app to be published to a track first*. The route that works is a
$25 Play developer account plus **internal testing**, which is also what supplies the Play
ownership the launcher tile turns out to depend on. See `docs/`.

**Development loop:** the **Desktop Head Unit (DHU)** emulator handles day-to-day
iteration with ordinary local installs and no Play involvement. Internal App Sharing is
only needed when verifying in the actual car.

### What the head unit constrains

We work with these rather than against them:

- **Unknown geometry.** Resolution and DPI arrive at runtime via `SurfaceContainer`, and
  host chrome can overlap the edges. Lay out from `onVisibleAreaChanged` /
  `onStableAreaChanged`; put anything critical in the stable area.
- **Minimal interaction.** Only `onClick`, `onScale`, `onScroll`, `onFling` reach the
  surface. All configuration — crew masses, cargo, fill-ups, units — lives on the phone.
  The head unit is a read-only instrument with at most a tap to change mode.
- **Dark theme is mandatory.** Honour `CarContext.isDarkMode()` and redraw on
  `Session.onCarConfigurationChanged()`. No hardship: a KSP instrument wants dark anyway.
- **Full instrument panel, deliberately.** The "no animated elements" rule is a Play
  Store criterion we are already outside of, and the whole premise is that efficient
  driving should feel like flying a spacecraft. Navball, six readouts, the lot. One
  glanceable primary (Δv) carries the at-speed reading; the density rewards a longer look
  while parked. See `docs/INSTRUMENT_PANEL.md`.
- **Cluster display is a stretch goal.** Navigation apps may draw to the instrument
  cluster via `FEATURE_CLUSTER`. Untested on the ND2, but a Δv readout in the gauge
  binnacle is the ideal end state, so the category is worth declaring early.

## Hardware

**Bluetooth Classic SPP is the only viable transport**, and the choice is driven by
throughput rather than convenience. See `docs/CONNECTIVITY.md` for the full analysis.

| Transport | Sustained rate | Verdict |
|---|---|---|
| **Bluetooth Classic (SPP)** | 20–50 cmd/sec | The only option that works |
| WiFi | 10–25 cmd/sec | Occupies the phone's WiFi — no internet while driving |
| BLE | 5–15 cmd/sec | Cannot come close to our poll budget |

Our nine-PID schedule needs **~33 commands/sec**, which sits *above the low end* of
Bluetooth Classic's range. That makes adapter quality a hard requirement rather than a
preference — a clone at the bottom of the range physically cannot run this.

**In use: OBDLink MX+.** Measured on this car at **14.8-15.4 commands/second** with zero
drops - the link is perfectly reliable and simply slow, at 66.7 ms mean round trip, of
which ~97% is adapter and transport overhead rather than the car.

**Superseding the earlier recommendation in this file:** the LX was recommended on the
grounds that the MX+ added nothing for $50 more. That was true *while the scope was
economy only*. It stopped being true when traction and roll entered scope, because the MX+
reaches **MS-CAN** - confirmed on this car with `STP 53`, 227 frames across 18 arbitration
IDs - and that is a class of data generic OBD-II cannot provide. The coverage PDF that
appeared to rule this out is dated 12.2019 and simply predates the 2023 ND; its silence was
not a negative.


The **vLinker MC+ (~$35)** is a genuine fast chipset and may cope — measure the achieved
rate early rather than assuming. **Avoid** generic $8 "ELM327 v2.1" clones entirely; their
dropouts get misdiagnosed as interference for a week.

### Tiered polling

`PollSchedule.kt` encodes the budget, unit-tested without hardware:

| Rate | PIDs | Cost |
|---|---|---|
| 10 Hz | RPM, speed, MAF | 30 cmd/s |
| 1 Hz | lambda, short trim, long trim | 3 cmd/s |
| 0.1 Hz | fuel level, barometer, ambient temp | 0.3 cmd/s |

A flat 10 Hz poll across all nine would be 90 cmd/s — no adapter delivers that. Tiering
saves ~63% by polling each value at the rate it actually changes. A `degraded()` schedule
halves the fast tier (never drops PIDs) to fit a weaker adapter, since a coarser gauge
beats a stuttering one and beats losing Isp entirely.

### Coexistence with Android Auto

No conflict on the ND2. Wired Android Auto runs over **USB**, so the Bluetooth radio is
free for the dongle. The car's hands-free pairing uses HFP, the dongle uses SPP —
different profiles, and Android handles concurrent connections routinely. Proven in the
field by `aa-torque`, an open-source app doing exactly this.

The one caveat on record: **wireless** Android Auto uses Bluetooth plus a 5 GHz Wi-Fi
link, putting three radios in play. Not our situation, but it would become one if the
setup ever changed.

---

## License

MIT — see [LICENSE](LICENSE). Copyright © 2026 Bergen Palisades Technology LLC.

Take it, fork it, build on it. The licence covers the code; the **Swordfish**
name and branding are not included in the grant, so please give forks their own
name.

Before using it in a car, read [NOTICE.md](NOTICE.md) — it covers the
read-only nature of the OBD connection, the fact that this is not a safety
system, and the known accuracy limits of the fuel and delta-V model.
