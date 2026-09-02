package dev.swordfish.physics

/**
 * OBD-II Mode 01 PID definitions and decoders for the values Swordfish needs.
 *
 * Kept in the pure-Kotlin physics module (rather than the Android layer) so
 * the decoders are unit-testable against captured byte strings without an
 * emulator. The Android layer owns only the Bluetooth transport.
 */
object ObdPid {

    /** Engine RPM. Two bytes, quarter-rpm resolution. */
    const val ENGINE_RPM = "010C"

    /** Vehicle speed, km/h. One byte, direct. */
    const val VEHICLE_SPEED = "010D"

    /** MAF air flow, g/s. Two bytes, hundredths. Fuel-flow fallback source. */
    const val MAF_RATE = "0110"

    /** Engine fuel rate, L/h. Two bytes, 0.05 resolution. The preferred source. */
    const val ENGINE_FUEL_RATE = "015E"

    /** Fuel tank level, percent. One byte. Often unsupported or coarse. */
    const val FUEL_LEVEL = "012F"

    /** Absolute barometric pressure, kPa. One byte, direct. */
    const val BAROMETRIC_PRESSURE = "0133"

    /** Throttle position, percent. One byte. */
    const val THROTTLE_POSITION = "0111"

    /** Intake air temperature, C. One byte, offset -40. */
    const val INTAKE_AIR_TEMP = "010F"

    /** Calculated engine load, percent. One byte. */
    const val ENGINE_LOAD = "0104"

    /**
     * Commanded equivalence ratio (lambda). Two bytes, 2/65536 resolution.
     *
     * This is the key to correcting the MAF fallback. Actual AFR is
     * lambda * 14.7, so when the ECU commands enrichment under load,
     * lambda drops below 1 and the true fuel flow is HIGHER than a naive
     * stoichiometric MAF calculation would suggest. Confirmed present on
     * the ND2 as EQ_RAT / EQ_RAT11.
     */
    const val COMMANDED_EQUIV_RATIO = "0144"

    /**
     * Short-term fuel trim, bank 1, percent. One byte, (A/1.28)-100.
     * Confirmed present on the ND2 as SHRTFT1.
     */
    const val SHORT_FUEL_TRIM_1 = "0106"

    /**
     * Long-term fuel trim, bank 1, percent. One byte, (A/1.28)-100.
     * Confirmed present on the ND2 as LONGFT1.
     */
    const val LONG_FUEL_TRIM_1 = "0107"

    /**
     * Intake manifold absolute pressure, kPa. One byte, direct.
     *
     * Confirmed present on the ND2 as MAP. Enables a speed-density
     * cross-check on the MAF reading and, more usefully here, serves as a
     * direct engine-load proxy for the efficiency-band indicator: low MAP
     * at moderate rpm in a tall gear is exactly the light-throttle cruise
     * the game rewards.
     */
    const val INTAKE_MAP = "010B"

    /**
     * Ignition timing advance for cylinder 1, degrees. One byte, A/2 - 64.
     *
     * ## Why a fuel-economy instrument cares about ignition timing
     *
     * The SkyActiv-G 2.0 runs a **13:1 compression ratio**, which is
     * extraordinary for a naturally-aspirated engine on pump gas. Mazda's
     * manual recommends premium while permitting regular, and that
     * distinction is visible right here.
     *
     * Octane is knock RESISTANCE, not energy: 87 and 93 have identical
     * LHV, stoichiometric AFR and density. But on a 13:1 engine, lower
     * octane brings the knock sensor into play, and the ECU responds by
     * **retarding ignition timing**. Retarded timing burns later in the
     * stroke and extracts less work from the same chemical energy.
     *
     * So the fuel is unchanged and `Thermodynamics.thermalEfficiency`
     * falls — which flows straight through to Isp via
     * `eta * LHV / (v * g0)` and therefore to delta-V per gallon.
     *
     * **This makes the "does premium actually help?" question empirically
     * answerable on this car** rather than a matter of forum opinion:
     * compare timing advance under load across two tanks on the same route.
     * The cause (010E) and the effect (thermal efficiency) are both
     * measurable.
     */
    const val TIMING_ADVANCE = "010E"

    /**
     * Fuel type the ECU is configured for. One byte, an enumeration.
     *
     * Present on the ND2 and reads "Gasoline/petrol". Note this is **static
     * ECU configuration, not a measurement of what is in the tank** — on a
     * non-flex-fuel car it will say gasoline regardless of what was pumped.
     *
     * Useful only as a guard: if it ever reports something other than
     * gasoline, the fuel constants in `Units` and `Thermodynamics` are
     * wrong and the model should say so rather than quietly mis-report.
     */
    const val FUEL_TYPE = "0151"

