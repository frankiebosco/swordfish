package dev.swordfish.physics

/**
 * Tractive force and thrust-to-weight ratio — the KSP numbers everyone checks
 * before launch, computed for a car.
 *
 * ## Why TWR is worth showing
 *
 * TWR = F / (m·g) is the first number a KSP player looks at, because below 1.0
 * a rocket simply does not leave the pad. A car is always below 1.0 — an ND2
 * manages about 0.77 in first gear and 0.15 in sixth — which is a fun and
 * physically honest way of saying *this vehicle could not hover*.
 *
 * It also does something useful: because tractive force falls with each
 * upshift, TWR drops as you move to a taller gear. The readout therefore moves
 * in the opposite direction to [DeltaVModel.effectiveIsp], which rises in tall
 * gears. That is exactly the real trade — acceleration versus efficiency — and
 * showing both side by side makes it legible.
 */
object Thrust {

    /**
     * Peak engine torque in newton-metres.
     *
     * SkyActiv-G 2.0 (PE-VPS) in the ND2: 151 lb-ft at 4000 rpm.
     */
    const val ND2_PEAK_TORQUE_NM = 151.0 * 1.35582

    /** Engine speed at which peak torque occurs. */
    const val ND2_PEAK_TORQUE_RPM = 4000.0

    /** ND2 redline. */
    const val ND2_REDLINE_RPM = 7500.0

    /**
     * Combined drivetrain efficiency, crank to contact patch.
     *
     * ~15% loss is typical for a longitudinal RWD manual. This is a constant
     * rather than a model because the precision does not matter for a display
     * figure, and pretending otherwise would be false rigour.
     */
    const val DRIVETRAIN_EFFICIENCY = 0.85

    /**
     * Normalised torque curve: fraction of peak torque available at a given
     * engine speed.
     *
     * A crude but well-shaped approximation of a naturally aspirated
     * four-cylinder — torque climbs off idle, peaks broadly, then tails off
     * toward the redline as breathing runs out.
     *
     * This is deliberately not a lookup table of dyno figures. The readout is
     * a game element; what matters is that it responds correctly in direction
     * and lands in the right ballpark. A test pins the shape.
     */
    fun torqueFraction(rpm: Double): Double {
        if (rpm <= 0.0) return 0.0
        val x = rpm / ND2_PEAK_TORQUE_RPM
        // Inverted parabola centred on peak torque, floored so the engine
        // still makes something at idle and near the redline.
        val f = 1.0 - 0.30 * (x - 1.0) * (x - 1.0)
        return f.coerceIn(0.15, 1.0)
    }

    /**
     * Engine torque (Nm) currently available at a given engine speed and
     * throttle opening.
     *
     * @param throttleFraction 0.0-1.0. Null assumes wide open, i.e. the
     *   *available* torque rather than the torque actually being made — which
     *   is what TWR should show, since TWR answers "what could this thing do".
     */
    fun engineTorqueNm(
        rpm: Double,
        throttleFraction: Double? = null,
        peakTorqueNm: Double = ND2_PEAK_TORQUE_NM
    ): Double {
        val available = peakTorqueNm * torqueFraction(rpm)
        return available * (throttleFraction?.coerceIn(0.0, 1.0) ?: 1.0)
    }

    /**
     * Tractive force (N) at the contact patch for a given gear.
     *
     * F = T_engine · gear · finalDrive · efficiency / tireRadius
     *
     * @param gear 1-based gear index. Returns 0 when out of range or null
     *   (clutch in, neutral, coasting) — there is no thrust with the
     *   driveline disconnected, which is correct and matches the DFCO state.
     */
    fun tractiveForceNewtons(
        v: Vehicle,
        gear: Int?,
        rpm: Double,
        throttleFraction: Double? = null,
        peakTorqueNm: Double = ND2_PEAK_TORQUE_NM
    ): Double {
        if (gear == null || gear < 1 || gear > v.gearRatios.size) return 0.0
        if (v.tireRadiusM <= 0.0) return 0.0
        val torque = engineTorqueNm(rpm, throttleFraction, peakTorqueNm)
        return torque * v.gearRatios[gear - 1] * v.finalDrive *
            DRIVETRAIN_EFFICIENCY / v.tireRadiusM
    }

    /**
     * Thrust-to-weight ratio: tractive force over vehicle weight.
     *
     * Below 1.0 always, for any road car. That is the joke, and it is true.
     */
    fun thrustToWeight(
        v: Vehicle,
        gear: Int?,
        rpm: Double,
        fuelKg: Double,
        throttleFraction: Double? = null,
        peakTorqueNm: Double = ND2_PEAK_TORQUE_NM
    ): Double {
        val mass = v.totalMassKg(fuelKg)
        if (mass <= 0.0) return 0.0
        val f = tractiveForceNewtons(v, gear, rpm, throttleFraction, peakTorqueNm)
        return f / (mass * Units.G0)
    }

    /** Saturn V thrust-to-weight at liftoff, for the comparison readout. */
    const val SATURN_V_LIFTOFF_TWR = 1.15

    /** Falcon 9 thrust-to-weight at liftoff. */
    const val FALCON_9_LIFTOFF_TWR = 1.4
}
