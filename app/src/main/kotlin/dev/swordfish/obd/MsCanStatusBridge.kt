package dev.swordfish.obd

/**
 * Publishes MS-CAN capture state so the car panel can show it.
 *
 * ## Why a bridge is needed at all
 *
 * The capture is started from `ProbeActivity` on the phone; the panel is drawn
 * by `GaugeScreen` on the head unit. They are separate host-driven lifecycles
 * in one process, and neither can own the other — exactly the situation
 * `TelemetryService.poller` already solves for the OBD poller, so this follows
 * that pattern rather than inventing a new one.
 *
 * ## Why the panel wants it
 *
 * A running capture takes the socket, so the panel loses telemetry and
 * announces `LINK LOST`. True, and useless: it says nothing about the thing
 * the driver deliberately started, and checking on it meant looking at the
 * phone while driving. The panel's existing status headline now reports the
 * capture instead, for as long as one is running.
 *
 * Values are `@Volatile` and written from the status poll on the main thread,
 * read by the panel on its own render thread. Nothing here is a compound
 * update, so no lock is needed — but see `MsCanCapture` for what happens when
 * that assumption is made carelessly about a collection.
 */
object MsCanStatusBridge {

    /**
     * The live verdict, exactly as `MsCanCapture.health` phrased it, or null
     * when no capture is running.
     *
     * Null is the signal to the panel that it should show the link state as
     * usual. Publishing the raw verdict rather than a pre-formatted label
     * keeps the phone and the panel from ever disagreeing: they render the
     * same judgement at two different lengths.
     */
    @Volatile
    var health: String? = null
        private set

    /** Frames paired so far, for the working label. */
    @Volatile
    var paired: Int = 0
        private set

    /** True while a capture owns the socket. */
    val isCapturing: Boolean get() = health != null

    /** Called from the capture's status poll, about once a second. */
    fun publish(health: String, paired: Int) {
        this.paired = paired
        this.health = health
    }

    /**
     * Called when the capture ends, so the panel reverts to the link state.
     *
     * Must be called on every exit path — a stopped capture that keeps
     * publishing would leave the panel permanently reporting a capture that
     * is not running, which is worse than never having shown it.
     */
    fun clear() {
        health = null
        paired = 0
    }
}
