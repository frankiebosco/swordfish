# Vehicle Survey — 2023 MX-5 ND2 Club

**Surveyed 2026-08-19** with an Ancel AD310 handheld scanner, engine warm, stationary,
26 photographs of the live-data and vehicle-info screens.

| Property | Value |
|---|---|
| Protocol | **CAN OBD-II** (ISO 15765-4) |
| Cal ID | `PG5XEC000PEP6010` — the `PE` prefix is Mazda's SkyActiv-G 2.0 family |
| Trim | Club, **soft top**, full BBS / Brembo / Recaro package, bone stock |
| Curb weight | **2381 lb** incl. full tank -> dry mass 2307.6 lb |
| Tires | 205/45R17 -> **0.2989 m** loaded rolling radius |
| Supported live-data items | **34**, enumerated exactly by the scanner |
| DTCs stored | 0 · MIL off · all readiness monitors OK (Htd Catalyst N/A) |
| Distance since codes cleared | 18,460 km |

---

## Headline findings

**1. PID `015E` (engine fuel rate) is NOT supported.** The scanner's own item count
(34) and the complete enumeration below both confirm it. The MAF path is therefore not
a fallback on this car — **it is the only route to fuel flow**, and the whole Isp model
depends on it.

**2. Everything needed to make the MAF path accurate IS supported.** Commanded lambda
(`0144`), both fuel trims (`0106`/`0107`), MAP (`010B`), IAT (`010F`) and ambient air
temp (`0146`) are all present. This closes most of the accuracy gap to a native fuel-rate
reading — see "The MAF correction" below.

**3. Tank level `012F` is supported** and reads to one decimal (83.1%). No manual
fill-up flow is required, though the slosh filtering stays.

**4. The car reports its own barometer** (`0133`, 101 kPa) **and true ambient air
temperature** (`0146`, 42 °C). Vehicle sensors beat phone sensors here: no weather
drift on the barometer, and AAT is real outside air where IAT read 45 °C from underhood
heat soak.

**5. Fuel rail pressure reads 10,070 kPa** (~100 bar), confirming direct injection —
consistent with SkyActiv-G and worth noting because DI engines run leaner in the
light-load regime this app is built around.

---

## Complete supported-PID enumeration (34 items)

Transcribed from the scanner's "View Graphic Items 1/34 … 34/34" screens.

| # | Scanner label | PID | Meaning | Used by Swordfish |
|---|---|---|---|---|
| 1 | `LOAD_PCT` | `0104` | Calculated engine load | Efficiency band |
| 2 | `ECT` | `0105` | Engine coolant temp | — |
| 3 | `SHRTFT1` | `0106` | Short-term fuel trim B1 | **Fuel flow correction** |
| 4 | `LONGFT1` | `0107` | Long-term fuel trim B1 | **Fuel flow correction** |
| 5 | `MAP` | `010B` | Manifold absolute pressure | Load proxy, speed-density check |
| 6 | `RPM` | `010C` | Engine speed | **Gear inference** |
| 7 | `VSS` | `010D` | Vehicle speed | **Core** |
| 8 | `SPARKADV` | `010E` | Spark advance | — |
| 9 | `IAT` | `010F` | Intake air temp | Air density (heat-soaked) |
| 10 | `MAF` | `0110` | Mass air flow | **Fuel flow — primary** |
| 11 | `TP` | `0111` | Throttle position | Display |
| 12 | `O2B1S2` | `0115` | O2 sensor B1S2 voltage | — |
| 13 | `SHRTFTB1S2` | `0115` | Short trim B1S2 | — |
| 14 | `RUNTM` | `011F` | Run time since start | — |
| 15 | `MIL_DIST` | `0121` | Distance with MIL on | — |
| 16 | `FRP` | `0123` | Fuel rail pressure | Diagnostic (DI confirmation) |
| 17 | `EVAP_PCT` | `012E` | Commanded evaporative purge | — |
| 18 | `FLI` | `012F` | **Fuel tank level** | **Fuel mass** |
| 19 | `WARM_UPS` | `0130` | Warm-ups since cleared | — |
| 20 | `CLR_DIST` | `0131` | Distance since cleared | — |
| 21 | `EVAP_VP` | `0132` | Evap system vapour pressure | — |
| 22 | `BARO` | `0133` | **Barometric pressure** | **Air density / altitude** |
| 23 | `EQ_RAT11` | `0134` | O2S1 equivalence ratio | — |
| 24 | `O2S11` | `0134` | O2S1 current | — |
| 25 | `CATEMP11` | `013C` | Catalyst temp B1S1 | — |
| 26 | `VPWR` | `0142` | Control module voltage | — |
| 27 | `LOAD_ABS` | `0143` | Absolute load value | Efficiency band |
| 28 | `EQ_RAT` | `0144` | **Commanded equivalence ratio** | **Fuel flow correction** |
| 29 | `TP_R` | `0145` | Relative throttle position | — |
| 30 | `AAT` | `0146` | **Ambient air temperature** | **Air density** |
| 31 | `TP_B` | `0147` | Absolute throttle position B | — |
| 32 | `APP_D` | `0149` | Accelerator pedal position D | — |
| 33 | `APP_E` | `014A` | Accelerator pedal position E | — |
| 34 | `TAC_PCT` | `014C` | Commanded throttle actuator | — |

