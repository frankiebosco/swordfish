package dev.swordfish.physics

/**
 * Pairs MS-CAN frames with the reference signal measured at the same moment.
 *
 * ## The gap this fills
 *
 * `MsCanProbe` captures frames and `MsCanIdentify` correlates them, but
 * nothing joined the two: frames were recorded with no idea what the car was
 * doing when they arrived, so there was nothing to correlate against.
 *
 * This holds the phone's current reference value and stamps it onto every
 * frame as it lands. Reference values arrive at ~1 Hz from GPS while frames
 * arrive far faster, so each frame takes the most recent one -- which is
 * correct, because the reference is a continuous quantity being sampled, not
 * an event.
 *
 * ## Why it is a normal drive and not a car park
 *
 * See [MsCanIdentify]: an ordinary ridge-road run supplies 1371 usable yaw samples,
 * 44 corners above 0.15 rad/s and turns in both directions. Correlation needs
 * variance, not choreography.
 *
 * ## Staleness matters more than it looks
 *
 * A reference from ten seconds ago describes a corner the car has already
 * left, and pairing it with current frames would blur the correlation toward
 * zero -- making a real signal look absent. Frames arriving without a fresh
 * Frames arriving without a fresh reference are DROPPED rather than paired
 * with a stale one.
 *
 * ## Thread safety -- this is NOT optional here
 *
 * Frames are written from the capture thread (`swordfish-mscan`) while the UI
 * reads counts and verdicts from the main thread once a second. An unguarded
 * `ArrayList` under that pattern throws `ConcurrentModificationException` from
 * whichever read happens to be iterating when a frame lands.
 *
 * That is not theoretical: on 2026-08-26 it crashed the app four times during
 * a drive, from `idCounts`, from `health` and from `readiness`. The race
 * pre-existed in `readiness`, but nothing called it every second until a live
 * status poll was added -- which turned a dormant bug into a crash per second.
 *
 * **Every access to [observations] and the reference fields goes through
 * [lock].** Adding a read path that forgets it reintroduces the crash.
 */
