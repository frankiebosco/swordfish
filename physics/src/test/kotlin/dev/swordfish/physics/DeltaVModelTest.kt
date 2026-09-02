package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeltaVModelTest {

    private val car = Vehicle.ND2_CLUB
    private val fullTankKg = Units.gallonsToKg(11.9)

    /** Fuel flow (kg/s) implied by a given speed and fuel economy. */
    private fun flowForMpg(speedMps: Double, mpg: Double): Double {
        val milesPerSec = Units.metersToMiles(speedMps)
        val galPerSec = milesPerSec / mpg
        return Units.gallonsToKg(galPerSec)
    }

    // --- Unit conversion sanity ---

    @Test
    fun `gallon of gasoline weighs about 6_17 pounds`() {
        assertEquals(6.17, Units.kgToLb(Units.gallonsToKg(1.0)), 0.01)
    }

    @Test
    fun `mph and mps round trip`() {
        assertEquals(60.0, Units.mpsToMph(Units.mphToMps(60.0)), 1e-9)
    }

    @Test
    fun `60 mph is about 26_8 meters per second`() {
        assertEquals(26.82, Units.mphToMps(60.0), 0.01)
    }

    // --- Vehicle mass bookkeeping ---

    @Test
    fun `dry mass excludes fuel so curb weight is recovered when full`() {
        // Published curb weight includes a full tank; adding fuel back to our
        // dry figure must reproduce it. Guards the double-count bug.
        //
        // 2381 lb is the soft-top Club with the full BBS/Brembo/Recaro
        // package, not the 2341 lb base Club. The default payload is one
        // standard adult (180 lb).
        val curbWithDriverKg = car.totalMassKg(fullTankKg)
        val expectedLb = Vehicle.ND2_CLUB_BRE_CURB_LB + 180.0
        assertEquals(expectedLb, Units.kgToLb(curbWithDriverKg), 1.0)
    }

    @Test
    fun `mass ratio for a full tank is small`() {
        // The honest caveat, pinned as a test: a Miata is a poor rocket.
        val ratio = car.totalMassKg(fullTankKg) / car.structuralMassKg
        assertTrue(ratio in 1.02..1.05, "expected ~1.03, got $ratio")
    }

    // --- Road load ---

    @Test
    fun `aero drag scales with square of speed`() {
        val at30 = DeltaVModel.aeroDragNewtons(car, Units.mphToMps(30.0))
        val at60 = DeltaVModel.aeroDragNewtons(car, Units.mphToMps(60.0))
        assertEquals(4.0, at60 / at30, 0.01)
    }

    @Test
    fun `road load at 60 mph is in a plausible range for a Miata`() {
        // An ND at a steady 60 mph on the flat needs roughly 10-15 hp at the
        // wheels, which is about 300-500 N of tractive force. If this test
        // fails, Cd/A/Crr have drifted somewhere unphysical.
        val mass = car.totalMassKg(fullTankKg)
        val load = DeltaVModel.roadLoadNewtons(car, mass, Units.mphToMps(60.0), 0.0)
        assertTrue(load in 300.0..500.0, "road load at 60 mph = $load N")
    }

    @Test
    fun `grade force is zero on the flat and positive uphill`() {
        val mass = car.totalMassKg(fullTankKg)
        assertEquals(0.0, DeltaVModel.gradeForceNewtons(mass, 0.0), 1e-9)
        assertTrue(DeltaVModel.gradeForceNewtons(mass, 0.05) > 0.0)
        assertTrue(DeltaVModel.gradeForceNewtons(mass, -0.05) < 0.0)
    }

    @Test
    fun `a 6 percent grade adds roughly 6 percent of vehicle weight`() {
        val mass = car.totalMassKg(fullTankKg)
        val grade = 0.06
        val f = DeltaVModel.gradeForceNewtons(mass, kotlin.math.asin(grade))
        assertEquals(mass * Units.G0 * grade, f, 1.0)
    }

    // --- Effective Isp: the hero stat ---

    @Test
    fun `isp is higher when cruising efficiently than when driving hard`() {
        // The core gamification claim, pinned as a test. Same speed, same
        // road load -- the only difference is how much fuel you burn.
        val speed = Units.mphToMps(60.0)
        val mass = car.totalMassKg(fullTankKg)
        val load = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)

        val efficientIsp = DeltaVModel.effectiveIsp(load, flowForMpg(speed, 38.0), speed)
        val thirstyIsp = DeltaVModel.effectiveIsp(load, flowForMpg(speed, 18.0), speed)

        assertTrue(efficientIsp > thirstyIsp)
        // Isp is inversely proportional to flow at fixed load, so the ratio
        // should mirror the MPG ratio.
        assertEquals(38.0 / 18.0, efficientIsp / thirstyIsp, 0.01)
    }

    @Test
    fun `isp is zero when stationary`() {
        // Idling burns fuel while doing no useful work: undefined, not huge.
        val isp = DeltaVModel.effectiveIsp(400.0, flowForMpg(1.0, 30.0), 0.0)
        assertEquals(0.0, isp, 1e-9)
    }

    @Test
    fun `isp is zero during deceleration fuel cutoff`() {
        // Zero flow would divide to infinity; we report zero and flag DFCO.
        val isp = DeltaVModel.effectiveIsp(400.0, 0.0, Units.mphToMps(50.0))
        assertEquals(0.0, isp, 1e-9)
    }

    @Test
    fun `isp is zero when road load goes negative on a steep descent`() {
        val isp = DeltaVModel.effectiveIsp(-120.0, flowForMpg(20.0, 40.0), 20.0)
        assertEquals(0.0, isp, 1e-9)
    }

    @Test
    fun `isp magnitude lands in a sane band for highway cruise`() {
        // A car's effective Isp is ENORMOUS next to a chemical rocket's
        // ~300 s, and that is not a bug. A rocket must carry its oxidiser and
        // throw reaction mass overboard at kilometres per second; a car takes
        // its oxygen from the atmosphere for free and pushes against the
        // planet. Per unit of fuel burned, the car overcomes vastly more
        // force-seconds. Expect roughly 20,000-60,000 s at highway cruise.
        //
        // This is worth surfacing in the UI: an ND2 in 6th scores about 100x
        // a Saturn V, which is a genuinely fun fact rather than a rounding
        // error.
        val speed = Units.mphToMps(60.0)
        val mass = car.totalMassKg(fullTankKg)
        val load = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)
        val isp = DeltaVModel.effectiveIsp(load, flowForMpg(speed, 35.0), speed)
        assertTrue(isp in 15_000.0..60_000.0, "highway cruise Isp = $isp s")
    }

    @Test
    fun `car isp dwarfs a chemical rocket`() {
        // Pins the comparison the UI will draw on. RS-25 vacuum Isp is 452 s.
        val speed = Units.mphToMps(60.0)
        val mass = car.totalMassKg(fullTankKg)
        val load = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)
        val isp = DeltaVModel.effectiveIsp(load, flowForMpg(speed, 35.0), speed)
        assertTrue(isp > 452.0 * 10, "car should beat an RS-25 by an order of magnitude")
    }

    // --- Delta-V ---

    @Test
    fun `tsiolkovsky delta v is zero with an empty tank`() {
        assertEquals(0.0, DeltaVModel.tsiolkovskyDeltaV(car, 0.0, 80.0), 1e-9)
    }

    @Test
    fun `delta v increases with fuel and with isp`() {
        val dvHalf = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg / 2, 80.0)
        val dvFull = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 80.0)
        assertTrue(dvFull > dvHalf)

        val dvBetterIsp = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 120.0)
        assertTrue(dvBetterIsp > dvFull)
    }

    @Test
    fun `tsiolkovsky and linearised range forms agree closely for a car`() {
        // Because the mass ratio is ~1.03, ln(m0/mf) ~ fuel/m_total to
        // within a percent or so. This test documents *why* the linearised
        // "range" readout is legitimate rather than a fudge.
        val isp = 90.0
        val tsiol = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, isp)
        val linear = DeltaVModel.rangeEquivalentDeltaV(car, fullTankKg, isp)
        val relErr = abs(tsiol - linear) / tsiol
        assertTrue(relErr < 0.02, "forms diverge by ${relErr * 100}%")
    }

    @Test
    fun `delta v is nearly proportional to fuel remaining`() {
        // The honest caveat again: halving fuel should roughly halve delta-V.
        val isp = 90.0
        val full = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, isp)
        val half = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg / 2, isp)
        assertEquals(2.0, full / half, 0.05)
    }

    // --- Gravity losses ---

    @Test
    fun `gravity loss is positive climbing and negative descending`() {
        val mass = car.totalMassKg(fullTankKg)
        val speed = Units.mphToMps(45.0)
        assertTrue(DeltaVModel.gravityLossWatts(mass, speed, 0.05) > 0.0)
        assertTrue(DeltaVModel.gravityLossWatts(mass, speed, -0.05) < 0.0)
        assertEquals(0.0, DeltaVModel.gravityLossWatts(mass, speed, 0.0), 1e-9)
    }

    @Test
    fun `climbing reduces isp relative to the same speed on the flat`() {
        // Same fuel flow, more road load fought... but the fuel is buying
        // altitude. Isp actually RISES because road load is the numerator.
        // This documents a subtlety worth keeping straight: on a climb the
        // engine must burn MORE fuel to hold speed, and it is that increased
        // flow -- not the grade term itself -- that drops real-world Isp.
        val speed = Units.mphToMps(50.0)
        val mass = car.totalMassKg(fullTankKg)

        val flatLoad = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)
        val climbLoad = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.06)
        assertTrue(climbLoad > flatLoad)

        // Holding speed uphill needs proportionally more fuel.
        val flatFlow = flowForMpg(speed, 35.0)
        val climbFlow = flatFlow * (climbLoad / flatLoad)

        val flatIsp = DeltaVModel.effectiveIsp(flatLoad, flatFlow, speed)
        val climbIsp = DeltaVModel.effectiveIsp(climbLoad, climbFlow, speed)
        assertEquals(flatIsp, climbIsp, 1.0)
    }

    // --- Full model integration ---

    @Test
    fun `compute produces a coherent readout for a highway cruise`() {
        val speed = Units.mphToMps(65.0)
        val t = Telemetry(
            speedMps = speed,
            rpm = 2400.0,
            fuelFlowKgPerSec = flowForMpg(speed, 34.0),
            fuelRemainingKg = fullTankKg * 0.75,
            gradeRadians = 0.0
        )
        val r = DeltaVModel.compute(car, t)

        assertTrue(r.effectiveIsp > 0.0)
        assertTrue(r.deltaVRemaining > 0.0)
        assertTrue(r.rangeEquivalentDeltaV > 0.0)
        assertTrue(r.roadLoadNewtons > 0.0)
        assertEquals(0.0, r.gravityLossWatts, 1e-6)
        assertTrue(!r.inDeceleratingFuelCutoff)
    }

    @Test
    fun `compute flags deceleration fuel cutoff while coasting`() {
        val t = Telemetry(
            speedMps = Units.mphToMps(50.0),
            rpm = 2000.0,
            fuelFlowKgPerSec = 0.0,
            fuelRemainingKg = fullTankKg * 0.5
        )
        val r = DeltaVModel.compute(car, t)
        assertTrue(r.inDeceleratingFuelCutoff)
        assertEquals(0.0, r.effectiveIsp, 1e-9)
    }

    @Test
    fun `compute survives missing fuel flow without throwing`() {
        val t = Telemetry(
            speedMps = Units.mphToMps(40.0),
            rpm = 1800.0,
            fuelFlowKgPerSec = null,
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        assertEquals(0.0, r.effectiveIsp, 1e-9)
        // The budget falls back to the reference cruise efficiency rather
        // than collapsing: the tank is still full, we just cannot measure
        // efficiency without a fuel-flow reading.
        assertTrue(r.deltaVRemaining > 1000.0)
    }

    // --- Instantaneous MPG cross-check ---

    @Test
    fun `instantaneous mpg round trips through the flow helper`() {
        val speed = Units.mphToMps(55.0)
        val flow = flowForMpg(speed, 32.0)
        assertEquals(32.0, DeltaVModel.instantaneousMpg(speed, flow), 0.01)
    }

    // --- Gear inference ---

    @Test
    fun `infers sixth gear at a relaxed highway cruise`() {
        // 65 mph in 6th predicts ~2661 rpm with the published ND ratios and
        // the corrected 0.2989 m loaded radius.
        //
        // This IS the ND's real gearing -- it is a famously busy highway
        // cruiser, which is why later trims and the RF discuss taller final
        // drives. An earlier version of this test chased a recalled
        // "2500 rpm = 75-80 mph" figure that no plausible tire or final
        // drive can produce; see the top-speed cross-check below.
        val speed = Units.mphToMps(65.0)
        val gear = DeltaVModel.inferGear(car, speed, 2661.0)
        assertEquals(6, gear)
    }

    @Test
    fun `sixth gear cruise rpm matches the ND reputation for busy gearing`() {
        // Pins the headline numbers so a future constant change is caught.
        fun rpmInSixth(mph: Double): Double {
            val v = Units.mphToMps(mph)
            val wheelRevPerSec = v / (2.0 * Math.PI * car.tireRadiusM)
            return wheelRevPerSec * car.finalDrive * car.gearRatios[5] * 60.0
        }
        assertEquals(2456.0, rpmInSixth(60.0), 15.0)
        assertEquals(2661.0, rpmInSixth(65.0), 15.0)
        assertEquals(2865.0, rpmInSixth(70.0), 15.0)
    }

    @Test
    fun `gearing is consistent with the published top speed`() {
        // Independent cross-check on the ratio/radius pair. The ND2 is
        // power-limited around 135 mph, well short of what 6th could pull at
        // its 7500 rpm redline -- exactly what a 181 hp car with tall top
        // gear should show. If a constant drifts badly, this breaks.
        val wheelRevPerSec = 7500.0 / 60.0 / car.finalDrive / car.gearRatios[5]
        val topSpeedMph = Units.mpsToMph(wheelRevPerSec * 2.0 * Math.PI * car.tireRadiusM)
        assertTrue(topSpeedMph > 135.0,
            "6th at redline ($topSpeedMph mph) must exceed the 135 mph limit")
        assertTrue(topSpeedMph < 220.0, "but not absurdly: got $topSpeedMph mph")
    }

    @Test
    fun `loaded tire radius is smaller than the unloaded spec radius`() {
        // 205/45R17: 17in rim radius + 45% of 205mm sidewall = 0.3081 m
        // unloaded. A loaded radial squats a few percent below that. Using
        // the unloaded figure overstates speed per revolution.
        val rimRadius = 17.0 * 0.0254 / 2.0
        val sidewall = 205.0 * 0.45 / 1000.0
        val unloaded = rimRadius + sidewall
        assertEquals(0.3081, unloaded, 0.001)
        assertTrue(car.tireRadiusM < unloaded, "loaded radius must be smaller")
        assertTrue(car.tireRadiusM > unloaded * 0.94, "but only by a few percent")
    }

    @Test
    fun `infers a low gear when revs are high for the speed`() {
        // 20 mph in 1st predicts ~4165 rpm with the corrected radius.
        val gear = DeltaVModel.inferGear(car, Units.mphToMps(20.0), 4165.0)
        assertNotNull(gear)
        assertTrue(gear <= 2, "expected 1st or 2nd, got $gear")
    }

    @Test
    fun `returns null for an rpm that matches no gear`() {
        // Between ratios -- clutch slipping, or mid-shift. Reporting the
        // nearest gear here would flicker the display; null is honest.
        assertNull(DeltaVModel.inferGear(car, Units.mphToMps(20.0), 3300.0))
    }

    @Test
    fun `each gear is recovered from its own predicted rpm`() {
        // Round-trip every ratio: synthesise the rpm the model expects for a
        // given speed in gear N, then check inference returns N.
        val speed = Units.mphToMps(45.0)
        val wheelRevPerSec = speed / (2.0 * Math.PI * car.tireRadiusM)
        car.gearRatios.forEachIndexed { i, ratio ->
            val rpm = wheelRevPerSec * car.finalDrive * ratio * 60.0
            if (rpm in 800.0..7500.0) {
                assertEquals(i + 1, DeltaVModel.inferGear(car, speed, rpm),
                    "gear ${i + 1} at $rpm rpm")
            }
        }
    }

    @Test
    fun `gear inference returns null when stopped or clutched in`() {
        assertNull(DeltaVModel.inferGear(car, 0.0, 800.0))
        // Clutch in at speed: revs drop to idle, matching no gear.
        assertNull(DeltaVModel.inferGear(car, Units.mphToMps(60.0), 800.0))
    }

    // --- Regressions from the 2026-08-21 drive (1857 real samples) ---

    @Test
    fun `crawling in traffic does not produce an absurd delta-V`() {
        // THE bug from that drive: 11% of samples exceeded 20,000 m/s and
        // one hit 287,452 m/s at 1.9 m/s road speed.
        //
        // Mechanism: Isp = F/(mdot*g0). At walking pace the numerator is
        // floor-bound by rolling resistance (~330 N, barely speed
        // dependent) while the denominator collapses as the injectors
        // close. Division blowup, not physics.
        val t = Telemetry(
            speedMps = 1.9,
            rpm = 792.0,
            fuelFlowKgPerSec = Units.gallonsToKg(0.10 / 3600.0),
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        assertEquals(0.0, r.effectiveIsp, 1e-9)
        assertTrue(
            r.deltaVRemaining < 20000.0,
            "delta-V blew up again: ${r.deltaVRemaining}"
        )
    }

    @Test
    fun `the budget survives every degenerate state`() {
        // Idle, crawl and no-fuel-reading all used to zero the budget. The
        // tank is unchanged in all three; only the engine state differs.
        val states = listOf(
            Telemetry(0.0, 784.0, Units.gallonsToKg(0.2 / 3600.0), fullTankKg),
            Telemetry(1.9, 792.0, Units.gallonsToKg(0.1 / 3600.0), fullTankKg),
            Telemetry(Units.mphToMps(40.0), 1800.0, null, fullTankKg)
        )
        for (t in states) {
            val r = DeltaVModel.compute(car, t)
            assertTrue(
                r.deltaVRemaining > 1000.0,
                "budget collapsed at speed ${t.speedMps}: ${r.deltaVRemaining}"
            )
        }
    }

    @Test
    fun `a real cruise sample still reports a genuine Isp`() {
        // The floor must not swallow normal driving. Taken from the same
        // drive: 27 m/s, 2000 rpm, 8 L/h.
        val t = Telemetry(
            speedMps = 27.0,
            rpm = 2000.0,
            fuelFlowKgPerSec = Units.gallonsToKg((8.0 / 3.785) / 3600.0),
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        assertTrue(r.effectiveIsp > 1000.0, "cruise Isp was suppressed")
        assertTrue(
            r.effectiveIsp < 200000.0,
            "cruise Isp implausibly high: ${r.effectiveIsp}"
        )
    }

    @Test
    fun `the reference cruise Isp matches what the car actually achieves`() {
        // Median across 815 genuine cruise samples on the 2026-08-21 drive
        // was 39,409 s. The constant is deliberately below that so the
        // budget under-promises rather than over-promises.
        assertTrue(DeltaVModel.REFERENCE_CRUISE_ISP in 20000.0..45000.0)
        assertTrue(DeltaVModel.REFERENCE_CRUISE_ISP < 39409.0)
    }


    // --- Coasting and efficiency ceiling (2026-08-21 evening drive) ---

    @Test
    fun `coasting at speed is not reported as efficiency`() {
        // From the drive: 17.8 m/s at 0.58 L/h -- BELOW the engine's idle
        // burn while doing 40 mph. That is the ECU shutting injectors on a
        // trailing throttle, not frugality. 78% of the remaining spikes had
        // fuel under 1 L/h.
        val t = Telemetry(
            speedMps = 17.8,
            rpm = 1800.0,
            fuelFlowKgPerSec = Units.gallonsToKg(0.154 / 3600.0),
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        assertEquals(0.0, r.effectiveIsp, 1e-9)
        assertTrue(r.inDeceleratingFuelCutoff, "partial cutoff should read as DFCO")
    }

    @Test
    fun `normal cruise is untouched by the coasting rule`() {
        // The threshold must not swallow real driving. 3.7 L/h was the
        // median for normal samples on that drive.
        val t = Telemetry(
            speedMps = 27.0,
            rpm = 2200.0,
            fuelFlowKgPerSec = Units.gallonsToKg((3.7 / 3.785) / 3600.0),
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        assertTrue(r.effectiveIsp > 1000.0, "normal cruise was suppressed")
        assertFalse(r.inDeceleratingFuelCutoff)
    }

    @Test
    fun `impossible efficiency is rejected`() {
        // The ceiling is on ETA, not Isp, because Isp legitimately rises as
        // speed falls -- at 5 m/s even 35% efficiency implies 309,684 s.
        // A naturally aspirated petrol engine cannot exceed ~38%.
        val t = Telemetry(
            speedMps = 20.0,
            rpm = 2000.0,
            // Absurdly little fuel for this road load.
            fuelFlowKgPerSec = 0.0002,
            fuelRemainingKg = fullTankKg
        )
        val r = DeltaVModel.compute(car, t)
        val eta = Thermodynamics.thermalEfficiency(
            r.roadLoadNewtons, t.speedMps, t.fuelFlowKgPerSec!!
        )
        if (eta > DeltaVModel.MAX_PLAUSIBLE_EFFICIENCY) {
            assertEquals(0.0, r.effectiveIsp, 1e-9)
        }
    }

    @Test
    fun `the efficiency ceiling sits above what the car really achieves`() {
        // The ND2 measured ~21% in cruise. A ceiling below that would
        // reject the car's own normal operation.
        assertTrue(DeltaVModel.MAX_PLAUSIBLE_EFFICIENCY > 0.38)
        assertTrue(DeltaVModel.MAX_PLAUSIBLE_EFFICIENCY < 0.60)
    }

    // --- The budget drains, it never refills ---
    //
    // The reference instrument is a KSP jet on the runway: it shows a
    // maximum that only ever goes DOWN. Throttle sets the rate -- afterburner
    // drains it fast, idle slowly -- but the remaining total never climbs
    // back up.
    //
    // Swordfish did not behave that way. `budgetIsp` fell back to the
    // reference ONLY when instantaneous Isp was exactly zero, so the rest of
    // the time the budget tracked the right foot. On the 65-minute
    // 2026-08-22 drive delta-V spanned 2..41,492 m/s and INCREASED on 34% of
    // steps -- a fuel budget that refilled itself 1,280 times.

    @Test
    fun `the budget ignores throttle and follows only fuel`() {
        // Same fuel, wildly different driving. The budget must not move.
        val v = Vehicle.ND2_CLUB
        val fuel = 20.0

        val gentle = DeltaVModel.compute(
            v, Telemetry(speedMps = 20.0, rpm = 2200.0, fuelFlowKgPerSec = 0.0004,
                fuelRemainingKg = fuel)
        )
        val hard = DeltaVModel.compute(
            v, Telemetry(speedMps = 20.0, rpm = 4200.0, fuelFlowKgPerSec = 0.0040,
                fuelRemainingKg = fuel)
        )
        val idle = DeltaVModel.compute(
            v, Telemetry(speedMps = 0.0, rpm = 780.0, fuelFlowKgPerSec = 0.00008,
                fuelRemainingKg = fuel)
        )

        assertEquals(gentle.deltaVRemaining, hard.deltaVRemaining, 1e-6,
            "throttle changed the remaining budget")
        assertEquals(gentle.deltaVRemaining, idle.deltaVRemaining, 1e-6,
            "idling changed the remaining budget")

        // ...while the hero stat DOES respond. That is where throttle belongs.
        assertTrue(gentle.effectiveIsp > hard.effectiveIsp,
            "Isp should reward the gentler right foot")
    }

    @Test
    fun `less fuel always means less budget`() {
        // The only thing that may move the budget is the tank.
        val v = Vehicle.ND2_CLUB
        var previous = Double.MAX_VALUE
        var fuel = 30.0
        while (fuel >= 1.0) {
            val r = DeltaVModel.compute(
                v,
                // Throttle deliberately varied as the tank drains, to prove
                // it cannot perturb the monotonic fall.
                Telemetry(
                    speedMps = 5.0 + (fuel * 1.7) % 25.0,
                    rpm = 1500.0 + (fuel * 91.0) % 3000.0,
                    fuelFlowKgPerSec = 0.0002 + (fuel % 5.0) * 0.0008,
                    fuelRemainingKg = fuel
                )
            )
            assertTrue(
                r.deltaVRemaining < previous,
                "budget rose from $previous to ${r.deltaVRemaining} at $fuel kg"
            )
            previous = r.deltaVRemaining
            fuel -= 0.5
        }
    }

    @Test
    fun `a full tank shows a stable maximum whatever the engine is doing`() {
        // The runway case: engine off, idling, or pulling hard, a full tank
        // reads the same maximum. That figure is the thing the whole gauge
        // counts down from.
        val v = Vehicle.ND2_CLUB
        val full = Units.gallonsToKg(Units.litersToGallons(v.tankCapacityL))
        val states = listOf(
            Telemetry(speedMps = 0.0, rpm = 0.0, fuelFlowKgPerSec = 0.0,
                fuelRemainingKg = full),
            Telemetry(speedMps = 0.0, rpm = 780.0, fuelFlowKgPerSec = 0.00008,
                fuelRemainingKg = full),
            Telemetry(speedMps = 30.0, rpm = 3600.0, fuelFlowKgPerSec = 0.0050,
                fuelRemainingKg = full)
        )
        val budgets = states.map { DeltaVModel.compute(v, it).deltaVRemaining }
        budgets.forEach {
            assertEquals(budgets.first(), it, 1e-6, "the maximum moved: $budgets")
        }
        assertTrue(budgets.first() > 0.0, "a full tank must show a budget")
    }
}
