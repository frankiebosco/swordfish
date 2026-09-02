package dev.swordfish.harness

import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.SurfaceContainer
import dev.swordfish.car.GaugeRenderer
import dev.swordfish.car.PanelState
import dev.swordfish.physics.Attitude
import dev.swordfish.physics.DeltaVModel
import dev.swordfish.physics.DemoFrame
import dev.swordfish.physics.EfficiencyBand
import dev.swordfish.physics.LinkState
import dev.swordfish.physics.MountAutoCalibrator
import dev.swordfish.physics.PanelLayout
import dev.swordfish.physics.RadarLayout
import dev.swordfish.physics.Units
import dev.swordfish.physics.Vehicle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the REAL gauge panel to a PNG on the desktop.
 *
 * `./gradlew :layout-harness:run` writes one PNG per case to
 * `tools/layout-harness/out/`. No phone, no car, no DHU, about a second.
 *
 * See `android/graphics/Primitives.kt` for how this is possible and what
 * its fidelity limits are.
 */

/**
 * A surface to render into.
 *
 * The ND2 numbers are not invented: they are what the head unit reported in
 * `SwordfishGeom` on the 2026-08-24 drive. Note that the stable area is NOT
 * the container -- 752x300 of an 800x400 surface, with an 88px top inset.
 * Deriving this by measuring a DHU window has been tried and got it wrong,
 * so these stay pinned to observed values.
 */
data class Geometry(
    val name: String,
    val width: Int,
    val height: Int,
    val stable: Rect
) {
    /** True when this surface resolves to the NARROW/collapsed breakpoint. */
    val isNarrow: Boolean
        get() = dev.swordfish.physics.PanelLayout
            .choose(stable.width(), stable.height()).mode !=
            dev.swordfish.physics.PanelLayout.Mode.WIDE

    companion object {
        /** The real Mazda Connect surface, settled state. */
        val ND2 = Geometry("nd2", 800, 400, Rect(24, 88, 776, 388))

        /**
         * The COLLAPSED (split-screen) surface, as the head unit really
         * reports it: an 800x400 container with a 442x342 stable area.
         *
         * Aspect 1.29, below the 1.5 WIDE threshold, so this is the NARROW
         * breakpoint -- the layout that was disturbed when WIDE and NARROW
         * shared tuning knobs. Confirmed from SwordfishGeom on 2026-08-24;
         * an earlier invented 480x400 was close but not what the DHU does.
         */
        val COLLAPSED = Geometry("collapsed", 800, 400, Rect(22, 34, 464, 376))

        /**
         * The TRANSIENT state seen while the action strip is open.
         *
         * Reported from the car 2026-08-24: opening the radar range control
         * made elements jump out of bounds. The head unit hands over a
         * DIFFERENT stable area during that interaction -- 442x342 instead
         * of 752x300 -- while the instruments keep laying out against the
         * full 800px surface. The stats column is anchored to
         * `statsArea.right`, so it leaps 312px left while the circles stay
         * put.
         */
        val ACTION_STRIP_OPEN =
            Geometry("action-strip-open", 800, 400, Rect(22, 34, 464, 376))

        /** A tall/narrow surface, to exercise NARROW at another size. */
        val NARROW = Geometry("narrow", 480, 400, Rect(0, 0, 480, 400))

        /** Short surface, to exercise MINIMAL degradation. */
        val MINIMAL = Geometry("minimal", 800, 180, Rect(0, 0, 800, 180))

        val ALL = listOf(ND2, COLLAPSED, NARROW, MINIMAL)
    }
}

/** One thing worth looking at: a geometry, a state, and the mode toggles. */
data class Case(
    val label: String,
    val geometry: Geometry,
    val state: PanelState,
    val darkMode: Boolean = true,
    val centre: RadarLayout.CentreContent = RadarLayout.CentreContent.INSTRUMENTS
)

/**
 * Render with the tuner's live overrides applied.
 *
 * Installs [LayoutOverride] as PanelLayout's tuning hook for the duration of
 * the draw, so the REAL renderer lays out using whatever is currently being
 * dragged. The hook is cleared afterwards so a snapshot run is never
 * contaminated by a tuner session in the same JVM.
 */
