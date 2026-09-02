package dev.swordfish.physics

/**
 * The MS-CAN capture state, condensed to fit the panel's status headline.
 *
 * ## Why this exists
 *
 * While a capture runs the phone owns the socket, so the panel has no
 * telemetry and shows `LINK LOST` — which is true, and useless. It says
 * nothing about the thing the driver actually started, and answering "is it
 * even running?" meant picking up the phone at the wheel.
 *
 * So the headline slot is reused: while a capture is running it reports the
 * capture instead of the link. Same position, same paint, same one line —
 * only the text changes.
 *
 * ## Why the text is this short
 *
 * `MsCanCapture.health` is written for a phone screen held in the hand and
 * runs to a full sentence. The panel headline is one short line read at a
 * glance at speed, so each state collapses to a few characters plus the one
 * number that matters. The phone still shows the long form.
 *
 * The label answers, in order of what the driver needs:
 *
 * | Label | Meaning |
 * |---|---|
 * | `MS-CAN OFF` | not running |
 * | `MS-CAN WAIT` | running, no frames yet |
 * | `MS-CAN PARKED` | frames arriving, car not moving — normal |
 * | `MS-CAN NO REF` | **moving with no reference — nothing is being saved** |
 * | `MS-CAN STALE` | bearing aged out while moving |
 * | `MS-CAN 1234` | working; the number is frames paired |
 */
object MsCanBanner {

    /** Shown when no capture is running, so the caller can fall back. */
    const val OFF = "MS-CAN OFF"

    /**
     * One-line capture state for the panel headline.
     *
     * Takes the same verdict string [MsCanCapture.health] produces rather than
     * re-deriving the state, so the phone and the panel can never disagree
     * about what is happening — they are the same judgement, rendered at two
     * lengths.
     *
     * @param health the verdict from [MsCanCapture.health].
     * @param paired how many frames have been paired, for the working case.
     */
    fun label(health: String, paired: Int): String = when {
        health.startsWith("WORKING") -> "MS-CAN $paired"
        health.startsWith("BROKEN") -> "MS-CAN NO REF"
        health.startsWith("REFERENCE STALE") -> "MS-CAN STALE"
        health.startsWith("PAUSED") -> "MS-CAN PARKED"
        health.startsWith("WAITING TO MOVE") -> "MS-CAN PARKED"
        health.startsWith("WAITING") -> "MS-CAN WAIT"
        else -> "MS-CAN ?"
    }

    /**
     * The second line, shown only for states that need a remedy.
     *
     * Mirrors [LinkState.hint]: transient and healthy states get nothing,
     * because a hint under every state trains the driver to stop reading it.
     */
    fun hint(health: String): String = when {
        health.startsWith("BROKEN") -> "nothing saved — stop"
        health.startsWith("REFERENCE STALE") -> "gps dropped"
        health.startsWith("WAITING TO MOVE") -> "needs road speed"
        health.startsWith("WAITING") -> "no frames — check adapter"
        else -> ""
    }

    /**
     * Whether this state warrants the fault colour.
     *
     * Deliberately narrow. `PARKED` is the normal state at the start of every
     * capture and in every traffic queue; colouring it as a fault would make
     * the amber meaningless within one drive.
     *
     * Only two states are real problems: frames arriving while moving with no
     * reference (nothing is being saved), and no frames at all.
     */
    fun isFault(health: String): Boolean =
        health.startsWith("BROKEN") ||
            health.startsWith("REFERENCE STALE") ||
            (health.startsWith("WAITING") && !health.startsWith("WAITING TO MOVE"))
}
