package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [WheelCalibration] against the real 2026-08-27 captures.
 *
 * ## Why a test and not a scratch script
 *
 * The point is not to produce a number -- this capture cannot produce a good
 * one. The point is to prove the guards WORK on the exact data that fooled a
 * hand-rolled analysis into reporting GPS speeds of 65 m/s.
 *
 * The first attempt at fitting scale used this file, differenced GPS positions
 * across the 10 s slice gaps, and produced impossible values. If
 * `WheelCalibration` quietly did the same, the constant it hands back after
 * the calibration drive could not be trusted either.
 *
 * So the assertion here is deliberately inverted: this capture MUST be judged
 * untrustworthy. A green result on this file would mean the guards are broken.
 *
 * Skips cleanly when the logs are absent, so the suite still runs on a machine
 * without them.
 */
class WheelCalibrationRealDataTest {

    private fun logs(): Pair<List<String>, List<String>>? {
        val roots = listOf("logs/2026-08-27/run4", "../logs/2026-08-27/run4")
        for (r in roots) {
            val cap = File("$r/mscan-1787860488153.ndjson")
            val drv = File("$r/drives/drive-20260827-155430.ndjson")
            if (cap.isFile && drv.isFile) return cap.readLines() to drv.readLines()
        }
        return null
    }

    @Test
    fun `the real capture is parsed and yields wheel frames`() {
        val (cap, _) = logs() ?: return
        val frames = MsCanReplay.parseFrames(cap).filter { it.canId == WheelSpeeds.CAN_ID }
        assertTrue(frames.size > 500, "expected many 215 frames, got ${frames.size}")

        val decoded = frames.mapNotNull { WheelSpeeds.decode(it.data) }
        assertTrue(decoded.size > 500, "most 215 frames should decode")

        // The car was stationary at times and moving at others.
        assertTrue(decoded.any { !it.isMoving }, "expected stationary frames")
        assertTrue(decoded.any { it.isMoving }, "expected moving frames")

        // And it turned: the ridge-road loop has real corners.
        assertTrue(decoded.any { it.isTurning }, "expected turning frames")
    }

    /**
     * The guard that matters. This capture's GPS is unusable for a scale fit,
     * and the fitter must SAY so rather than return a confident constant.
     */
    @Test
    fun `the gapped capture is reported as not trustworthy`() {
        val (cap, drv) = logs() ?: return
        val fit = WheelCalibration.fitScale(cap, drv)
        if (fit != null) {
            assertFalse(
                fit.isTrustworthy,
                "the 2026-08-27 capture has 10s gaps and cannot support a scale " +
                    "fit; reporting it as usable would mean the guards are broken " +
                    "(countsPerMps=${fit.countsPerMps}, stdev=${fit.residualStdevMps})"
            )
        }
    }

    /**
     * The ridge-road loop mixes left and right turns, so it cannot settle the side
     * convention however many samples it contains.
     */
    @Test
    fun `a mixed-direction real drive does not settle the side convention`() {
        val (cap, _) = logs() ?: return
        val fit = WheelCalibration.fitSide(cap, turnedLeft = true)
        assertFalse(
            fit.isTrustworthy,
            "a loop with corners both ways must not settle the side question " +
                "(confidence=${fit.confidence}, n=${fit.turningSamples})"
        )
    }
}
