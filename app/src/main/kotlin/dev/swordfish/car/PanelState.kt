package dev.swordfish.car

import dev.swordfish.physics.Attitude
import dev.swordfish.physics.DeltaVModel
import dev.swordfish.physics.EfficiencyBand
import dev.swordfish.physics.LinkState
import dev.swordfish.physics.MountAutoCalibrator
import dev.swordfish.physics.OperatingState

/**
 * Everything the panel draws, in one immutable snapshot.
 *
 * The renderer reads only from this. Keeping it a plain value type means the
 * telemetry thread can build a new snapshot and hand it over atomically,
 * without the renderer ever seeing a half-updated state — a real hazard when
 * OBD samples arrive at 10 Hz on a background thread while the surface repaints
 * independently.
 *
 * Nullable fields mean "not available yet" and the renderer shows a dash rather
 * than a zero. That distinction matters: 0 Isp and unknown Isp look identical
 * on a gauge but mean very different things.
 */
data class PanelState(
    val readout: DeltaVModel.Readout? = null,
    val efficiency: EfficiencyBand.Assessment? = null,
    val attitude: Attitude.Reading? = null,

    val speedMps: Double? = null,
    val rpm: Double? = null,
    val gear: Int? = null,
    val twr: Double? = null,
    val totalMassKg: Double? = null,
    val fuelGallons: Double? = null,
    val thermalEfficiency: Double? = null,

    /** True while the ECU has cut fuel on a trailing throttle. */
    /**
     * Delta-V at the start of this drive, m/s, or null before it is set.
     *
     * A jet's budget only means something against where it started. Seeing
     * 3,075 tells you nothing on its own; seeing it under a 7,129 start
     * tells you the trip cost 4,054 -- more than half the tank's budget.
     * That comparison is the whole reward loop, and it was missing.
     *
     * Captured once per drive on the first live sample, not on app launch:
     * the panel can be up for minutes in a driveway before anyone moves.
     */
    val tripStartDeltaV: Double? = null,
    val inFuelCutoff: Boolean = false,

    /** True once real telemetry is arriving; false while showing a sample. */
    val isLive: Boolean = false,

    /**
     * How far the OBD link has got.
     *
     * Distinct from [isLive], which is a bool and therefore cannot tell the
     * driver *why* there is no data. On a first drive that distinction is
     * the whole diagnostic: an unpaired dongle, an ignition in accessory
     * mode, and a mid-drive dropout all render as dashes otherwise.
     */
    val linkState: LinkState = LinkState.DEMO,

    /**
     * MS-CAN capture state, or null when no capture is running.
     *
     * When present it REPLACES the link state in the status headline. A
     * capture owns the socket, so the link is legitimately lost and saying so
     * describes a consequence of what the driver started rather than the
     * thing itself -- which is not what they need at the wheel.
     *
     * Same slot, same paint, same baselines: see [GaugeRenderer.drawLinkBanner].
     */
    val msCanBanner: MsCanBannerText? = null,

    /**
     * What the car is doing, as distinct from what the numbers read.
     *
     * Isp is zero in three unrelated situations — idling, coasting with the
     * injectors shut, and descending steeply enough that road load goes
     * negative — and rendering all three as a dash throws away the most
     * interesting thing the panel could say. See [OperatingState].
     */
    val operatingState: OperatingState = OperatingState.CRUISE,

    /**
     * Current burn, kg/s. Null when unknown.
     *
     * Displayed in place of Isp while idling: an engine at the hold line
     * shows fuel flow, because flow is the meaningful number when thrust is
     * doing nothing.
     */
    val fuelFlowKgPerSec: Double? = null,

    /**
     * How far the phone-orientation calibration has got.
     *
     * Surfaced on the panel because an uncalibrated navball is not merely
     * imprecise, it is meaningless -- and a confidently wrong horizon is worse
     * than an honest dash.
     */
    val mountState: MountAutoCalibrator.State = MountAutoCalibrator.State.UNCALIBRATED,

    /**
     * Road grade from the barometer, radians. Positive uphill.
     *
     * Preferred over accelerometer pitch for the navball horizon: this
     * describes how the ROAD is lying, where accelerometer pitch describes
     * how the PHONE is lying — which on a passenger seat means nothing.
     * Null until the barometer and a speed source have both produced data.
     */
    val gradeRadians: Double? = null,

    /**
     * Fused altitude in metres, or null before the barometer has settled.
     *
     * RELATIVE altitude is excellent — the phone barometer resolves about
     * 10 cm, so a climb is tracked to within a metre or two. ABSOLUTE
     * altitude is only as good as the sea-level pressure reference: each
     * hPa of error in it is ~8.3 m, and real weather spans 980-1040 hPa.
     * GPS is what pins the absolute value; the barometer supplies the
     * change between fixes.
     */
    val altitudeM: Double? = null,

    /**
     * Battery / alternator bus voltage, or null when unavailable.
     *
     * The ND has no voltmeter at all, so this is information the driver
     * cannot otherwise get. See [ElectricalState] for why the same number
     * means state-of-charge with the engine off and alternator output with
     * it running.
     */
    val busVolts: Double? = null,

    /** Coolant temperature in C, or null when unavailable. */
    val coolantC: Double? = null,

    /** Compass heading in degrees, or null when unavailable. */
    val headingDegrees: Double? = null,

    /**
     * Whether a usable GPS fix has arrived.
     *
     * The radar scope needs a position to request imagery for, and showing
     * weather for the wrong place is worse than showing none -- so the scope
     * says NO GPS rather than guessing or reusing a fix from a previous
     * drive.
     */
    val hasLocationFix: Boolean = false
) {
    val deltaVMps: Double? get() = readout?.deltaVRemaining
    val effectiveIsp: Double? get() = readout?.effectiveIsp
    val gravityLossWatts: Double? get() = readout?.gravityLossWatts

    companion object {
        val EMPTY = PanelState()
    }
}

/**
 * Whether the status headline is showing anything.
 *
 * THE SINGLE RULE. The renderer reserves vertical space for this strip in
 * three places and draws into it in a fourth; if any of them disagreed, text
 * would land in unreserved space and print over the navball/scope pair. That
 * class of overlap has cost a whole evening before, so they all read this.
 */
val PanelState.announcesStatus: Boolean
    get() = msCanBanner != null || linkState.shouldAnnounce

/**
 * The status headline's text, when an MS-CAN capture is driving it.
 *
 * A tiny value type rather than three loose strings so the renderer cannot
 * pair a label with the wrong hint, and so [PanelState.announcesStatus] has
 * one thing to test.
 */
data class MsCanBannerText(
    val label: String,
    val hint: String,
    val isFault: Boolean
)
