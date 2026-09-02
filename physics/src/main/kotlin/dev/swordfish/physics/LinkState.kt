package dev.swordfish.physics

/**
 * Where the telemetry link has got to, as a single value the panel can show.
 *
 * ## Why the panel needs this
 *
 * On the first drive there are three untested links — Android Auto, the head
 * unit, and the dongle — and the panel is the only thing visible while
 * driving. Without a link state, all failures look identical: dashes where
 * numbers should be. That is indistinguishable from "the car is stationary",
 * "the dongle is unpaired", and "the app crashed on the phone".
 *
 * The states below are ordered by how far the connection has got, so a
 * single label answers "what is it waiting for" without any further
 * interpretation.
 *
 * The panel shows this **only when the link is not [LIVE]**. A working
 * instrument should not spend pixels announcing that it is working — the
 * numbers moving is the announcement.
 */
enum class LinkState(val label: String, val hint: String) {

    /** No socket. The usual cause is the dongle being unpaired or unpowered. */
    NO_ADAPTER("NO ADAPTER", "dongle not connected"),

    /** Socket open, ELM handshake in progress. */
    HANDSHAKE("HANDSHAKE", "configuring adapter"),

    /**
     * Adapter answering, ECU silent.
     *
     * Almost always ignition off or accessory mode. Worth a distinct state
     * because the remedy is a key turn, not a debugging session, and the
     * generic "no data" would send the driver looking for a fault that is
     * not there.
     */
    NO_VEHICLE("IGNITION?", "adapter OK, ECU not answering"),

    /** Probing which PIDs the car supports. Brief, on connect only. */
    CAPABILITIES("PROBING", "reading supported PIDs"),

    /** Telemetry flowing. The only state the panel does not announce. */
    LIVE("LIVE", "telemetry flowing"),

    /**
     * Was live, has stopped.
     *
     * Distinct from [NO_ADAPTER] because it means something *broke* rather
     * than never started — which is the difference between "check the
     * pairing" and "the link dropped mid-drive", and the latter is a finding
     * worth keeping.
     */
    LOST("LINK LOST", "telemetry stopped"),

    /** Nothing real is connected; the panel is showing a sample frame. */
    DEMO("DEMO", "sample data, not the car");

    /** True when the panel should surface this state to the driver. */
    val shouldAnnounce: Boolean get() = this != LIVE

    /**
     * True when the state warrants attention rather than merely reporting
     * progress.
     *
     * [HANDSHAKE] and [CAPABILITIES] are transient steps on a healthy
     * connect and should not be styled as faults — flashing a warning
     * colour for the 200 ms of a normal handshake trains the driver to
     * ignore the indicator.
     */
    val isFault: Boolean
        get() = this == NO_ADAPTER || this == NO_VEHICLE || this == LOST
}
