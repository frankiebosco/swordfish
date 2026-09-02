# The Instrument Panel

> **This is the original design brief, not a status document.** It records what the panel
> was *meant* to be and why, and most of it has been built. Where it disagrees with the
> app, **the app is right** — in particular the
> "Modes" and "Responsive layout" sections below predate the WIDE/NARROW/MINIMAL
> breakpoint system and the radar centre-column mode, neither of which is described here.
> Kept because the *reasoning* is still load-bearing: the density argument, the
> glanceability rule and the palette all still govern new work.
>
> **To change where anything sits, use the layout tuner** — `./gradlew
> :layout-harness:tune` drags elements against the real head-unit geometry and writes the
> values back into `PanelLayout.kt`. Do not tune by editing numbers and driving.

The head-unit display. A full KSP-style flight instrument, not a fuel gauge with a
spaceship sticker on it.

The design brief, verbatim: *"this should be stupid and fun. no reason not to."* Every
decision below follows from that, with exactly one countervailing rule (see
[Glanceability](#glanceability)).

---

## Why density is allowed here

Google's `SA-1` criterion forbids animated elements on car app screens, and `NF-2`
restricts the navigation surface to map content. Both are **Play Store manual-review
criteria**, and Swordfish is distributed via Internal App Sharing, which bypasses review
entirely. Nothing in the framework stops us drawing whatever we like.

More to the point: a single static Δv number would be a *worse* version of the trip
computer already in the dash. The premise of this project is that efficient driving should
feel like flying a spacecraft. Spacecraft have instrument panels. Build the panel.

---

## Layout

Landscape, since every head unit is. Proportional units throughout — see
[Responsive layout](#responsive-layout).

```
┌──────────────────────────────────────────────────────────────────────┐
│  ORBITAL FUEL BUDGET                              STAGE 1 · ND2 CLUB │
│                                                                      │
│   ┌────────────┐    ┌──────────────────────┐    ┌────────────────┐   │
│   │            │    │                      │    │ Isp     31 420s│   │
│   │  NAVBALL   │    │      Δv  1 847       │    │ ████████░░░░░  │   │
│   │            │    │         m/s          │    │                │   │
│   │  ⊕ pitch   │    │                      │    │ TWR      0.31  │   │
│   │    horizon │    │  ── range 1 792 ──   │    │ Mass  2 481 lb │   │
│   │            │    │                      │    │ Fuel     9.9ga │   │
│   └────────────┘    └──────────────────────┘    └────────────────┘   │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ GRAVITY LOSSES  ▁▂▃▅▇▅▃▂▁  −4.2 kW        ALT 312 ft  ↗ 3.1%   │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  GEAR 6   2 461 RPM   MODE: CRUISE          ⬤ EFFICIENCY BAND       │
└──────────────────────────────────────────────────────────────────────┘
```

### The six readouts

Counting the way the brief did — six live numeric readouts plus the navball:

| # | Readout | Source | Why it earns its place |
|---|---|---|---|
| 1 | **Δv remaining** | `Readout.deltaVRemaining` | The headline. Tsiolkovsky, honest, fuel-linear. |
| 2 | **Range-equivalent** | `Readout.rangeEquivalentDeltaV` | The practical figure — will you make it home. Shown smaller, beneath. |
| 3 | **Effective Isp** | `Readout.effectiveIsp` | The hero stat. Swings an order of magnitude with your right foot. |
| 4 | **TWR** | `Thrust.thrustToWeight` | Thrust-to-weight. See [below](#twr). |
| 5 | **Total mass** | `Vehicle.totalMassKg` | Ship mass, live, falling as you burn. |
| 6 | **Propellant** | `FuelTracker.fuelGallons` | Fuel remaining, in gallons and as a bar. |

Plus the navball, the gravity-loss strip, and a status line (gear, rpm, mode, efficiency
lamp).

**TWR and Isp are deliberately adjacent.** They move in opposite directions — a tall gear
buys efficiency at the cost of acceleration — so putting them side by side makes the
central trade of the whole game legible at a glance. A test pins that opposition.

---

## The navball

A real artificial horizon driven by road grade, not an ornament.

- **Sphere** split brown (below) / blue (above), rotating in pitch as `gradeRadians`
  changes. Climbing pitches the horizon down, exactly as an aircraft AI does.
- **Prograde marker** ⊕ sits at centre while cruising; drifts up under acceleration and
  down under braking, driven by the derivative of speed.
- **Roll is real.** An earlier draft of this doc said roll had to stay level because "we
  have no lateral sensor" — that was wrong. **The phone is the lateral sensor.** The moto g
  carries an ICM-4x607 accelerometer and gyro at up to 400 Hz, plus fused
  `ROTATION_VECTOR` and `GRAVITY` at 200 Hz. `Attitude.kt` turns those into genuine roll,
  pitch and cornering G, once `MountCalibration` has worked out how the phone sits in its
  cradle.
- **Grade in degrees** printed beneath.

Because grade is smoothed and clamped to ±30% by `GradeEstimator`, the ball moves
gently — no jitter, no wild swings on a sensor glitch.

### Why the navball is worth the effort

It is the single most recognisable KSP element, it encodes a real measurement (grade),
and it is the thing that makes a passenger say "what *is* that". That is the whole point.

---

## TWR

Thrust-to-weight ratio, the KSP number everyone checks before launch. For a car:

```
TWR = F_available / (m · g)
```

where `F_available` is tractive force at the wheels. We can estimate it from engine torque
and the current gear ratio, or approximate from throttle position against a peak-torque
figure. An ND2 makes ~151 lb-ft; in 1st that is a lot of multiplication, in 6th very
little — so **TWR falls as you upshift**, which is both correct and satisfying.

A Miata's TWR is around 0.3 in a low gear. A Saturn V leaves the pad at 1.15. This is a
fine thing to display.

**Implemented** in `Thrust.kt`. Measured values for the ND2 at peak torque:

| Gear | Tractive force | TWR |
|---|---|---|
| 1 | 8 488 N | **0.77** |
| 2 | 4 991 N | 0.45 |
| 3 | 3 396 N | 0.31 |
| 4 | 2 660 N | 0.24 |
| 5 | 2 146 N | 0.19 |
| 6 | 1 669 N | **0.15** |

Saturn V leaves the pad at 1.15, Falcon 9 at 1.4 — so the Miata loses to a Saturn V even
in first gear, which is a fact the panel should absolutely display.

The torque curve is a deliberately crude inverted parabola about the 4000 rpm peak, not a
dyno table. What matters for a game readout is that it responds in the right direction and
lands in the right ballpark; a test pins the shape. Clutch in or neutral returns zero
thrust, which is correct and matches the DFCO state.

---

## Grip indicator

The ND2 has real traction sensors — four wheel-speed sensors, yaw rate, lateral
accelerometer, steering angle — which is how the DSC telltale knows to flash. They live on
the **ABS/DSC module**, and none of them appear in the 34-PID generic survey.

**They were reached (2026-08-29), and it still does not help here.** The four wheel
speeds decode cleanly from MS-CAN frame `215`, and yaw follows from them as plane
geometry — see `docs/MSCAN_SIGNALS.md`. But wheel speeds are on **MS-CAN (125 kbaud)**
while speed, rpm and fuel are on **HS-CAN (500 kbaud)**: different physical pins, one
adapter, one bus at a time. Reading the chassis sensors live means a panel with no speed
and no fuel flow, which is not a trade worth making for a grip estimate.

So the panel still estimates from the phone. The wheel data is an **offline** reference —
useful for checking the phone's answer after a drive, not for feeding the gauge. The full
account is in [MSCAN_SIGNALS.md](MSCAN_SIGNALS.md).

**The friction circle.** A tyre's grip is a finite budget shared between cornering and
braking/accelerating. `hypot(lateralG, longitudinalG)` is how much is in use, and that is
the honest headline: *"you are at 70% of what these tyres will do."* Four coarse bands —
CRUISING / WORKING / PRESSING / AT THE LIMIT — because the peak-grip denominator is an
assumption and a precise percentage would overstate what we know.

**The slip check.** In a steady turn, a gripping car satisfies `a_lat = v × yaw_rate`.
When measured lateral G falls below what speed and yaw rate predict, the car is rotating
faster than its cornering force explains — which is what a slide looks like from outside.
A crude cousin of what DSC itself does, from the phone gyro and PID 010D alone.

**Presented as observation, never as warning.** The car's own DSC is authoritative and
will intervene long before a phone-derived estimate is worth acting on. This is for
looking at afterwards, not reacting to.

**Cornering costs fuel**, and the panel can say so: tyre slip angle produces drag the
engine must overcome, which is why a twisty road returns worse economy than a motorway at
the same average speed. `Traction.corneringDragWatts` estimates it, letting the panel
attribute part of an Isp drop to cornering rather than leaving it unexplained.

## Efficiency band lamp

A single indicator that lights when you are in the sweet spot: roughly 1800–2200 rpm at
light load, in the tallest sensible gear. Driven by rpm, `LOAD_PCT`/`LOAD_ABS`, and the
inferred gear.

Three states: **dim** (out of band), **lit** (in band), **bright with Isp spike** (in band
*and* Isp above a rolling personal best). The last is the reward loop — it should feel
like a good burn.

---

## Modes

Cycled by tapping the surface (`onClick` is one of the four gestures Android Auto passes
through).

| Mode | Shows |
|---|---|
| **CRUISE** | The full panel above. Default. |
| **BURN** | Isp and instantaneous MPG enlarged; navball shrinks. For actively hypermiling. |
| **STAGE** | Mass breakdown — dry, crew, cargo, propellant — as a stacked bar. Parked reading. |
| **DFCO** | Auto-entered when `inDeceleratingFuelCutoff` is true. See below. |

### The DFCO screen

When coasting in gear with injectors closed, instantaneous efficiency is infinite and the
model reports the state rather than a number. The panel should mark this properly:
**"ENGINE CUT — BALLISTIC COAST"**, the Isp readout replaced by ∞, and the navball prograde
marker drifting down.

It is the closest a car gets to an unpowered trajectory, it costs nothing to detect, and
it is exactly the sort of thing this project exists for.

---

## Glanceability

The one rule that survives the "stupid and fun" brief, because it is about not crashing
rather than about taste:

**One element must be readable in half a second.** That is the **Δv figure** — largest
type on the panel, high contrast, centre-left. Everything else exists to reward a longer
look while stopped, or a passenger's attention.

Density is fine. An undifferentiated wall of equal-weight numbers is not — that fails at
being an instrument panel *and* at being safe. Strong typographic hierarchy is what makes
the difference, and it is the thing to get right first.

**Nothing invites interaction while moving.** Mode cycling is a single tap anywhere, and
even that is optional; the panel is fully useful without ever being touched.

---

## Responsive layout

Head-unit geometry arrives at runtime and varies enormously between cars.

- Read width, height, DPI from `SurfaceContainer` in `onSurfaceAvailable`
- Keep every element inside the **stable area** from `onStableAreaChanged`
- Treat `onVisibleAreaChanged` as the preferred, larger canvas — decorative elements may
  extend into it, live numbers may not
- Express all positions as fractions of the stable rect; scale type from its height
- **Degrade gracefully:** on a short surface, drop the gravity-loss strip first, then TWR
  and mass, then the navball. Δv is the last thing standing.

The ND2's Mazda Connect screen is 7" or 8.8" depending on year, but the layout must not
assume it.

---

## Palette

Dark, and mandatory to support properly — `CarContext.isDarkMode()` with redraws on
`Session.onCarConfigurationChanged()`. Convenient, since a KSP instrument wants dark.

| Role | Colour |
|---|---|
| Ground | near-black `#0B0E14` |
| Primary text / Δv | off-white `#E8ECF1` |
| Isp bar, in-band lamp | KSP green `#4CE0A0` |
| Gravity loss (climbing) | amber `#F0A860` |
| Gravity loss (descending) | cyan `#5AC8E0` |
| Navball ground | brown `#6B4A2F` |
| Navball sky | blue `#2A5A8C` |
| Dim / inactive | grey `#4A5260` |

Monospace for all numerals so digits do not jitter as values change — a real instrument
concern, not an aesthetic one.

---

## Build order

1. **Δv only**, large, centred, responsive. Proves the surface pipeline end to end.
2. **Isp bar + efficiency lamp.** The core reward loop.
3. **Navball.** The showpiece.
4. **Gravity-loss strip + altitude.** Needs the grade estimator wired to phone sensors.
5. **Mass, propellant, gear, rpm.** Straightforward once the layout system exists.
6. **TWR.** Physics already done (`Thrust.kt`); just needs displaying.
7. **Modes**, including the DFCO screen.

Ship step 1 to the real car early. Everything after that is decoration on a working
instrument, and decoration is the point.
