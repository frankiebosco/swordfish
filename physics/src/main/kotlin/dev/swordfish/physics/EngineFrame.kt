package dev.swordfish.physics

/**
 * Engine speed and road speed, decoded from MS-CAN arbitration ID `202`.
 *
 * ## Two signals in one frame (CONFIRMED 2026-08-27)
 *
 * From 16,410 frames on the ridge-road loop:
 *
 *  - **bytes 0-1 = engine RPM.** Nonzero (~4,100-5,400 raw) when the car is
 *    stopped, which is what an idling engine looks like and what rules out its
 *    being a second speed signal.
 *  - **bytes 2-3 = vehicle speed.** Correlates with the wheel-speed aggregate
 *    at r = 0.916, ratio 0.998 -- the SAME units as [WheelSpeeds], from a
 *    different module. Reads exactly 0 when stopped, unlike `215` which
 *    reports its 10000 sentinel.
 *
 * ## How the RPM identification was proved
 *
 * Not by the range looking plausible -- by the GEAR SIGNATURE. The ratio of
 * bytes 0-1 to road speed, over 1,039 paired samples, is not a smooth spread.
 * It has two sharp peaks, at ~3.0 and ~5.3:
 *
 * ```
 *   3.0 : 366  ##################################################
 *   3.1 : 119  #######################################
 *   5.2 : 167  ##################################################
 *   5.3 : 137  #############################################
 * ```
 *
 * A quantity whose ratio to road speed clusters at discrete values IS engine
 * speed, by the definition of a gearbox. Two clusters matches the drive: top
 * gear on the ridge road, a lower gear in town.
 *
 * That also makes gear detection free -- see [gearRatio].
 *
 * ## Scaling is NOT yet fitted
 *
 * Both values are returned RAW. The divisors are unknown:
 *
 *  - RPM: raw/4 gives 1,029-3,071 which is plausible for this engine, but it
 *    is a guess. Idle-RPM alignment could not be checked because `215` and
 *    `202` timestamps never coincided closely enough at a full stop in the
 *    2026-08-27 data. **Any capture with the car idling settles it.**
 *  - Speed: shares [WheelSpeeds]' unknown scale factor, so fitting either
 *    fits both.
 *
 * Returning raw counts with an honest "unfitted" label is deliberate. A
 * plausible-looking guessed divisor would propagate into a gauge and be much
 * harder to notice than a missing one.
 */
object EngineFrame {

    /** The arbitration ID this decoder reads. */
    const val CAN_ID = "202"

    /**
     * Below this raw speed, gear ratio is not computable.
     *
     * The ratio divides by road speed, so at a standstill it is undefined and
     * at a crawl it is dominated by quantisation.
     */
    const val MIN_SPEED_FOR_GEAR = 300.0

    /**
     * One decoded `202` frame. Both values are RAW -- see the class note.
     *
     * @param rpmRaw bytes 0-1, engine speed in unknown units.
     * @param speedRaw bytes 2-3, road speed in the same units as
     *   [WheelSpeeds.Reading.aggregate].
     */
    data class Reading(
        val rpmRaw: Int,
        val speedRaw: Int
    ) {
        /** True when the car is moving according to THIS frame's own speed field. */
        val isMoving: Boolean get() = speedRaw > 0

        /**
         * Engine speed divided by road speed.
         *
         * Clusters at discrete values, one per gear -- the signature that
         * identified the RPM field in the first place. Because it is a ratio
         * of two quantities in unknown units, it is still a valid gear
         * DISCRIMINATOR even though neither input is scaled: the clusters are
         * where they are regardless of the divisors.
         *
         * Null below [MIN_SPEED_FOR_GEAR].
         */
        val gearRatio: Double?
            get() = if (speedRaw >= MIN_SPEED_FOR_GEAR) rpmRaw.toDouble() / speedRaw else null
    }

    /**
     * Decode a `202` payload.
     *
     * @return null unless this is a full 8-byte frame.
     */
    fun decode(data: List<Int>): Reading? {
        if (data.size != 8) return null
        return Reading(
            rpmRaw = (data[0] shl 8) or data[1],
            speedRaw = (data[2] shl 8) or data[3]
        )
    }
}
