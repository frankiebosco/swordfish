package dev.swordfish.physics

/**
 * Translates a delta-V budget into real-world spaceflight terms.
 *
 * ## Why not Kerbal references
 *
 * An earlier version of this file used the KSP delta-V map — MUN RETURN,
 * MINMUS RETURN, DUNA RETURN. It was replaced for two reasons, both good:
 *
 * 1. **Anyone who would recognise those numbers already knows them.** Telling
 *    a KSP player that 6,000 m/s reaches Minmus is telling them something they
 *    have memorised. It carries no information.
 * 2. **This is not KSP.** Swordfish is *inspired by* it, not an imitation of
 *    it. Borrowing the fictional solar system muddles what the instrument
 *    actually is.
 *
 * Real orbital mechanics is both more impressive and more legible: **a tank of
 * petrol in an MX-5 is about 80% of the delta-V needed to reach low Earth
 * orbit.** That is a true statement about the real world, and it needs no
 * prior knowledge to land.
 *
 * ## The headline metric
 *
 * [percentToOrbit] is the primary readout. It has properties a milestone list
 * does not:
 *
 * - **Continuous** — it moves as you drive, rather than jumping between rungs
 * - **Anchored** — 100% is a real, famous threshold, not an arbitrary tier
 * - **Achievable** — hypermiling genuinely crosses it (~137%), so exceeding
 *   orbital delta-V becomes a goal rather than a curiosity
 * - **Honest at the bottom** — wide-open throttle drops to ~27%, which is a
 *   more useful thing to see than the name of a moon you cannot reach
 */
object OrbitalScale {

    /**
     * Delta-V from Earth's surface to low Earth orbit, m/s.
     *
     * ~9,400 m/s is the standard figure including gravity and atmospheric
     * drag losses — the real cost of getting to orbit, not the ideal
     * ~7,800 m/s orbital velocity alone. Using the honest number matters:
     * launch vehicles are sized against this, not the idealised figure.
     */
    const val LOW_EARTH_ORBIT = 9_400.0

    /** Delta-V to reach the Karman line (100 km) without orbiting. */
    const val SUBORBITAL = 1_400.0

    /**
     * Real mission budgets, for the secondary readout.
     *
     * All are *from low Earth orbit* except the first two, which are from the
     * surface. Values are standard mission-planning figures.
     */
    val REFERENCES = listOf(
        Reference("SUBORBITAL", SUBORBITAL, "up past the Karman line"),
        Reference("LOW EARTH ORBIT", LOW_EARTH_ORBIT, "surface to LEO, losses included"),
        Reference("GEOSTATIONARY", LOW_EARTH_ORBIT + 2_440.0, "LEO to GTO"),
        Reference("LUNAR FLYBY", LOW_EARTH_ORBIT + 3_120.0, "trans-lunar injection"),
        Reference("ESCAPE VELOCITY", LOW_EARTH_ORBIT + 3_200.0, "leave Earth entirely"),
        Reference("MARS TRANSFER", LOW_EARTH_ORBIT + 3_600.0, "trans-Mars injection"),
        Reference("LUNAR LANDING", LOW_EARTH_ORBIT + 5_930.0, "TLI, capture and descent")
    )

    data class Reference(
        val name: String,
        val deltaVMps: Double,
        val blurb: String
    )

    /**
     * The headline figure: this budget as a percentage of the delta-V needed
     * to reach low Earth orbit.
     *
     * Can exceed 100% — hypermiling does, which is the point.
     */
    fun percentToOrbit(deltaVMps: Double): Double {
        if (!deltaVMps.isFinite() || deltaVMps <= 0.0) return 0.0
        return deltaVMps / LOW_EARTH_ORBIT * 100.0
    }

    /**
     * Short label for the panel, e.g. `"80% TO ORBIT"` or `"ORBITAL +37%"`.
     *
     * Crossing 100% changes the wording rather than just the number, so the
     * threshold reads as an accomplishment.
     */
    fun label(deltaVMps: Double): String {
        val pct = percentToOrbit(deltaVMps)
        return when {
            pct <= 0.0 -> "GROUNDED"
            pct >= 100.0 -> "ORBITAL +${"%.0f".format(pct - 100.0)}%"
            else -> "${"%.0f".format(pct)}% TO ORBIT"
        }
    }

    /** True once the budget exceeds what it takes to reach orbit. */
    fun isOrbital(deltaVMps: Double): Boolean =
        deltaVMps.isFinite() && deltaVMps >= LOW_EARTH_ORBIT

    /**
     * The most demanding real mission this budget could cover.
     *
     * Secondary to [percentToOrbit] — useful on a detail screen, too coarse
     * for the main gauge.
     */
    fun reachable(deltaVMps: Double): Reference? {
        if (!deltaVMps.isFinite() || deltaVMps <= 0.0) return null
        return REFERENCES.lastOrNull { deltaVMps >= it.deltaVMps }
    }

    /** The next milestone up, for a "how much further" readout. */
    fun nextUp(deltaVMps: Double): Reference? {
        if (!deltaVMps.isFinite()) return null
        return REFERENCES.firstOrNull { deltaVMps < it.deltaVMps }
    }

    /** Delta-V still needed to reach [nextUp]. Null at the top of the table. */
    fun deltaVToNext(deltaVMps: Double): Double? {
        val next = nextUp(deltaVMps) ?: return null
        return (next.deltaVMps - deltaVMps).coerceAtLeast(0.0)
    }

    /**
     * Progress toward orbit as a 0..1 fraction, clamped at 1.0.
     *
     * For a progress bar. Clamped so the bar does not overflow when the
     * percentage exceeds 100 — the label carries that information instead.
     */
    fun orbitProgress(deltaVMps: Double): Double {
        if (!deltaVMps.isFinite() || deltaVMps <= 0.0) return 0.0
        return (deltaVMps / LOW_EARTH_ORBIT).coerceIn(0.0, 1.0)
    }
}