class MsCanCapture(
    /**
     * How old a reference may be and still be stamped onto a frame.
     *
     * GPS bearing updates about once a second, so 1.5 s allows one missed
     * update without letting a genuinely stale value through.
     */
    private val maxReferenceAgeMs: Long = 1_500L,
    /** Cap on retained observations, so a long drive cannot exhaust memory. */
    private val maxObservations: Int = 60_000
) {

    /**
     * Guards [observations] and the reference pair.
     *
     * A plain lock rather than a concurrent collection: the reads are
     * whole-list scans that must see a consistent snapshot, which a
     * `CopyOnWriteArrayList` gives only at the cost of copying the entire
     * list on every one of ~28 frames per second.
     */
    private val lock = Any()

    private val observations = ArrayList<MsCanIdentify.Observation>()

    private var reference: Double? = null
    private var referenceAtMs: Long = 0L

    /** Frames seen but dropped for want of a fresh reference. */
    var droppedNoReference: Int = 0
        private set

    /** Frames dropped because the buffer was full. */
    var droppedFull: Int = 0
        private set

    val size: Int get() = synchronized(lock) { observations.size }

    /**
     * Whether a reference value has EVER been supplied.
     *
     * Distinguishes "the feed is not wired up" from "the car is stationary",
     * which look identical from the dropped-frame count alone. The 2026-08-26
     * failure was the former and was mistaken for the latter for a whole
     * drive.
     */
    val hasEverHadReference: Boolean get() = synchronized(lock) { reference != null }

    /** Frames offered, whether kept or dropped. */
    val framesOffered: Int
        get() = synchronized(lock) {
            observations.size + droppedNoReference + droppedFull
        }

    /**
     * How old the newest reference is, or null if none has arrived.
     *
     * The caller supplies the clock so this stays testable and so the
     * physics module keeps no dependency on wall time.
     */
    fun referenceAgeMs(nowMs: Long): Long? = synchronized(lock) {
        if (reference == null) null else nowMs - referenceAtMs
    }

    /**
     * Whether the reference is fresh enough that frames are being kept.
     *
     * The same test [onFrame] applies, exposed so a UI can show the state
     * BEFORE a drive is wasted rather than reporting it afterwards.
     */
    fun referenceIsFresh(nowMs: Long): Boolean = synchronized(lock) {
        reference != null && nowMs - referenceAtMs <= maxReferenceAgeMs
    }

    /**
     * Update what the car is currently doing.
     *
     * Called at the reference signal's own rate -- roughly 1 Hz for a
     * GPS-derived yaw rate.
     */
    fun onReference(value: Double, atMs: Long) = synchronized(lock) {
        reference = value
        referenceAtMs = atMs
    }

    /**
     * Record a frame, stamped with the current reference.
     *
     * @return true when the frame was kept.
     */
    fun onFrame(canId: String, data: List<Int>, atMs: Long): Boolean =
        synchronized(lock) {
            val ref = reference
            if (ref == null || atMs - referenceAtMs > maxReferenceAgeMs) {
                droppedNoReference++
                return@synchronized false
            }
            if (observations.size >= maxObservations) {
                droppedFull++
                return@synchronized false
            }
            observations += MsCanIdentify.Observation(canId, data, ref)
            true
        }

    /** Everything collected, for [MsCanIdentify.identify]. */
    fun observations(): List<MsCanIdentify.Observation> =
        synchronized(lock) { observations.toList() }

    /**
     * Distinct CAN IDs seen, with how many observations each has.
     *
     * Useful before running the correlation: an ID with 12 observations
     * cannot be scored whatever its bytes do.
     */
    fun idCounts(): Map<String, Int> = synchronized(lock) {
        observations.groupingBy { it.canId }.eachCount()
    }

    /**
     * Live verdict on whether the capture is currently working.
     *
     * ## Why this is separate from [readiness]
     *
     * [readiness] judges the FINISHED dataset -- enough samples per ID, both
     * turn directions. It is the right question at the end of a drive and the
     * wrong one at the start, where it says NO DATA no matter how healthy the
     * capture is.
     *
     * This answers the question that actually matters while driving: **is
     * what I am doing right now producing usable data?** On 2026-08-26 the
     * answer was no for two full captures and nothing said so.
     *
     * @param nowMs current wall clock.
     * @param moving whether the car is above the speed at which a GPS bearing
     *   is meaningful. Below it, no reference is EXPECTED and a stalled
     *   capture is not a fault.
     */
    fun health(nowMs: Long, moving: Boolean): String {
        // Snapshot once. See the note in readiness: this crashed too.
        val snapshot = observations()
        val paired = snapshot.size
        val offered = synchronized(lock) {
            observations.size + droppedNoReference + droppedFull
        }

        if (offered == 0) {
            return "WAITING — no CAN frames yet. Check the adapter is on the " +
                "MS-CAN bus."
        }

        if (!hasEverHadReference) {
            return if (!moving) {
                "WAITING TO MOVE — $offered frames seen. A GPS bearing needs " +
                    "road speed; nothing pairs until then. This is normal in " +
                    "a car park."
            } else {
                "BROKEN — $offered frames seen and the car is moving, but no " +
                    "reference has EVER arrived. Nothing is being saved. Stop " +
                    "and fix this rather than driving the route."
            }
        }

        if (!referenceIsFresh(nowMs)) {
            val age = referenceAgeMs(nowMs) ?: 0L
            return if (!moving) {
                "PAUSED — stationary, so the bearing has gone stale " +
                    "(${age / 1000}s). Frames are dropped until you move " +
                    "again. $paired paired so far."
            } else {
                "REFERENCE STALE — last bearing ${age / 1000}s ago while " +
                    "moving. GPS may have dropped. $paired paired so far."
            }
        }

        val pct = if (offered > 0) paired * 100 / offered else 0
        val ids = snapshot.groupingBy { it.canId }.eachCount().size
        return "WORKING — $paired paired of $offered frames ($pct%), " +
            "$ids IDs, reference fresh."
    }

    fun clear() = synchronized(lock) {
        observations.clear()
        droppedNoReference = 0
        droppedFull = 0
        reference = null
        referenceAtMs = 0L
    }

    /** One-line state, for the log. */
    fun summary(): String =
        "obs=$size ids=${idCounts().size} " +
            "droppedNoRef=$droppedNoReference droppedFull=$droppedFull"

    /**
     * Whether there is enough here to trust a correlation.
     *
     * Deliberately strict about BOTH SIGNS. A drive that only ever turned
     * left exercises half a signed sensor's range, and a byte can correlate
     * beautifully across that half while being wrong about the other -- the
     * sign convention would be unresolvable and a left-only fit would be
     * projected onto right turns.
     */
    fun readiness(minPerId: Int = 30): String {
        // ONE snapshot, then work off it.
        //
        // This iterated the live list three times and crashed the app on
        // 2026-08-26 when a frame landed mid-scan. Holding the lock for the
        // whole body would instead stall the capture thread while strings are
        // built, so copy once and release.
        val snapshot = observations()

        if (snapshot.isEmpty()) return "NO DATA — no frames paired with a reference"
        val counts = snapshot.groupingBy { it.canId }.eachCount()
        val usable = counts.count { it.value >= minPerId }
        if (usable == 0) {
            return "TOO FEW — ${counts.size} IDs seen, none with $minPerId+ samples"
        }
        val refs = snapshot.map { it.reference }
        val pos = refs.count { it > 0.15 }
        val neg = refs.count { it < -0.15 }
        return when {
            pos < 5 || neg < 5 ->
                "ONE-SIDED — $pos strong-positive and $neg strong-negative " +
                    "samples. Drive a route that turns BOTH ways, or the sign " +
                    "convention cannot be resolved."
            else ->
                "READY — $usable IDs with $minPerId+ samples, $pos left and " +
                    "$neg right manoeuvres"
        }
    }
}
