package dev.swordfish.physics

/**
 * Occupant and cargo mass — the "crew and payload" of the stage.
 *
 * ## Why this is a type and not a number
 *
 * Payload is the one model input that has to come from a human, and asking a
 * passenger for their exact body weight is a genuinely unpleasant prompt. So
 * every occupant is entered one of two ways:
 *
 *  - **[Occupant.Average]** — a standard adult mass, no personal disclosure
 *  - **[Occupant.Exact]** — a precise figure, for anyone who wants the accuracy
 *
 * Neither is privileged. Whichever the user picks is simply what the maths
 * uses; there is no "real" value hiding behind the average, and nothing in
 * the app should nag toward exactness.
 *
 * The framing is a natural fit for the theme: KSP crew have standard masses
 * too, and "one standard crew member" reads as a deliberate unit rather than
 * an evasion.
 *
 * ## Accuracy note
 *
 * A 30 lb error in payload shifts the mass ratio by well under a percent, so
 * choosing an average over an exact figure costs almost nothing in the
 * delta-V readout. This is worth stating in the UI: the honest answer to
 * "does it matter if I round?" is "barely".
 */

/** One person aboard. */
sealed interface Occupant {
    /** Mass in kilograms. */
    val massKg: Double

    /** Short label for the UI, e.g. "Driver — average adult". */
    val label: String

    /**
     * A standard adult mass, chosen so nobody has to disclose their own.
     *
     * Figures are US adult averages (NHANES-derived, rounded to a tidy
     * number of pounds). They are reference constants, not a judgement about
     * any particular person.
     */
    data class Average(val build: AdultAverage) : Occupant {
        override val massKg: Double get() = build.massKg
        override val label: String get() = build.label
    }

    /** An exact figure, for users who prefer the precision. */
    data class Exact(val kg: Double, val name: String = "custom") : Occupant {
        override val massKg: Double get() = kg.coerceAtLeast(0.0)
        override val label: String get() = name

        companion object {
            fun ofPounds(lb: Double, name: String = "custom") =
                Exact(Units.lbToKg(lb), name)
        }
    }
}

/**
 * Standard adult masses for the no-disclosure path.
 *
 * US adult averages rounded to convenient pounds. [ADULT] is the
 * unspecified default and sits between the other two — it exists so the UI
 * can offer a neutral option that requires stating nothing at all.
 */
enum class AdultAverage(val pounds: Double, val label: String) {
    ADULT(180.0, "average adult"),
    ADULT_MALE(200.0, "average adult male"),
    ADULT_FEMALE(170.0, "average adult female");

    val massKg: Double get() = Units.lbToKg(pounds)
}

/**
 * Everything aboard that is not the car itself or its fuel.
 *
 * @param driver Always present.
 * @param passenger Null when driving alone. The ND2 seats exactly two, so
 *   there is no list here — the car's own geometry bounds the crew.
 * @param cargoKg Luggage, tools, whatever is in the boot. A single estimate;
 *   nobody is going to itemise this.
 */
data class Payload(
    val driver: Occupant,
    val passenger: Occupant? = null,
    val cargoKg: Double = 0.0
) {
    /** Total payload mass carried by the vehicle. */
    val totalKg: Double
        get() = driver.massKg + (passenger?.massKg ?: 0.0) + cargoKg.coerceAtLeast(0.0)

    val totalLb: Double get() = Units.kgToLb(totalKg)

    /** Crew count, for the KSP-flavoured readout. */
    val crewCount: Int get() = if (passenger != null) 2 else 1

    /** One-line summary for the settings screen. */
    fun describe(): String = buildString {
        append("Crew $crewCount")
        append(" (${driver.label}")
        if (passenger != null) append(" + ${passenger.label}")
        append(")")
        if (cargoKg > 0.0) append(", cargo ${"%.0f".format(Units.kgToLb(cargoKg))} lb")
        append(" — ${"%.0f".format(totalLb)} lb total")
    }

    companion object {
        /** Driving alone, nothing in the boot, no disclosure required. */
        val SOLO_DEFAULT = Payload(driver = Occupant.Average(AdultAverage.ADULT))

        /** Convenience for the common case of two people and a light boot. */
        fun twoUp(cargoLb: Double = 0.0) = Payload(
            driver = Occupant.Average(AdultAverage.ADULT),
            passenger = Occupant.Average(AdultAverage.ADULT),
            cargoKg = Units.lbToKg(cargoLb)
        )
    }
}