**Conspicuously absent:** `015E` engine fuel rate, `0159` absolute fuel rail pressure,
`0152` ethanol fuel percentage, `015C` engine oil temperature.

Note the scanner shows some PIDs under two labels (`EQ_RAT11`/`O2S11` are both `0134`;
`O2B1S2`/`SHRTFTB1S2` are both `0115`), which is why 34 display rows map to fewer
distinct PID numbers.

---

## Observed values — warm idle, stationary

The single reference sample everything is calibrated against.

| Signal | Value | Note |
|---|---|---|
| RPM | 784 | Warm idle |
| VSS | 0 km/h | Stationary |
| MAF | **2.31 g/s** | The key measurement |
| MAP | 29 kPa | vs BARO 101 → heavy throttling, as expected at idle |
| BARO | 100–101 kPa | Fluctuates; 1 kPa quantisation |
| IAT | 45 °C | Heat-soaked |
| AAT | 42 °C | True outside air |
| ECT | 49 °C | Still warming |
| TP | 12.5 % | Closed-throttle rest position |
| LOAD_PCT | 23.5 % | |
| LOAD_ABS | 17.3 % | |
| SHRTFT1 | +6.1 % | ECU adding fuel |
| LONGFT1 | +5.5 % | ECU adding fuel |
| EQ_RAT | 1.028–1.029 | Very slightly lean of stoichiometric |
| FLI | 83.1 % | ≈ 9.89 gal ≈ 61 lb aboard |
| FRP | 10,070 kPa | ~100 bar — direct injection |
| VPWR | 13.649 V | Alternator charging |
| FUELSYS1 | CL | Closed loop |

### Sanity check — does the MAF path produce a believable idle burn?

```
MAF 2.31 g/s ÷ 14.7 AFR = 0.157 g/s fuel = 0.202 gal/h
```

A warm 2.0-litre four idles at roughly 0.2–0.4 gal/h, so this lands at the efficient end
of the expected band — exactly where an idling SkyActiv-G should sit. **The MAF path is
validated against real data.** This is pinned by
`Nd2ObservedTest.observed idle maf implies a plausible fuel burn`.

### Speed-density cross-check

Using MAP 29 kPa, IAT 45 °C, 1998 cc, 784 rpm, the ideal-gas airflow implies a
volumetric efficiency of ~0.56 at idle. That is a realistic figure for a heavily
throttled engine, so MAF and MAP corroborate each other rather than contradicting.

---

## The MAF correction

The naive `MAF ÷ 14.7` conversion is wrong in exactly the situations that matter most,
and this car gives us the data to fix both:

**Enrichment.** Under high load the ECU commands lambda well below 1.0 (as low as ~0.80
at wide-open throttle) to cool the charge. Real fuel flow is then up to **25 % higher**
than the naive figure. An uncorrected model would silently under-report consumption when
you drive hard — flattering precisely the behaviour the game exists to penalise. PID
`0144` gives us the commanded ratio directly.

