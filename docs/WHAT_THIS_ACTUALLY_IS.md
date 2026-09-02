# What The Maths Actually Is

A precise account of every formula Swordfish uses, what its real name is, and
which parts are standard engineering versus borrowed by analogy.

Written because "it's the rocket equation applied to a car" is both the easiest
description and a misleading one. **Three of the four core formulas are ordinary
vehicle engineering.** Only the integration step is borrowed, and even that has a
non-rocket reading.

---

## The formulas, by their proper names

### 1. Road load — standard vehicle dynamics

```
F_resist = ½·ρ·Cd·A·v²  +  Crr·m·g·cos(θ)  +  m·g·sin(θ)
           aerodynamic     rolling            grade
```

This is the **road load equation**. It is what SAE coastdown testing measures
(J1263, and the later J2263 with onboard anemometry), what every manufacturer
uses for fuel-economy modelling, and what a dynamometer is programmed with.
Nothing exotic, nothing borrowed.

The **form** is standard. The **coefficients** here are catalogue values for
the ND2, not a coastdown on this specific car -- see the caveats in
`DEMO_SCRIPT.md`.

### 2. Specific impulse — a general quantity, not a rocket one

```
Isp = F / (ṁ · g₀)
```

**Specific impulse is defined as force per unit weight-flow of fuel.** That
definition never mentions rockets, exhaust, or reaction mass. It is dimensionally
seconds for any engine that produces force by consuming fuel.

What we compute is **road-load specific impulse** — a legitimate instance of the
general quantity, asking "how many newton-seconds of resistance does this vehicle
overcome per kilogram of fuel."

### 3. Tank-to-wheel efficiency — standard automotive

```
η = (F·v) / (ṁ·LHV)
```

Useful work over chemical energy released. Every powertrain engineer computes
this.

### 4. The bridge: Isp and BSFC are the same measurement

Substituting (3) into (2):

```
Isp = η·LHV / (v·g₀)
```

This is worth dwelling on. **Brake specific fuel consumption** — BSFC, in
g/kWh — is *the* standard automotive efficiency metric. Specific impulse is its
reciprocal, scaled by g₀.

Aerospace and automotive engineers have been measuring the same physical quantity
with different yardsticks for a century. Converting between them is a unit change,
not an analogy.

`Thermodynamics.ispFromEnergy` implements this route, and it agrees with the
force-based route to **nine decimal places** at an implied 20.7% tank-to-wheel
efficiency — squarely correct for a naturally aspirated petrol engine at light
load. A test pins the identity across the speed range.

---

## The one borrowed piece

```
Δv = Isp · g₀ · ln(m₀/m_f)
```

**This is Tsiolkovsky, and its derivation genuinely does not apply to a car.**

It comes from conservation of momentum: a rocket accelerates *because* it throws
mass backward, and `dv = −vₑ·dm/m` integrates to the logarithm. A car's momentum
change is balanced by the Earth's.

We apply it by analogy. That is stated plainly rather than hidden, and it is
precisely why `DeltaVModel.rangeEquivalentDeltaV` exists alongside it — the
linearised form is what actually tracks achievable distance. At a mass ratio of
1.03 the two agree within 2% regardless.

### But Δv has a defensible non-rocket reading

The figure answers: **"what velocity change could this fuel buy, for this mass,
at the efficiency I am currently achieving?"**

That is a real quantity — a **fuel-energy budget expressed as a velocity** — and
computing it requires no momentum exchange with exhaust. It is closer in spirit
to "how many kilowatt-hours are in the tank" than to a rocket's Δv. It simply
*coincides* with rocket Δv when the vehicle happens to be a rocket.

---

## So what would we call it, if not the rocket equation?

The accurate description:

> **A propellant-budget formulation of vehicle energy accounting** — road load
> and fuel flow expressed as specific impulse, integrated over remaining fuel
> mass.

Or plainly: **fuel economy in impulse units**.

MPG and Isp answer the same question — how much useful output per unit fuel —
in different currencies. MPG answers in distance; Isp answers in force-seconds.
Neither is more fundamental. Isp is simply the convention that makes a car
directly comparable to a spacecraft, which is the entire point of the project.

**The display still says Δv**, because that is what it is, everyone knows what
it means, and it is what makes the instrument worth building.

---

## The strongest objection, answered

A 2016 r/AskScience thread asked whether the rocket equation applies to cars and
aircraft. The top reply (u/Overunderrated) is correct on both counts it raises,
and worth answering directly because it is the sharpest available critique.

### "It ignores forces other than thrust — drag, rolling friction"

True of the classical rocket equation, and it assumes we are using Tsiolkovsky to
*predict a vehicle's velocity change*. We are not.

**We invert it.** Rather than ignoring drag and rolling resistance, they *are*
the numerator — `F_resist` in formula (2) above. The forces the objection says
the equation ignores are the entire input to ours.

### "It assumes all accelerated mass started within the rocket"

Also true, and the sharper point — but the reply concedes it in the same
paragraph. It notes that for a jet, thrust is
`(air + fuel flow) × exhaust velocity − (air flow × incoming velocity)`, and that
in practice **"you totally ignore the fuel flow rate altogether."**

That is an admission that specific impulse stays meaningful for air-breathing
engines whose reaction mass was never aboard. A turbofan at 4,000–9,000 s already
sits far outside rocket territory for exactly this reason. Swordfish is one step
further along an axis the objection has already accepted, not off in a different
direction. See `THE_JET_ANALOGY.md`, where our formulas reproduce KSP's jet
readouts to under 2%.

### Where the objection would land

If we claimed Tsiolkovsky's *derivation* holds for a car, it would be right and
we would be wrong. We do not, and the docs say so.

### What the thread could not have

That exchange was a physics argument. We have a measurement: the same Isp derived
two independent ways — one purely mechanical, one purely thermodynamic — agreeing
to nine decimals at a realistic engine efficiency.

The number is not a rocket formula misapplied. It is a standard automotive
efficiency measurement that happens to be expressible in seconds.