    /**
     * Control module voltage, V. Two bytes, (256A+B)/1000.
     *
     * The ECU supply rail, which is the battery/alternator bus. Confirmed
     * on the ND2 at 12.27 V in accessory mode.
     *
     * **The ND has no voltmeter at all**, so this is information the driver
     * cannot otherwise get. See [ElectricalState] for why the same reading
     * means state-of-charge with the engine off and alternator output with
     * it running.
     */
    const val CONTROL_MODULE_VOLTAGE = "0142"

    /**
     * Engine coolant temperature, C. One byte, offset -40.
     *
     * Present on the ND2. Worth polling for the same reason as voltage:
     * the factory gauge is a three-state indicator (cold / normal /
     * danger) whose needle sits dead centre across the entire 88-100 C
     * range a warm engine actually occupies.
     */
    const val COOLANT_TEMP = "0105"

    /** Ambient air temperature, C. One byte, offset -40. Confirmed as AAT. */
    const val AMBIENT_AIR_TEMP = "0146"

    // --- PIDs discovered by the MX+ on 2026-08-20 ---
    //
    // The Ancel AD310 survey stopped querying at 0x4C and therefore reported
    // none of these. The MX+ diagnostic report enumerated all of them. They
    // were captured in ACCESSORY MODE with the engine off, so every value
    // read zero: presence is established, live behaviour is NOT. Anything
    // built on these must survive them answering zero forever.

    /**
     * Engine fuel rate, L/h. Two bytes, 0.05 resolution.
     *
     * **The project recorded this as unsupported.** That came from the Ancel
     * survey, which never asked. If it returns non-zero with the engine
     * running it is a direct measurement of the quantity the whole model
     * currently estimates from MAF, and would become the preferred source.
     *
     * Until confirmed at idle, treat presence as enumeration only.
     */
    const val ENGINE_FUEL_RATE_ALT = "015E"

    /**
     * Engine fuel rate and vehicle fuel rate, g/s. Four bytes: two per value.
     *
     * A second, independent fuel-flow path. If this and [ENGINE_FUEL_RATE]
     * both read live, they cross-check each other and the MAF estimate —
     * three-way agreement would be far stronger evidence than the single
     * idle calibration point the model rests on today.
     */
    const val ENGINE_VEHICLE_FUEL_RATE = "019D"

    /**
     * Actual engine percent torque. One byte, offset -125.
     *
     * With [ENGINE_REFERENCE_TORQUE], gives the ECU's own torque figure.
     * `Thrust.kt` currently uses a crude modelled torque curve; this would
     * replace the model with a measurement.
     */
    const val ACTUAL_ENGINE_TORQUE_PCT = "0162"

    /** Engine reference torque, Nm. Two bytes. Observed 184.39 lb-ft. */
    const val ENGINE_REFERENCE_TORQUE = "0163"

    /**
     * Transmission actual gear. Four bytes; the ratio is bytes C-D / 1000.
     *
     * The car reports its own gear ratio directly — observed 5.09 in
     * neutral. `DeltaVModel.inferGear` currently derives gear from speed and
     * rpm through the tire radius, the constant that was 6% wrong and had to
     * be corrected. This replaces inference with measurement.
     */
    const val TRANSMISSION_ACTUAL_GEAR = "01A4"

    /** Odometer, km. Four bytes, 0.1 resolution. Observed 30277 miles. */
    const val ODOMETER = "01A6"

    /** Supported-PID bitmask queries, used for capability detection on connect. */
    /**
     * Supported-PID bitmask queries, used for capability detection on connect.
     *
     * **All eight ranges, not four.** The original list stopped at `0160`,
     * which is what the Ancel AD310 survey did — and it is why that survey
     * reported 34 PIDs and missed sixteen. The MX+ diagnostic report of
     * 2026-08-20 enumerated PIDs up to `01A6`, including `015E` engine fuel
     * rate, which the project had recorded as absent.
     *
     * Querying a range the car does not implement costs one `NO DATA`, which
     * [walkSupportQueries] avoids anyway by following the continuation bit.
     * Stopping early costs a whole class of data. Do not shorten this list.
     */
    val SUPPORT_QUERIES = listOf(
        "0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0"
    )

