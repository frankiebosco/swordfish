package dev.swordfish.physics

/**
 * What the electrical system is doing, from PID `0142` alone.
 *
 * ## Why a car voltmeter is usually useless, and this one is not
 *
 * A factory voltmeter — where one exists at all, which it does not on the
 * ND — reads 13.5-14.5 V for the entire time the engine is running. A
 * needle across that span never moves. The same is true of the ND's
 * coolant gauge: the real range on a warm engine is roughly 88-100 C and
 * the needle sits dead centre across all of it. Both are three-state
 * indicators wearing the costume of a continuous instrument: **cold /
 * normal / something is wrong.**
 *
 * So this does not try to be a needle. It reports the number the dashboard
 * refuses to show, plus the state that actually matters — and it is honest
 * that most of the time the state is simply NORMAL.
 *
 * ## The reading means two completely different things
 *
 * `0142` is the ECU supply rail, which is the battery/alternator bus:
 *
 *  - **engine off** — battery state of charge. This is the KSP
 *    `ElectricCharge` reading: a reservoir that depletes and recharges.
 *  - **engine running** — alternator output. Not charge at all; a car with
 *    a nearly dead battery still reads 14 V while running.
 *
 * The genuinely diagnostic moment is neither: it is the **voltage sag
 * during cranking**, which predicts a no-start weeks before it happens and
 * which no dashboard anywhere displays.
 */
object ElectricalState {

    /**
     * Resting-voltage to state-of-charge points for a 12 V lead-acid
     * battery, measured with the engine off and the surface charge settled.
     *
     * These are the standard figures; they are not car-specific.
     */
    const val VOLTS_FULL = 12.7
    const val VOLTS_75 = 12.5
    const val VOLTS_50 = 12.2
    const val VOLTS_25 = 12.0
    const val VOLTS_FLAT = 11.8

    /** Below this while running, the alternator is not keeping up. */
    const val ALTERNATOR_LOW = 13.2

    /** Above this, the regulator is overcharging — a real fault. */
    const val ALTERNATOR_HIGH = 15.0

    /** Normal charging range. */
    const val ALTERNATOR_NOMINAL_LOW = 13.5
    const val ALTERNATOR_NOMINAL_HIGH = 14.5

    /**
     * Cranking sag below which the battery is failing.
     *
     * A healthy battery dips to about 10 V while the starter turns. Below
     * 9.6 V under load is the standard condemnation threshold — and the
     * point of catching it is that it appears long before the car actually
     * refuses to start.
     */
    const val CRANK_FAIL_VOLTS = 9.6

    /** Engine speed above which the engine counts as running. */
    const val RUNNING_RPM = 400.0

    /**
     * Engine speed range that indicates cranking rather than running.
     *
     * A starter turns the engine at roughly 150-250 rpm, well below any
     * idle.
     */
    const val CRANKING_RPM_MIN = 50.0
    const val CRANKING_RPM_MAX = 400.0

    enum class State(val label: String, val detail: String) {
        /** Engine off, battery healthy. */
        RESTING("BATTERY", "state of charge"),

        /** Engine off and the battery is low enough to worry about. */
        LOW_CHARGE("LOW CHARGE", "battery needs charging"),

        /** Starter turning. The most diagnostic moment there is. */
        CRANKING("CRANKING", "starter load"),

        /** Cranking sag past the condemnation threshold. */
        CRANK_WEAK("WEAK BATTERY", "excessive cranking sag"),

        /** Engine running, alternator normal. The usual case. */
        CHARGING("CHARGING", "alternator nominal"),

        /** Running but the alternator is not keeping up. */
        UNDERCHARGING("UNDERCHARGE", "alternator struggling"),

        /** Running and overcharging — a regulator fault. */
        OVERCHARGING("OVERCHARGE", "regulator fault"),

        /** No usable reading. */
        UNKNOWN("---", "no reading");

        /** True when this warrants the driver's attention. */
        val isFault: Boolean
            get() = this == LOW_CHARGE || this == CRANK_WEAK ||
                this == UNDERCHARGING || this == OVERCHARGING
    }

    /**
     * Classify a voltage reading.
     *
     * @param volts from PID `0142`, or null when unavailable.
     * @param rpm engine speed, which decides whether the reading describes
     *   the battery or the alternator. Without it the two are
     *   indistinguishable.
     */
    fun classify(volts: Double?, rpm: Double?): State {
        if (volts == null || !volts.isFinite() || volts <= 0.0) return State.UNKNOWN

        // Cranking first: it is brief, and it is the only moment the
        // reading is genuinely diagnostic.
        if (rpm != null && rpm in CRANKING_RPM_MIN..CRANKING_RPM_MAX) {
            return if (volts < CRANK_FAIL_VOLTS) State.CRANK_WEAK else State.CRANKING
        }

        val running = (rpm ?: 0.0) >= RUNNING_RPM
        return if (running) {
            when {
                volts > ALTERNATOR_HIGH -> State.OVERCHARGING
                volts < ALTERNATOR_LOW -> State.UNDERCHARGING
                else -> State.CHARGING
            }
        } else {
            if (volts < VOLTS_25) State.LOW_CHARGE else State.RESTING
        }
    }

    /**
     * Battery state of charge as 0.0-1.0, or null while the engine runs.
     *
     * Deliberately null when running: the bus then shows alternator output,
     * and reporting that as "charge" would be a confident lie. A car with a
     * failing battery reads a healthy 14 V all the way home.
     */
    fun stateOfCharge(volts: Double?, rpm: Double?): Double? {
        if (volts == null || !volts.isFinite()) return null
        if ((rpm ?: 0.0) >= RUNNING_RPM) return null

        // Piecewise linear through the standard resting-voltage points.
        // A curve fit would imply precision this measurement does not have.
        return when {
            volts >= VOLTS_FULL -> 1.0
            volts >= VOLTS_75 -> 0.75 + 0.25 * (volts - VOLTS_75) / (VOLTS_FULL - VOLTS_75)
            volts >= VOLTS_50 -> 0.50 + 0.25 * (volts - VOLTS_50) / (VOLTS_75 - VOLTS_50)
            volts >= VOLTS_25 -> 0.25 + 0.25 * (volts - VOLTS_25) / (VOLTS_50 - VOLTS_25)
            volts >= VOLTS_FLAT -> 0.25 * (volts - VOLTS_FLAT) / (VOLTS_25 - VOLTS_FLAT)
            else -> 0.0
        }.coerceIn(0.0, 1.0)
    }
}
