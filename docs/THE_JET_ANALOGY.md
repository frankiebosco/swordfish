# Why This Works: The Jet, Not The Rocket

The clearest statement of what Swordfish is:

> **A live Δv budget for the Miata that behaves exactly like the one in a KSP jet
> aircraft — a large number, continuously recomputed from a variable Isp, that drains as
> you drive and drains *much* faster when you demand thrust instead of efficiency.**

Not an analogy. The same maths, applied to a passenger car instead of an aircraft.

---

## The insight

Early framing in this project compared the car to a **rocket**, which caused a real
problem: a rocket is mostly propellant, so its Δv is dominated by the mass ratio, whereas
a Miata's mass ratio is ~1.03 and the logarithm barely does anything. That made the Δv
figure look like a fuel gauge in costume, and led to a (wrong) suggestion that it should
be rescaled.

The right comparison was sitting in KSP the whole time: **an air-breathing jet**.

Frank's DEA Evader 1 — a twin-turbofan aircraft — carries a Δv readout that:

- is enormous (tens of thousands of m/s), because jets do not haul oxidiser
- **is not constant**: MechJeb recomputes Isp live from airspeed, altitude and throttle
- drains continuously while flying
- drains *catastrophically faster* under high thrust demand

That is precisely the instrument we are building, and precisely the behaviour we want.

### Observed in flight

Two screenshots of the same aircraft, minutes apart, differing mainly in **throttle
setting**:

| | Full throttle | Half throttle |
|---|---|---|
| Isp | **4,000 s** | **9,000 s** |
| Thrust | 339.20 kN | 101.66 kN |
| TWR | 3.02 | 0.92 |
| Δv | 18,525 m/s | 57,984 m/s |
| Liquid fuel | 1,125 / 1,160 | 1,086 / 1,160 |

**Backing off the throttle more than doubled Isp and tripled the Δv budget**, on
essentially the same fuel load. The aircraft became dramatically more capable the moment
it stopped asking for maximum thrust.

---

## KSP really is using our formula

Reverse-engineering the screenshot figures confirms the game computes exactly what
`DeltaVModel` and `Thrust` compute.

**Thrust-to-weight** — `TWR = F / (m·g₀)`:

| | computed | game | |
|---|---|---|---|
| Full throttle | 3.01 | 3.02 | ✅ |
| Half throttle | 0.92 | 0.92 | ✅ |

**Tsiolkovsky** — `Δv = Isp·g₀·ln(m₀/m_f)`:

| | computed | game | error |
|---|---|---|---|
| Full throttle | 18,368 m/s | 18,525 | 0.8% |
| Half throttle | 58,864 m/s | 57,984 | 1.5% |

Both within the precision of reading masses off a screenshot to one decimal tonne.

**Mass flow** — `ṁ = F / (Isp·g₀)`, cross-checked against burn time:

| | implied burn | game | |
|---|---|---|---|
| Half throttle | 4,775 s | 4,722 s | ✅ |
| Full throttle | 497 s | 651 s | ❌ |

The full-throttle mismatch is *informative rather than a problem*: at full throttle the
jet is accelerating hard, so Isp is changing throughout the burn, and MechJeb's estimate
integrates over that variation instead of assuming the instantaneous value holds.
**Swordfish has exactly the same property** — our Isp is recomputed every tick from live
road load and fuel flow, so a "range at current Isp" figure is only valid while conditions
hold.

---

## Why a car scores even higher than a jet

Both are air-breathing, which is the big win over a rocket: neither carries oxidiser, so
both extract far more force-seconds per kilogram of fuel.

But a jet must additionally spend thrust **holding itself up**. A car pushes against the
planet for free — no lift-induced drag, no gravity losses in level flight.

| Vehicle | Isp | Carries oxidiser? | Holds itself up? |
|---|---|---|---|
| Chemical rocket (RS-25) | 450 s | **yes** | **yes** |
| DEA Evader, full throttle | ~4,000 s | no | **yes** |
| DEA Evader, half throttle | ~9,000 s | no | **yes** |
| **Miata, WOT low gear** | **~9,000 s** | no | no |
| **Miata, 65 mph cruise** | **~31,500 s** | no | no |
| **Miata, hypermiling 6th** | **~45,000 s** | no | no |

Note the overlap: **the Miata at wide-open throttle scores about the same as the jet at
cruise.** Drive badly enough and you are, efficiency-wise, flying a fighter aircraft. That
is a fact the instrument panel should eventually say out loud.

---

## The control mapping

The jet has an afterburner; the Miata does not. But the *actual* control input in both
cases is the same, and it is the one the driver modulates continuously:

| | Jet | Miata |
|---|---|---|
| **Efficiency mode** | throttle back, cruise | light throttle, tall gear |
| Result | Isp rises, Δv budget expands | Isp rises, Δv budget expands |
| **Thrust mode** | full throttle / afterburner | wide-open throttle, low gear |
| Result | Isp collapses, Δv drains fast | Isp collapses, Δv drains fast |

Gear ratio in the car plays the role of a thrust setting — `Thrust.thrustToWeight` gives
0.77 in 1st and 0.15 in 6th — but **throttle is what the driver actually modulates**, and
it is what our Isp calculation is most sensitive to, because it drives fuel flow directly
while road load stays roughly constant.

Same mechanism. Same instrument. Same feeling.

---

## What this means for the build

**Do not rescale the Δv readout.** Its magnitude is correct, matches how KSP treats
air-breathing engines, and carries the project's best fact (a tank of petrol is ~80% of the delta-V to orbit). See `OrbitalScale`.

**Isp must be recomputed live, every tick.** It is not a vehicle constant. This is already
how `DeltaVModel.effectiveIsp` works, and it is the single most important behaviour to
preserve — it is what makes the number respond to the right foot.

**Show TWR next to Isp.** They move in opposite directions across the gears, which is the
same trade the jet makes between thrust and efficiency. Having both visible makes the
trade legible.

**The remaining gap is the live fuel-flow signal.** Everything above is implemented and
tested; the phone currently shows a static sample. Once MAF is streaming at 10 Hz from the
OBDLink LX, the Δv figure will move exactly the way the one in the cockpit does.


---

## But a car has no exhaust thrust — is this honest?

The fair objection: a jet and a rocket both produce thrust by accelerating mass
rearwards. A car burns gasoline to make heat, converts part of it to shaft work, and
pushes against the **road**. No propellant leaves the vehicle to produce force. Does the
Isp framing survive that?

**Two halves to the answer, and they differ.**

### Isp is rigorous, and independently verifiable

Specific impulse is defined as force per unit weight-flow of fuel, `F / (ṁ·g₀)`. That
definition says nothing about *how* the force arises. "How many newton-seconds of
resistance does this car overcome per kilogram of fuel" is a well-posed question with a
real answer.

The jet already stretches the classical reading, incidentally: it burns fuel with
atmospheric oxygen and accelerates air it never carried, so most of its reaction mass was
never aboard. The car is one further step along the same axis, not a different claim.

More importantly, **it checks out thermodynamically.** Isp can be derived a second way
with no momentum argument anywhere:

```
P_useful = F · v            work rate against road load
P_chem   = ṁ · LHV          chemical energy release rate
η        = P_useful / P_chem

⇒  Isp = η · LHV / (v · g₀)
```

At the 65 mph reference point both routes give **31,561 s**, agreeing to nine decimal
places, at an implied **tank-to-wheel efficiency of 20.7%** — exactly the right range for
a naturally aspirated petrol engine at light load. `ThermodynamicsTest` pins the identity
across the whole speed range.

So the chemical-energy conversion *is* accounted for. It is embedded in the fuel flow the
ECU reports, and the resulting Isp is consistent with actually burning gasoline at a
realistic thermal efficiency. `Thermodynamics.kt` implements both routes.

### The rocket equation is a borrowing, and we say so

**Tsiolkovsky's derivation does not apply to a car.** It comes from conservation of
momentum — a rocket accelerates *because* it throws mass backward, and `dv = −vₑ dm/m`
integrates to the logarithm. A car's momentum change is balanced by the Earth's.

So `Δv = Isp·g₀·ln(m₀/m_f)` is applied here **by analogy rather than derived**. Not a
hidden fudge — every term is well defined and the arithmetic is exact — but the reason the
logarithm appears is not operative for a car.

This is precisely why `DeltaVModel.rangeEquivalentDeltaV` exists alongside it. The
linearised form is what actually tracks achievable distance, and at a mass ratio of ~1.03
the two agree within 2% regardless. Both are shown; neither is presented as something it
is not.

### A bonus the energy route revealed

Writing Isp as `η·LHV/(v·g₀)` exposes something the force-based form hides: **Isp falls
with speed even at constant efficiency**, because speed is in the denominator.

| Speed | Isp at η = 25% |
|---|---|
| 25 mph | ~99,000 s |
| 45 mph | ~55,000 s |
| 65 mph | ~38,000 s |
| 85 mph | ~29,000 s |

That is physically real, and it is the reason hypermilers slow down. It also means the
instrument correctly rewards **reducing speed**, not merely feathering the throttle — a
genuinely distinct behaviour. And since road load additionally grows with v², the
real-world penalty is steeper still.