    /**
     * Bit 0x20 of every mask range means "the next range is also supported".
     *
     * Each mask reply covers 32 PIDs, and its lowest bit is a continuation
     * flag rather than a real PID: a reply to `0100` sets 0x20 when `0120`
     * is worth asking about. Following it means the sweep asks exactly as
     * many questions as the car has answers for.
     */
    const val RANGE_CONTINUATION_BIT = 0x20

    /**
     * The queries worth sending, given what has been discovered so far.
     *
     * Returns the next query to send, or null when the chain ends. Callers
     * loop: start at `0100`, decode, ask again until this returns null.
     *
     * @param discovered every PID number found so far.
     * @param lastQueryBase the base of the range just decoded (0x00, 0x20...).
     */
    fun nextSupportQuery(discovered: Set<Int>, lastQueryBase: Int): String? {
        val continuation = lastQueryBase + RANGE_CONTINUATION_BIT
        if (!discovered.contains(continuation)) return null
        val next = lastQueryBase + 0x20
        if (next > 0xE0) return null
        return "01" + "%02X".format(next)
    }

    /**
     * Strip an ELM327 response to its data bytes.
     *
     * A raw reply looks like `41 0C 1A F8` -- mode+0x40, echoed PID, then
     * data. Real-world replies also carry spaces, CR/LF, a trailing `>`
     * prompt, occasional `SEARCHING...`, and on multi-ECU cars a header
     * prefix. This returns just the data bytes, or null if the frame is
     * unusable.
     *
     * @param raw The line as received.
     * @param pid The 4-char PID we asked for, e.g. "010C".
     */
    fun extractDataBytes(raw: String, pid: String): List<Int>? {
        val cleaned = raw.uppercase()
            .replace(">", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("SEARCHING\\.*"), " ")
            .replace(Regex("[^0-9A-F ]"), " ")
            .trim()
        if (cleaned.isEmpty()) return null

        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Some adapters return bytes unspaced ("410C1AF8"); re-split those.
        //
        // Odd-length tokens are skipped rather than failing the frame: with
        // headers enabled the CAN ID arrives as a 3-nibble token such as
        // "7E8", which is not a byte pair but also not an error. The real
        // payload is located by the mode+PID scan below, so dropping an
        // unparseable prefix token is safe.
        val bytes = mutableListOf<Int>()
        for (tok in tokens) {
            if (tok.length % 2 != 0) continue
            var ok = true
            val parsed = tok.chunked(2).map { pair ->
                pair.toIntOrNull(16) ?: run { ok = false; 0 }
            }
            if (ok) bytes.addAll(parsed)
        }

        val mode = pid.substring(0, 2).toIntOrNull(16) ?: return null
        val pidNum = pid.substring(2, 4).toIntOrNull(16) ?: return null
        val respMode = mode + 0x40

        // Find the response header anywhere in the stream -- multi-ECU cars
        // and adapters with headers on prepend extra bytes.
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == respMode && bytes[i + 1] == pidNum) {
                val data = bytes.subList(i + 2, bytes.size)
                return if (data.isEmpty()) null else data.toList()
            }
        }
        return null
    }

    /** RPM = (256*A + B) / 4. */
    fun decodeRpm(data: List<Int>): Double? {
        if (data.size < 2) return null
        return (256.0 * data[0] + data[1]) / 4.0
    }

    /** Speed = A km/h, returned as m/s. */
    fun decodeSpeedMps(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return Units.kphToMps(data[0].toDouble())
    }

    /** MAF = (256*A + B) / 100 g/s. */
    fun decodeMafGramsPerSec(data: List<Int>): Double? {
        if (data.size < 2) return null
        return (256.0 * data[0] + data[1]) / 100.0
    }

    /** Fuel rate = (256*A + B) * 0.05 L/h, returned as kg/s. */
    fun decodeFuelRateKgPerSec(data: List<Int>): Double? {
        if (data.size < 2) return null
        val lph = (256.0 * data[0] + data[1]) * 0.05
        return Units.literPerHourToKgPerSec(lph)
    }

    /** Fuel level = A * 100/255 percent, returned as a 0.0-1.0 fraction. */
    fun decodeFuelLevelFraction(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return (data[0] / 255.0).coerceIn(0.0, 1.0)
    }

    /** Barometric pressure = A kPa, returned as hPa. */
    fun decodeBarometricHpa(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] * 10.0
    }

    /** Throttle position = A * 100/255 percent. */
    fun decodeThrottlePercent(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] * 100.0 / 255.0
    }

    /** Intake air temp = A - 40, in C. */
    fun decodeIntakeAirTempC(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] - 40.0
    }

    /**
     * Timing advance = A/2 - 64, in degrees before top dead centre.
     *
     * Positive is advanced (normal); a fall under load is the knock
     * sensor pulling timing back, which is what lower-octane fuel causes
     * on a high-compression engine.
     */
    fun decodeTimingAdvanceDegrees(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] / 2.0 - 64.0
    }

    /** Control module voltage = (256*A + B) / 1000, in volts. */
    fun decodeVoltage(data: List<Int>): Double? {
        if (data.size < 2) return null
        return (256.0 * data[0] + data[1]) / 1000.0
    }

    /** Coolant temp = A - 40, in C. */
    fun decodeCoolantTempC(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] - 40.0
    }

    /** Ambient air temp = A - 40, in C. */
    fun decodeAmbientAirTempC(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] - 40.0
    }

    /** Intake MAP = A kPa, direct. */
    fun decodeMapKpa(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0].toDouble()
    }

    /**
     * Commanded equivalence ratio (lambda) = (256*A + B) * 2 / 65536.
     *
     * 1.0 is stoichiometric. Below 1.0 the ECU is commanding a rich
     * mixture, which the MAF fuel-flow path must account for.
     */
    fun decodeEquivalenceRatio(data: List<Int>): Double? {
        if (data.size < 2) return null
        return (256.0 * data[0] + data[1]) * 2.0 / 65536.0
    }

    /** Fuel trim = (A / 1.28) - 100, in percent. Negative means leaning out. */
    fun decodeFuelTrimPercent(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return (data[0] / 1.28) - 100.0
    }

    /**
     * Engine fuel rate = (256*A + B) * 0.05 L/h, returned as kg/s.
     *
     * Same encoding as [decodeFuelRateKgPerSec]; named separately because
     * the call site cares which PID it came from when cross-checking.
     */
    fun decodeEngineFuelRateKgPerSec(data: List<Int>): Double? =
        decodeFuelRateKgPerSec(data)

    /**
     * Engine and vehicle fuel rate from PID 019D, in kg/s.
     *
     * Four bytes: A-B engine fuel rate, C-D vehicle fuel rate, both in
     * grams/sec at 0.02 resolution. Returns engine rate first.
     */
    fun decodeEngineVehicleFuelRate(data: List<Int>): Pair<Double, Double>? {
        if (data.size < 4) return null
        val engineGps = (256.0 * data[0] + data[1]) * 0.02
        val vehicleGps = (256.0 * data[2] + data[3]) * 0.02
        return (engineGps / 1000.0) to (vehicleGps / 1000.0)
    }

    /** Actual engine percent torque = A - 125. */
    fun decodeActualTorquePercent(data: List<Int>): Double? {
        if (data.isEmpty()) return null
        return data[0] - 125.0
    }

    /** Engine reference torque = 256*A + B, in Nm. */
    fun decodeReferenceTorqueNm(data: List<Int>): Double? {
        if (data.size < 2) return null
        return 256.0 * data[0] + data[1]
    }

    /**
     * Transmission actual gear ratio from PID 01A4.
     *
     * Four bytes; bytes C-D carry the ratio at 0.001 resolution. Byte A is a
     * support mask and B the gear number, neither of which we need — the
     * ratio alone is what replaces the inferred gear.
     */
    fun decodeGearRatio(data: List<Int>): Double? {
        if (data.size < 4) return null
        return (256.0 * data[2] + data[3]) / 1000.0
    }

    /** Odometer = (A<<24 | B<<16 | C<<8 | D) * 0.1 km, returned as km. */
    fun decodeOdometerKm(data: List<Int>): Double? {
        if (data.size < 4) return null
        val raw = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or
            (data[2].toLong() shl 8) or data[3].toLong()
        return raw * 0.1
    }

    /**
     * Decode a supported-PID bitmask response into the set of PID numbers
     * it reports as available.
     *
     * A reply to `0100` covers PIDs 0x01-0x20: the MSB of the first data
     * byte is PID 0x01, and so on. Querying 0x20 tells you about 0x21-0x40.
     *
     * @param data The four mask bytes.
     * @param baseQuery The query PID number (0x00, 0x20, 0x40, 0x60).
     */
    fun decodeSupportedPids(data: List<Int>, baseQuery: Int): Set<Int> {
        if (data.size < 4) return emptySet()
        val supported = mutableSetOf<Int>()
        for (byteIdx in 0 until 4) {
            for (bit in 0 until 8) {
                val isSet = (data[byteIdx] shr (7 - bit)) and 1 == 1
                if (isSet) supported.add(baseQuery + byteIdx * 8 + bit + 1)
            }
        }
        return supported
    }
}