**Fuel trims.** Even in closed loop the ECU shifts the delivered mixture a few percent.
At the observed idle it was adding 11.6 % (6.1 + 5.5) over the base map. PIDs `0106` and
`0107` give us both terms.

Implemented as `Units.mafToFuelKgPerSecCorrected`:

```
AFR   = 14.7 × lambda
fuel  = (MAF ÷ 1000) ÷ AFR × (1 + (shortTrim + longTrim) ÷ 100)
```

At the observed idle this yields **+8.6 %** over naive (trims +11.6 %, lean lambda
−2.7 %). Under WOT enrichment it yields **+25 %**. Both are pinned by tests.

---

## Air density — use the car's sensors, not the phone's

The car reports BARO (`0133`) and AAT (`0146`), which beats the phone on both counts:
no weather-drift on the barometer, and true ambient rather than cabin temperature.

Use **AAT, not IAT**, for drag: intake air is heat-soaked by the engine bay (45 °C vs
42 °C ambient here, and the gap widens after a hard run). The air the car is actually
pushing through is at ambient.

At the observed 1010 hPa / 42 °C, air density is ~1.12 kg/m³ against the 1.225 standard —
about **8 % less drag** than a standard-day calculation would predict. Small, real, and
free given the car already tells us.

The phone barometer still earns its place for *grade*: the vehicle's 1 kPa quantisation
is far too coarse (≈ 85 m per count) to resolve a hill, whereas a phone barometer
resolves sub-metre changes. So:

- **Vehicle BARO + AAT** → air density for the drag term
- **Phone barometer + GPS** → altitude changes for the grade/gravity-loss term

---

## Still outstanding

**Gear ratios: RESOLVED — they were never the problem.** The ND 6MT ratios
(5.087 / 2.991 / 2.035 / 1.594 / 1.286 / 1.000, final drive 2.866) are published Mazda
figures and the transmission is bone stock, so they are correct as written.

The real error was the **tire radius**, which was set to 0.3175 m — about 6% too large.
205/45R17 computes to 0.3081 m unloaded (17-inch rim radius 0.2159 m + 45% of a 205 mm
section), and a loaded radial squats roughly 3% below that, giving **0.2989 m**. Now
corrected.

The "2500 rpm in 6th = 75-80 mph" figure that prompted the whole investigation was
**unsourced and wrong**. The arithmetic rules it out: no plausible tire could produce it
(you would need a 75 cm diameter wheel), and reverse-solving demands a 2.27 final drive
that Mazda does not ship. With correct constants the ND is simply a busy highway cruiser:

| Speed | RPM in 6th |
|---|---|
| 60 mph | ~2456 |
| 65 mph | ~2661 |
| 70 mph | ~2865 |
| 75 mph | ~3070 |

That matches the ND's well-known reputation, and is part of why later trims and the RF
discuss taller gearing. An independent top-speed cross-check corroborates: 6th at the
7500 rpm redline would theoretically reach ~183 mph, well beyond the ~135 mph the car is
actually power-limited to — exactly the relationship a 181 hp car with tall top gear
should show. Both are now pinned by tests.

A real rpm/speed pair on the next drive would still be a welcome confirmation, but the
model is no longer resting on an unverified assumption.

**Also worth capturing while moving:**

- MAF at a steady 60 mph in 6th — validates the fuel-flow path under cruise, where the
  idle sample cannot
- Whether EQ_RAT drops below 1.0 under hard acceleration — confirms the enrichment
  correction is doing real work
- FLI behaviour through a corner — characterises the slosh the filter has to reject

---

## Consequences for the build

| Decision | Status |
|---|---|
| Fuel flow source | MAF (`0110`), mixture-corrected. **No `015E` fallback needed — it does not exist.** |
| Tank tracking | `012F` + slow complementary filter. No manual fill-up flow required. |
| Air density | Vehicle BARO + AAT. |
| Grade | Phone barometer + GPS; vehicle BARO too coarse. |
| Polling set | `010C` `010D` `0110` `0144` `0106` `0107` `012F` `0133` `0146` — nine PIDs at 5–10 Hz. |

That nine-PID poll set is the real hardware requirement: a cheap ELM327 clone managing
2–5 queries/sec cannot sustain it, which is why the dongle recommendation in the README
matters more than it might appear.
