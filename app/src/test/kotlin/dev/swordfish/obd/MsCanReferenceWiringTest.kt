package dev.swordfish.obd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the fix for the MS-CAN capture that collected nothing on 2026-08-26.
 *
 * ## What happened
 *
 * Two captures ran their full length against a working adapter and saved no
 * file at all:
 *
 * ```
 * capture finished: obs=0 ids=0 droppedNoRef=735  droppedFull=0
 * capture finished: obs=0 ids=0 droppedNoRef=1385 droppedFull=0
 * ```
 *
 * 2120 CAN frames arrived and every one was dropped for want of a reference.
 * `MsCanCapture` was correct throughout; the fault was upstream in
 * `ProbeActivity.startMsCanReference`, which did this:
 *
 * ```kotlin
 * val imu = ImuSource(this)
 * imu.startLocation()          // location only -- sensors NOT registered
 * ...
 * val h = imu.headingDegrees   // written only from the SENSOR callback
 * ```
 *
 * `headingDegrees` is assigned exclusively inside `onRotationVector`, so
 * without `start()` it stays null for the life of the screen. The yaw-rate
 * helper was never reached and `onReference` was never called.
 *
 * ## Why this is a SOURCE test
 *
 * `ImuSource` needs `SensorManager` and `LocationManager`, both stubbed to
 * throw in local unit tests, and this module has no Robolectric (see
 * `RadarSourceRecycleTest` for the same reasoning). A behavioural test here
 * would assert nothing.
 *
 * The invariant is anyway a property of the source and can be checked
 * exactly: **the capture's reference feed must not depend on
 * `headingDegrees`.** It must use course over ground, which comes straight
 * off the location fix and needs no sensors.
 *
 * That also happens to be the more correct signal — yaw rate is about where
 * the CAR is going, not where the phone is pointing.
 */
class MsCanReferenceWiringTest {

    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    /** Lines with the comment portion removed, so prose cannot trip the check. */
    private fun codeLines(f: File): List<String> =
        f.readLines()
            .map { it.substringBefore("//") }
            .filter { it.isNotBlank() }

    private fun probeActivity() =
        sourceFile("src/main/kotlin/dev/swordfish/ui/ProbeActivity.kt")

    private fun imuSource() =
        sourceFile("src/main/kotlin/dev/swordfish/obd/ImuSource.kt")

    /**
     * The regression itself.
     *
     * `headingDegrees` is sensor-backed. Reading it from a screen that only
     * started location is what produced 2120 dropped frames.
     */
    @Test
    fun `the capture reference feed does not read headingDegrees`() {
        val offenders = codeLines(probeActivity())
            .filter { it.contains("headingDegrees") }

        assertTrue(
            offenders.isEmpty(),
            "ProbeActivity must not use ImuSource.headingDegrees for the " +
                "MS-CAN reference. It is written ONLY from the rotation-vector " +
                "sensor callback, so it stays null unless start() has " +
                "registered the sensors -- and the capture screen calls " +
                "startLocation() alone. On 2026-08-26 that dropped all 2120 " +
                "captured frames and saved no file. Use " +
                "courseOverGroundDegrees, which needs no sensors.\n" +
                "Offending lines:\n  " + offenders.joinToString("\n  ")
        )
    }

    /** The positive half: the feed really does use course over ground. */
    @Test
    fun `the capture reference feed uses course over ground`() {
        val code = codeLines(probeActivity())

        assertTrue(
            code.any { it.contains("courseOverGroundDegrees") },
            "ProbeActivity must derive the MS-CAN reference from " +
                "ImuSource.courseOverGroundDegrees."
        )
    }

    /**
     * Course over ground must stay sensor-independent.
     *
     * Its whole value is that it works after `startLocation()` alone. If it
     * ever starts depending on the rotation vector it reintroduces the bug
     * silently, so pin where it is written from.
     */
    @Test
    fun `course over ground is derived only from the location fix`() {
        val text = imuSource().readText()

        val accessor = Regex(
            """val courseOverGroundDegrees:\s*Double\?\s*\n\s*get\(\) =([^\n]*)"""
        ).find(text)

        assertTrue(
            accessor != null,
            "ImuSource must expose courseOverGroundDegrees as the " +
                "sensor-independent reference for the MS-CAN capture."
        )

        val body = accessor!!.groupValues[1]
        assertTrue(
            body.contains("gpsBearing"),
            "courseOverGroundDegrees must come from the GPS bearing, which " +
                "is set in the location callback. Was: $body"
        )
        assertTrue(
            !body.contains("heading") && !body.contains("rotationMatrix"),
            "courseOverGroundDegrees must not depend on sensor-backed state; " +
                "that is the dependency this whole guard exists to prevent. " +
                "Was: $body"
        )
    }