/**
 * What the connected vehicle actually supports, discovered on connect.
 *
 * The whole point of this type is that the app adapts to the car rather
 * than assuming: if PID 015E is missing we fall back to MAF, and if tank
 * level is missing we ask the user to confirm fill-ups. Nothing silently
 * produces a wrong number because a PID was absent.
 */
data class VehicleCapabilities(
    val hasEngineFuelRate: Boolean,
    val hasMaf: Boolean,
    val hasFuelLevel: Boolean,
    val hasBarometricPressure: Boolean,
    val hasIntakeAirTemp: Boolean,
    val hasEquivalenceRatio: Boolean = false,
    val hasFuelTrims: Boolean = false,
    val hasMap: Boolean = false,
    val hasAmbientAirTemp: Boolean = false,
    val supportedPids: Set<Int> = emptySet()
) {
    /** True when some path to fuel flow exists. Without one, no Isp. */
    val canComputeFuelFlow: Boolean get() = hasEngineFuelRate || hasMaf

    /** True when fuel level must be tracked open-loop from user fill-ups. */
    val needsManualFuelReset: Boolean get() = !hasFuelLevel

    /**
     * True when the MAF path can be corrected with the ECU's own mixture
     * data rather than assuming a fixed 14.7:1 ratio. This closes most of
     * the accuracy gap to a native PID 015E reading.
     */
    val canCorrectMafMixture: Boolean
        get() = hasMaf && (hasEquivalenceRatio || hasFuelTrims)

    /** Human-readable summary for the diagnostics screen. */
    fun describe(): String = buildString {
        appendLine("Fuel flow source: " + when {
            hasEngineFuelRate -> "PID 015E engine fuel rate (preferred)"
            hasMaf && canCorrectMafMixture ->
                "MAF-derived, mixture-corrected (0110 + lambda/trims)"
            hasMaf -> "MAF-derived (PID 0110 / AFR) -- approximate under load"
            else -> "NONE -- Isp cannot be computed"
        })
        appendLine("Tank level: " + if (hasFuelLevel) "PID 012F" else "manual fill-up tracking")
        appendLine("Barometer: " + if (hasBarometricPressure) "PID 0133 (vehicle)" else "phone sensor")
        appendLine("Intake air temp: " + if (hasIntakeAirTemp) "PID 010F" else "assumed 15C")
        appendLine("Ambient air temp: " + if (hasAmbientAirTemp) "PID 0146" else "not reported")
        appendLine("Manifold pressure: " + if (hasMap) "PID 010B" else "not reported")
    }

    companion object {
        fun fromSupportedPids(pids: Set<Int>) = VehicleCapabilities(
            hasEngineFuelRate = pids.contains(0x5E),
            hasMaf = pids.contains(0x10),
            hasFuelLevel = pids.contains(0x2F),
            hasBarometricPressure = pids.contains(0x33),
            hasIntakeAirTemp = pids.contains(0x0F),
            hasEquivalenceRatio = pids.contains(0x44),
            hasFuelTrims = pids.contains(0x06) && pids.contains(0x07),
            hasMap = pids.contains(0x0B),
            hasAmbientAirTemp = pids.contains(0x46),
            supportedPids = pids
        )

        /**
         * The 2023 MX-5 ND2 Club (SkyActiv-G 2.0, CAN OBD-II), as surveyed
         * from the car on 2026-08-19 with an Ancel AD310. The scanner
         * enumerated exactly 34 supported live-data items.
         *
         * The headline: **PID 015E engine fuel rate is NOT supported**, so
         * the MAF path is the only route to fuel flow. Everything needed to
         * correct it -- lambda, both fuel trims, MAP, IAT -- is present, so
         * the fallback can be made accurate rather than merely adequate.
         *
         * See docs/VEHICLE_SURVEY.md for the full enumeration and the raw
         * observed values.
         */
        val ND2_2023_OBSERVED = VehicleCapabilities(
            hasEngineFuelRate = false,
            hasMaf = true,
            hasFuelLevel = true,
            hasBarometricPressure = true,
            hasIntakeAirTemp = true,
            hasEquivalenceRatio = true,
            hasFuelTrims = true,
            hasMap = true,
            hasAmbientAirTemp = true,
            supportedPids = setOf(
                0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E,
                0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x21, 0x23, 0x2E,
                0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x3C, 0x42, 0x43, 0x44,
                0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C
            )
        )
    }
}
