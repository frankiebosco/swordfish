# Can the OBD Dongle and Android Auto Run at the Same Time?

**Researched 2026-08-19.**

**Short answer: yes, and on a wired-Android-Auto car like the ND2 it is not even close
to a conflict.** They use different radios entirely. This is also proven in the field —
`aa-torque` is a shipping open-source app doing exactly this, with a Bluetooth OBD dongle
feeding gauges onto the Android Auto screen.

**The real constraint turned out to be elsewhere: throughput.** Our nine-PID poll set
needs ~33 commands/sec, which sits above the low end of what Bluetooth Classic sustains
and far beyond BLE. That makes adapter quality a hard requirement and forces a tiered
polling design. See [Hardware recommendation](#hardware-recommendation--revised).

> **MEASURED 2026-08-20 — and it is worse than this document assumes.** A genuine
> OBDLink MX+ delivered **14.8 cmd/s** with zero packet loss on the ND2, below the
> 20 cmd/s figure treated here as the Bluetooth Classic floor. The limit is
> **latency** (66.7 ms per round trip), not bandwidth or packet loss, so "buy a
> better adapter" is not obviously the fix. Every throughput number below is a
> quoted range that has now been contradicted by measurement on the real hardware.
>
> **The Bluetooth/Android-Auto coexistence conclusion, however, held up perfectly:**
> the dongle stayed connected while Android Auto projected over USB, exactly as
> argued here.

---

---

## Why there is no architectural conflict

The concern was that three links have to coexist:

1. **Phone → head unit** for Android Auto projection
2. **Phone → car** for Bluetooth hands-free calling (already paired, always on)
3. **Phone → OBD dongle** for telemetry (the new one)

On a **wired** Android Auto car, link 1 is entirely over USB. Per Google's own framing,
wired Android Auto "handles almost everything via USB" — the Bluetooth radio is not
carrying projection data at all. So the only Bluetooth contention is between the car's
hands-free profile and the dongle's serial profile.

Those are **different Bluetooth profiles**, and Android handles multiple simultaneous
connections as a matter of course. Connection limits are enforced per-profile, not
globally:

| Profile | Used by | Limit |
|---|---|---|
| **HFP** (hands-free) | Car, for calls | Multiple supported |
| **A2DP** (audio) | Car, for media | **Hardcoded to 1** |
| **SPP** (serial) | OBD dongle | No documented conflict with the above |

The A2DP limit of one is worth knowing, but it does not bite us: the dongle speaks SPP,
not A2DP, so it is not competing for that slot. Bluetooth Classic supports a piconet of
one master and up to seven active slaves; we need two.

### The wireless-Android-Auto caveat

If you ever switch to **wireless** Android Auto — either a factory implementation or a
dongle like AAWireless — the picture changes. Wireless AA uses Bluetooth for the initial
handshake and then a peer-to-peer **5 GHz Wi-Fi** link for the actual projection, because
Bluetooth lacks the bandwidth for continuous video.

That does not make it impossible, but it puts three radios in play at once and adds a real
interference surface. Several Android Auto disconnection troubleshooting guides
specifically name Bluetooth device interference as a cause of dropouts.

**The ND2 is wired-only, so this is not our problem today.** Worth recording because it
would become one if the setup ever changed.

---

## Proven in the field

`aa-torque` ([agronick/aa-torque](https://github.com/agronick/aa-torque), plus several
active forks) is a performance-monitor app that reads OBD data via a Bluetooth dongle and
renders gauges on the Android Auto head unit. It requires Torque Pro as its data source
and a Bluetooth OBD2 module, and its setup is exactly ours: USB to the car for Android
Auto, Bluetooth to the dongle for data.

Its documentation does not flag Bluetooth/AA conflicts at all — the known issues are about
*installation* (Android 14 reinstall quirks, Pixel PackageInstaller problems), not
connectivity. That is a meaningful silence: if simultaneous operation were fragile, an app
whose entire premise depends on it would say so.

### One important difference from our approach

`aa-torque` installs via the Android Auto **"Unknown sources"** developer toggle — which,
per `ANDROID_AUTO_RESEARCH.md`, only works for apps that are *not* built on the Car App
Library. That tells us aa-torque uses the older/legacy Android Auto surface rather than
`CarAppService`.

**This does not change our plan.** It confirms the Bluetooth coexistence question, which
is a radio and OS-level matter independent of which drawing API we use. Our distribution
route remains Play Internal App Sharing because we *are* using the Car App Library.

---

## Interference is a real but manageable risk

Simultaneous operation is architecturally fine; signal quality is a separate question.
OBD2 troubleshooting guides do advise minimising other Bluetooth devices near the scanner,
and AA disconnection guides list Bluetooth interference among the causes.

Practical mitigations, in order of usefulness:

1. **Use a good dongle.** Cheap clones drop frames under load and will be blamed on
   "interference" when the real cause is a counterfeit chipset.
2. **Use a good USB cable.** The single most common cause of AA dropouts, unrelated to
   Bluetooth. Data-capable, short, not the charge-only one in the door pocket.
3. **Prefer Bluetooth Classic over BLE.** See below — also better for throughput.
4. **Do not add a fourth radio.** Bluetooth earbuds in the car at the same time is where
   people actually hit trouble.

---

## Hardware recommendation — revised

Earlier notes in this repo suggested a dual-mode adapter partly so the project could
migrate to iPhone later. Now that head-unit-first Android is the settled plan, throughput
is what matters, and the numbers favour **Bluetooth Classic SPP** decisively:

| Transport | Throughput | Notes |
|---|---|---|
| **Bluetooth Classic (SPP)** | **20–50 cmd/sec** | Fastest. Recommended for real-time logging. |
| WiFi | 10–25 cmd/sec | Occupies the phone's WiFi — no internet while driving. |
| BLE | 5–15 cmd/sec | Slowest; patchy compatibility; brand-sensitive. |

**Our poll set is nine PIDs at 5–10 Hz — call it 45–90 commands/sec.** That is above even
Bluetooth Classic's quoted ceiling, which means:

- **BLE is ruled out.** At 5–15 cmd/sec it cannot come close.
- **WiFi is ruled out.** Losing phone internet while Android Auto runs is unacceptable.
- **Bluetooth Classic SPP is the only viable transport**, and we will still need to be
  smart about it.

### Consequence for the design: tiered polling

We cannot poll everything at 10 Hz. The nine PIDs should be split by how fast they
actually change:

| Rate | PIDs | Why |
|---|---|---|
| **~10 Hz** | `010C` RPM, `010D` speed, `0110` MAF | Drive the live gauge and Isp |
| **~1 Hz** | `0144` lambda, `0106`/`0107` trims | Mixture shifts slowly |
| **~0.1 Hz** | `012F` fuel level, `0133` baro, `0146` ambient temp | Near-static |

That works out to 30 + 3 + 0.3 = **33.3 commands/sec**.

**Be honest about what that means.** Bluetooth Classic is quoted at 20–50 cmd/sec, so
33 cmd/s sits *above the low end of that range*. On a mediocre adapter we would stall; on
a good one we have about 33% headroom. This is not comfortable, and the naive alternatives
are worse — all nine PIDs at 10 Hz is 90 cmd/s, and even 5 Hz across the board is 45 cmd/s
with no margin for retries.

Consequences, and they are firm rather than advisory:

- **A genuine fast adapter is a hard requirement, not a nice-to-have.** A clone at the
  bottom of the range cannot run this poll set. This is the single most important
  purchasing decision in the project.
- **The fast tier is the budget.** Adding a fourth PID at 10 Hz costs another 10 cmd/s and
  should be resisted. If the gauge needs more inputs, derive them rather than poll them.
- **Measure before tuning.** The transport layer should track actual achieved poll rate
  and surface it in the phone-side debug view. If a real adapter turns out to sustain
  more, the fast tier can rise; if it sustains less, drop MAF to 5 Hz and interpolate.
- **The fast tier degrades gracefully.** RPM and speed drive the gauge; MAF drives Isp.
  If throughput collapses, halving MAF's rate and holding the last value is far better
  than a stuttering display.

The slower tiers lose nothing: tank level is slosh-filtered over minutes anyway, ambient
temperature does not change in a second, and fuel trims move over tens of seconds.

This tiering must be built into the transport layer from the start, not retrofitted when
the gauge stutters.

---

## Recommended adapter

**Bluetooth Classic (or dual-mode used in Classic mode), genuine chipset.**

- **vLinker MC+** (~$35) — dual-mode, fast chipset, well regarded for Android
- **OBDLink LX / MX+** (~$60–110) — STN chipset, the quality benchmark, best sustained
  polling and excellent Mazda support
- **Avoid** generic $8 "ELM327 v2.1" clones — counterfeit chips, fake version strings,
  dropouts that will be misdiagnosed as interference for a week

**Recommendation: buy the OBDLink LX.** (Full LX vs MX+ analysis in the next section.) The arithmetic above changed my advice. When the
plan was a relaxed phone-screen readout, the vLinker was the sensible value pick. Now that
the poll budget (33 cmd/s) sits above the *low end* of Bluetooth Classic's range, adapter
quality is the difference between a working instrument and a stuttering one. The STN
chipset's sustained throughput is exactly what we are short of, and paying ~$25 more once
removes a class of debugging that would otherwise be blamed on interference, on the phone,
or on our own code.

If the budget is firm, the vLinker MC+ is still worth trying — it is a genuine fast
chipset and may well sustain the poll set. Just measure the achieved rate early rather
than assuming.

---

## Verdict

No wall. Wired Android Auto over USB, Bluetooth Classic SPP to the dongle, hands-free
still working on its own profile. Build the tiered poller and buy a real adapter.

---

## Sources

- [Wireless Android Auto needs both Bluetooth and Wi-Fi](https://www.howtogeek.com/android-auto-needs-both-bluetooth-and-wi-fi-and-heres-why/)
- [Why wireless Android Auto uses both Bluetooth and Wi-Fi — Engadget](https://www.engadget.com/2210415/why-wireless-android-auto-uses-both-bluetooth-wifi/)
- [aa-torque — Performance Monitor for cars with Android Auto](https://github.com/agronick/aa-torque)
- [Torque Pro Android Auto setup guide](https://blog.aahacks.com/torque-pro/)
- [OBD adapter WiFi vs Bluetooth Classic vs BLE](https://obdllm.com/en/blog/obd-adapter-wifi-vs-bluetooth-vs-ble/)
- [Best OBD2 adapter for Android: Classic vs BLE](https://steer.so/blog/best-obd2-adapter-android/)
- [Bluetooth — Android Open Source Project](https://source.android.com/docs/automotive/ivi_connectivity)
- [Dual-mode Bluetooth: Classic/BLE coexistence](https://www.ezurio.com/resources/blog/dual-mode-bluetooth-classic-ble-coexistence)
- [Android Auto keeps disconnecting — troubleshooting](https://www.androidauthority.com/android-auto-keeps-disconnecting-3390002/)


---

## OBDLink LX vs MX+ - which one?

> **SUPERSEDED 2026-08-20 — the MX+ was bought and MS-CAN was CONFIRMED on this
> car:** 227 frames across 18 arbitration IDs via `STP 53`. The analysis below
> concluded the MX+ "adds nothing Swordfish can use", on the reasoning that Mazda
> MS-CAN reaches only body modules and that the 2023 ND2 was outside documented
> coverage. The first half is still unproven either way; the second half was
> **wrong** — the coverage PDF is dated 12.2019 and simply predates the car.
>
> The section is kept rather than deleted because its reasoning about
> *throughput* remains the live concern, and because the premise that changed
> (economy-only vs. traction/attitude in scope) is worth seeing. See
> `MX_PLUS_PROBE_PLAN.md` for the resolved decision record.
>
> **Ironically the throughput argument also did not survive contact:** the MX+
> measured **14.8 cmd/s**, below the Bluetooth Classic floor this document
> treats as a given. Adapter choice was never the binding constraint it was
> assumed to be.

Researched 2026-08-19 against the manufacturer spec pages and their
`OEM-Specific Enhanced Diagnostics Support Coverage` PDF (rev 12.2019).

**Verdict: buy the LX ($89.95). The MX+ ($139.95) adds nothing Swordfish can use.**

### They are the same adapter for our purposes

| | OBDLink LX | OBDLink MX+ |
|---|---|---|
| Price | **$89.95** | $139.95 |
| Bluetooth | Class 2 **v3.0** (Classic) | Class 2 **v3.0** (Classic) |
| Chipset | STN | STN |
| Sampling rate | "up to 4x competing adapters" | *no different figure published* |
| All legislated OBD-II protocols | yes | yes |
| MS-CAN / SW-CAN | no | **yes** |
| iOS support | no | **yes** |
| OEM add-ons in OBDLink app | some | all, free |

**Critically, neither vendor page claims a throughput advantage for the MX+.** Both are
Bluetooth 3.0 Classic on the same STN chipset, and the "up to 4 times as many samples per
second as the closest competitor" claim attaches to the LX too. Since throughput is our
binding constraint, the extra $50 buys nothing on the metric we actually care about.

### The two real MX+ features, and why neither helps

**1. iOS support.** Irrelevant. The project is head-unit-first on Android, and that is
settled. The LX being Android-only costs us nothing. (This was the reason earlier notes
leaned toward a dual-mode adapter - that reasoning is now obsolete.)

**2. MS-CAN / SW-CAN access.** This one deserved a proper look, because it is usually
described as a "Ford and GM" feature - but **Mazda genuinely does have an MS-CAN bus**
(shared platform history with Ford), and the coverage PDF lists Mazda with checkmarks in
all four columns including **OEM Live Parameters**. Encouraging.

It still does not help us, for three independent reasons:

- **Coverage stops before your car.** The PDF lists `Mazda MX-5 ND 2017-2018` and
  `Mazda MX-5 Miata 2.0L PE 2016`. The **2023** ND2 is not in it. The document is dated
  12.2019, so it could not be - but that means MS-CAN support for this specific car is
  unverified, not merely undocumented.
- **The modules are body electronics, not powertrain telemetry.** The Mazda supported
  module list is ABS, airbags, BCM, blind-spot monitoring, parking aid, TPMS, power
  steering, retractable hard top. Useful for diagnosing a fault; irrelevant to fuel flow.
  Everything Swordfish needs - RPM, speed, MAF, lambda, trims, tank level - is **generic
  OBD-II on the standard HS-CAN bus**, which the LX reads perfectly.
- **We already surveyed the car and found what we need.** The 34-PID enumeration in
  `VEHICLE_SURVEY.md` is the definitive statement of what this ND2 exposes. Nothing on
  that list requires MS-CAN.

### Could OEM live parameters give us better data?

The genuinely interesting question, since Mazda does show an **OEM Live Parameters**
checkmark, and a manufacturer-specific fuel-flow parameter would beat our MAF-derived
estimate.

**Not worth $50 on speculation.** Enhanced PIDs are accessible to third-party apps (Torque
and others read them through OBDLink adapters), so it is not a walled garden - but:

- No evidence any Mazda OEM PID provides direct fuel flow, and the ND2 conspicuously
  omits the *standard* one (`015E`)
- The 2023 ND2 is outside documented coverage entirely
- Our mixture-corrected MAF path is already validated against the car (0.202 gal/h at
  idle, corroborated by a speed-density cross-check)

If someone later discovers a Mazda-specific fuel-rate PID for the ND2, upgrading the
adapter is a $140 decision made with evidence rather than a $50 gamble made without.

### Buy the LX

Same speed, same chipset, same Bluetooth Classic SPP, same generic OBD-II coverage - for
$50 less. Put the saving toward the $25 Play Console account and a decent USB cable, which
between them will do more for reliability than the MX+ would.