    /**
     * `gpsBearingDegrees` must keep being written from the location callback.
     *
     * If that assignment moved into a sensor path, course over ground would
     * quietly become sensor-dependent again while still reading correctly.
     */
    @Test
    fun `the gps bearing is still written from the location callback`() {
        val text = imuSource().readText()

        // The guard that stops a stationary fix claiming due north, which
        // sits immediately above the assignment in onLocation.
        assertTrue(
            text.contains("loc.hasBearing()") &&
                text.contains("gpsBearingDegrees = loc.bearing"),
            "gpsBearingDegrees must be assigned from the Location callback " +
                "(guarded by hasBearing() and a minimum speed). Course over " +
                "ground depends on it being location-backed."
        )
    }

    /**
     * The probe screen must not read OBD-fed speed either.
     *
     * `ImuSource.speedMps` is written by GaugeScreen and MainActivity from
     * OBD samples. During an MS-CAN capture BOTH are dead -- the capture owns
     * the socket -- so it is null for the entire capture. Using it to decide
     * whether the car is moving would make the health verdict permanently say
     * "parked", hiding a broken capture behind a state that looks normal.
     *
     * Exactly the `headingDegrees` trap wearing a different hat.
     */
    @Test
    fun `the probe screen does not use OBD-fed speed`() {
        val offenders = codeLines(probeActivity())
            .filter { it.contains("imu.speedMps") }

        assertTrue(
            offenders.isEmpty(),
            "ProbeActivity must not read ImuSource.speedMps. It is fed by the " +
                "OBD telemetry path, which is stopped for the whole of an " +
                "MS-CAN capture, so it is always null there. Use " +
                "freshGpsSpeedMps, which comes off the location fix.\n" +
                "Offending lines:\n  " + offenders.joinToString("\n  ")
        )
    }

    /**
     * The status line must LEAD with a verdict, not with counts.
     *
     * The old line rendered the 2026-08-26 failure as "frames=735 paired=0" --
     * legible, technically complete, and missed for two whole captures,
     * because it was a number among numbers rather than a judgement. The
     * verdict from `MsCanCapture.health` now comes first.
     */
    @Test
    fun `the status line leads with a health verdict`() {
        val text =
            sourceFile("src/main/kotlin/dev/swordfish/obd/MsCanSession.kt")
                .readText()

        val body = text.substringAfter("fun status(): String")
            .substringBefore("private companion object")

        assertTrue(
            body.contains("capture.health("),
            "MsCanSession.status() must lead with MsCanCapture.health(), " +
                "which distinguishes parked from broken. Counts alone hid " +
                "the 2026-08-26 failure for a whole drive."
        )

        val verdictAt = body.indexOf("capture.health(")
        val countsAt = body.indexOf("frames=")
        assertTrue(
            verdictAt in 0 until countsAt,
            "the verdict must be appended BEFORE the counts so it is the " +
                "first thing read from the driver's seat."
        )
    }

    /**
     * The health verdict must know whether the car is moving.
     *
     * Without it, "parked so no bearing yet" and "wiring broken" are the
     * same output -- and reading the second as the first is precisely the
     * mistake that wasted the drive.
     */
    @Test
    fun `the session is told whether the car is moving`() {
        val session = sourceFile(
            "src/main/kotlin/dev/swordfish/obd/MsCanSession.kt"
        ).readText()

        assertTrue(
            session.contains("fun onMoving("),
            "MsCanSession must expose onMoving() so the health verdict can " +
                "tell a parked car from a broken capture."
        )

        assertTrue(
            codeLines(probeActivity()).any { it.contains("onMoving(") },
            "ProbeActivity's reference feed must report movement every tick, " +
                "including ticks with no bearing -- that is the case the " +
                "verdict exists to explain."
        )
    }
}