fun renderTuned(case: Case): BufferedImage {
    PanelLayout.tuningHook = { name, fallback -> LayoutOverride.of(name, fallback) }
    try {
        return render(case)
    } finally {
        PanelLayout.tuningHook = null
    }
}

/** Render one case and return the image. */
fun render(case: Case): BufferedImage {
    val img = BufferedImage(
        case.geometry.width, case.geometry.height, BufferedImage.TYPE_INT_ARGB
    )
    val canvas = Canvas(img)

    val renderer = GaugeRenderer(CarContext(isDarkMode = case.darkMode))
    renderer.centreContent = case.centre
    // Feed the stable area through the real callback the host would use.
    renderer.onStableAreaChanged(case.geometry.stable)

    renderer.update(case.state)
    renderer.draw(canvas, SurfaceContainer(case.geometry.width, case.geometry.height))
    canvas.dispose()
    return img
}

/**
 * A realistic panel state, built through the REAL model pipeline.
 *
 * `PanelState.EMPTY` is useless as a layout preview: every readout dashes
 * out, so nothing occupies its true width and the crowding this tool exists
 * to find is invisible. So the telemetry comes from [DemoFrame] -- the same
 * frames the phone-side demo mode uses -- and goes through
 * `DeltaVModel.compute` rather than having numbers typed in here. Numbers
 * invented in the harness would be a second source of truth and would drift.
 */
fun demoState(
    frame: DemoFrame = DemoFrame.CRUISE,
    live: Boolean = true
): PanelState {
    val car = Vehicle.ND2_CLUB
    val sample = frame.telemetry()
    val readout = DeltaVModel.compute(car, sample)

    return PanelState(
        readout = readout,
        efficiency = EfficiencyBand.Assessment(
            fill = 0.72, inSweetSpot = true, isPersonalBest = false
        ),
        attitude = Attitude.Reading(
            pitchRadians = Math.toRadians(2.5),
            rollRadians = Math.toRadians(-4.0),
            lateralG = 0.18,
            longitudinalG = -0.05
        ),
        speedMps = sample.speedMps,
        rpm = sample.rpm,
        gear = 5,
        twr = 0.42,
        totalMassKg = car.totalMassKg(sample.fuelRemainingKg),
        fuelGallons = Units.kgToGallons(sample.fuelRemainingKg),
        thermalEfficiency = 0.31,
        tripStartDeltaV = readout.deltaVRemaining * 1.18,
        inFuelCutoff = readout.inDeceleratingFuelCutoff,
        isLive = live,
        linkState = if (live) LinkState.LIVE else LinkState.NO_ADAPTER,
        headingDegrees = 118.0,
        hasLocationFix = true,
        mountState = MountAutoCalibrator.State.CALIBRATED,
        altitudeM = 34.0,
        coolantC = 88.0
    )
}

/**
 * The default set: every geometry in both centre modes.
 *
 * Kept deliberately small. This is the set worth LOOKING at after a layout
 * change, not an exhaustive matrix -- a hundred snapshots nobody opens is
 * the same as no snapshots.
 */
fun defaultCases(): List<Case> {
    val state = demoState()
    val cases = mutableListOf<Case>()
    for (geom in Geometry.ALL) {
        cases += Case("${geom.name}-instruments", geom, state)
        cases += Case(
            "${geom.name}-radar", geom, state,
            centre = RadarLayout.CentreContent.RADAR
        )
    }
    return cases
}

fun main(args: Array<String>) {
    val outDir = File(if (args.isNotEmpty()) args[0] else "tools/layout-harness/out")
    outDir.mkdirs()

    val cases = defaultCases()
    for (case in cases) {
        val img = render(case)
        val f = File(outDir, "${case.label}.png")
        ImageIO.write(img, "PNG", f)
        println("wrote ${f.path}  (${img.width}x${img.height})")
    }
    println("\n${cases.size} snapshots -> ${outDir.absolutePath}")
}
