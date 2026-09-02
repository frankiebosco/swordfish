package dev.swordfish.physics

/**
 * Decides what the instrument panel shows, given the space it actually has.
 *
 * ## Why this is in `:physics` and not the Android layer
 *
 * Layout *arithmetic* is pure: given a rectangle and an aspect ratio, decide
 * which elements fit and where they go. Keeping that here means every
 * breakpoint and degradation rule is unit-testable on the JVM, with no head
 * unit and no emulator. The Android side only turns the resulting geometry
 * into Canvas calls.
 *
 * ## The two modes we must survive
 *
 * Android Auto lets the user toggle between them at will by tapping the app
 * button, so neither is "the" layout:
 *
 * - **Full width** — the app owns the whole head-unit screen. Roughly 2:1.
 * - **Split** — the app gets about half, sharing with a media card. Roughly
 *   1:1, sometimes narrower.
 *
 * A three-column arrangement that looks right at full width is unreadable at
 * half. Rather than scaling one design down, [choose] picks a *different*
 * arrangement per aspect ratio, and drops elements in a documented order when
 * the space runs out.
 */
object PanelLayout {

    /**
     * Aspect ratio (width/height) above which the wide arrangement is used.
     *
     * Full-width head units land around 2.0; split view lands near 1.0. 1.5
     * sits comfortably between, with no realistic geometry near the boundary.
     */
    const val WIDE_ASPECT_THRESHOLD = 1.5

    /** Below this height in pixels, drop to the most minimal layout. */
    const val MIN_HEIGHT_FOR_EXTRAS_PX = 260

    enum class Mode {
        /** Three zones: navball left, delta-V centre, stats right. */
        WIDE,

        /** Single column: delta-V, Isp bar, compact stats beneath. */
        NARROW,

        /** Delta-V only. Very small or very short surfaces. */
        MINIMAL
    }

    /**
     * Which elements are shown, in priority order.
     *
     * The order encodes the degradation rule from
     * `docs/INSTRUMENT_PANEL.md`: delta-V is the last thing standing, because
     * one element must be readable in half a second.
     */
    enum class Element {
        DELTA_V,        // never dropped
        ORBITAL_LABEL,  // the "% TO ORBIT" line
        ISP_BAR,        // the hero stat and reward loop
        EFFICIENCY_LAMP,
        STATS_BLOCK,    // TWR, mass, fuel, gear, rpm
        NAVBALL,
        GRAVITY_STRIP,

        /** The radar scope. Only present in [RadarLayout.CentreContent.RADAR]. */
        RADAR
    }

    /**
     * A resolved layout: which mode, which elements, and the fractions that
     * position them.
     *
     * All geometry is expressed as fractions of the stable area so the
     * renderer never deals in absolute pixels.
     */
    data class Resolved(
        val mode: Mode,
        val elements: Set<Element>,
        /** Fraction of width given to the navball column (WIDE only). */
        val navballColumnFraction: Double,
        /** Fraction of width given to the stats column (WIDE only). */
        val statsColumnFraction: Double
    ) {
        fun shows(e: Element): Boolean = e in elements
    }

    /**
     * Choose a layout for the given stable-area dimensions and centre content.
     *
     * Content is applied as a **transform over the instrument layout**, not
     * as a parallel set of breakpoint rules. [choose] decides the geometry
     * exactly as it always has; [forRadar] then rearranges what that geometry
     * carries. A future third content mode costs one more transform rather
     * than a third copy of every threshold.
     *
     * @param widthPx Stable-area width from `onStableAreaChanged`.
     * @param heightPx Stable-area height.
     * @param content What the driver has asked the centre column to show.
     */
    fun choose(
        widthPx: Int,
        heightPx: Int,
        content: RadarLayout.CentreContent
    ): Resolved {
        val base = choose(widthPx, heightPx)
        return if (content == RadarLayout.CentreContent.RADAR) forRadar(base) else base
    }

    /**
     * Rearrange an instrument layout to carry the radar scope.
     *
     * ## The navball is KEPT in both modes (revised 2026-08-23)
     *
     * The first version dropped the navball at full width so the scope could
     * take its column, on the reasoning that a bigger scope beats a second
     * instrument. **Rendered on the DHU that was simply wrong**: the panel
     * looked half-empty, and the instrument the driver had in split view
     * vanished when they widened the app — a mode switch should not cost
     * them an instrument.
     *
     * So both layouts now read the same way, left to right: navball, scope,
     * stats. Full width has the room for all three, and the scope is still
     * far larger than the delta-V column it replaced.
     *
     * MINIMAL refuses radar outright. A surface too short to carry the
     * orbital label cannot carry a scope, and delta-V is still the last thing
     * standing.
     */
    fun forRadar(base: Resolved): Resolved = when (base.mode) {
        Mode.MINIMAL -> base

        // Both modes: the scope replaces the CENTRE column's contents and
        // the navball keeps its own. The gravity strip goes at full width --
        // it is the one element with nowhere to sit once the centre column
        // is a circle rather than a stack of readouts.
        Mode.WIDE -> base.copy(
            elements = base.elements - Element.GRAVITY_STRIP + Element.RADAR
        )

        Mode.NARROW -> base.copy(
            elements = base.elements + Element.RADAR
        )
    }

    /**
     * Choose a layout for the given stable-area dimensions.
     *
     * @param widthPx Stable-area width from `onStableAreaChanged`.
     * @param heightPx Stable-area height.
     */
    fun choose(widthPx: Int, heightPx: Int): Resolved {
        if (widthPx <= 0 || heightPx <= 0) {
            return Resolved(Mode.MINIMAL, setOf(Element.DELTA_V), 0.0, 0.0)
        }

        val aspect = widthPx.toDouble() / heightPx.toDouble()

        // Very short surfaces cannot stack anything under the figure.
        if (heightPx < MIN_HEIGHT_FOR_EXTRAS_PX) {
            return Resolved(
                mode = Mode.MINIMAL,
                elements = setOf(Element.DELTA_V, Element.ORBITAL_LABEL),
                navballColumnFraction = 0.0,
                statsColumnFraction = 0.0
            )
        }

        return if (aspect >= WIDE_ASPECT_THRESHOLD) {
            Resolved(
                mode = Mode.WIDE,
                elements = setOf(
                    Element.DELTA_V,
                    Element.ORBITAL_LABEL,
                    Element.ISP_BAR,
                    Element.EFFICIENCY_LAMP,
                    Element.STATS_BLOCK,
                    Element.NAVBALL,
                    Element.GRAVITY_STRIP
                ),
                // 0.34/0.22, was 0.28/0.24. The navball was WIDTH-bound on
                // every real head-unit geometry -- at 1280x720 the width term
                // capped the radius at 143px while the height term would have
                // allowed 213px, so the ball rendered at two thirds of the
                // size the panel could actually carry. Photographed on the
                // real unit: small, hard to read, with obvious black space
                // below and to its right.
                //
                // The width came from the stats column, which is text-driven
                // and self-scaling, so it absorbs the loss without dropping a
                // row. See navballRadiusPx for the rest of the fix.
                navballColumnFraction = 0.34,
                statsColumnFraction = 0.22
            )
        } else {
            // Split view. The navball is KEPT: an earlier version dropped it
            // and gave delta-V a larger type fraction, on the reasoning that
            // it had less competition for space. That was backwards. Less
            // space means the headline should yield some so other elements
            // can exist at readable sizes, rather than dominating a small
            // surface and leaving everything else cramped.
            //
            // The navball moves beside the figure instead of above it, which
            // uses the width that a centred column wastes.
            Resolved(
                mode = Mode.NARROW,
                elements = setOf(
                    Element.DELTA_V,
                    Element.ORBITAL_LABEL,
                    Element.ISP_BAR,
                    Element.EFFICIENCY_LAMP,
                    Element.STATS_BLOCK,
                    Element.NAVBALL
                ),
                // 0.46, not 0.38. Which axis binds depends on the panel's
                // real width, and the DHU's virtual display is 800x400 --
                // so split view is about 468x400, not the ~800 wide surface
                // the first pass assumed. At 468 the ball goes back to being
                // WIDTH-bound, where 0.38 bought nothing at all.
                //
                // 0.46 is the balance point: the width cap and the height
                // cap meet there (101.2 vs 101.7 px), so neither axis wastes
                // space. Widening further is pure loss to the stats row.
                navballColumnFraction = 0.46,
                statsColumnFraction = 0.0
            )
        }
    }

    /**
     * Type-size fractions of the stable area's height, per mode.
     *
     * **NARROW is smaller than WIDE, deliberately.** The first version had
     * this the other way round — the reasoning being that a split-view panel
     * has less competing for space so the figure could grow. In practice a
     * 30% type fraction on a half-width surface swallowed the panel and left
     * no room for the navball or a legible stat row.
     *
     * A headline that dominates a small surface is not more glanceable; it is
     * just bigger. Yielding a little height buys back a whole instrument.
     */
    /**
     * Runtime override hook, used ONLY by the desktop layout tuner.
     *
     * The stack values below are `const val`s, so a tool cannot poke them to
     * show you a drag in progress. Every read goes through [tuned], which
     * consults this hook first. It is null in the app and in every test, so
     * the shipping build takes the constant and pays a single null check.
     *
     * Deliberately a plain function reference rather than a settable map:
     * `:physics` must not grow state that the app could accidentally depend
     * on, and a null default makes "the app never uses this" checkable.
     */
    @JvmStatic
    var tuningHook: ((String, Float) -> Float)? = null

    /** A tunable value: the override if a tuner is attached, else the constant. */
    fun tuned(name: String, value: Float): Float =
        tuningHook?.invoke(name, value) ?: value

    // --- Per-instrument nudges (navball, scope) ---
    //
    // [pairRadiusPx] deliberately gives BOTH circles ONE radius: two sizing
    // formulas is what produced navR=91.5 against scopeR=103.7 and a rim in
    // the readout row. That rule stays.
    //
    // These are a deliberate deviation ON TOP of it -- a scale and an offset
    // per instrument, defaulting to "no change". The pair stays coherent
    // unless someone decides otherwise, and when they do, the intent is
    // explicit and named rather than a second formula competing with the
    // first.
    //
    // 1.0 / 0.0 everywhere means the pair rule is untouched, so the shipping
    // panel is exactly what it was.

    // Every boxable element gets the same three: a scale and an x/y nudge,
    // all defaulting to no change. Uniform on purpose -- one mental model
    // for the tuner and one shape for the constants, rather than each
    // element inventing its own knobs.
    //
    // dx is a fraction of panel WIDTH, dy a fraction of panel HEIGHT, so a
    // tuned value means the same thing on a surface of another size.
    //
    // The COMPASS has none: it is expressed in navball radii inside
    // drawNavball and scales with the ball as one instrument. That is
    // deliberate and was confirmed as wanted -- do not give it its own box.
    //
    // THESE VALUES WERE DIALLED IN BY HAND on 2026-08-24 in the layout
    // tuner (`./gradlew :layout-harness:tune`), against the real ND2
    // surface. They are NOT arbitrary and they are NOT defaults: changing
    // one changes what the driver sees. Re-tune in the tool rather than
    // nudging a number here and hoping.
    //
    // NAVBALL_* came from the INSTRUMENTS session. An earlier radar session
    // produced slightly different navball values (scale 1.1152, dx +0.02);
    // these constants are shared by both centre modes, so the instruments
    // pass -- the later one, and the mode the ball is judged in -- won.

    // WIDE and NARROW get SEPARATE knobs. This is the whole point.
    //
    // They were shared at first, and it was wrong in a way that only showed
    // on the head unit: a SCOPE_SCALE of 1.59 dialled in against the 800x400
    // full panel was also applied to the collapsed split-screen surface,
    // which has a fraction of the width and none of the slack. The element
    // does not want the same treatment in two layouts that differ that much,
    // so one number cannot serve both.
    //
    // [tunedFor] picks the right one from the mode, so call sites that
    // already know their mode do not each re-implement the choice.

    /**
     * The knob for [name] in [mode]: `NAME` at WIDE, `NAME_N` at NARROW.
     *
     * MINIMAL shares NARROW's values -- it draws almost nothing, and giving
     * a third breakpoint its own set would be three numbers nobody tunes.
     */
    fun tunedFor(mode: Mode, name: String, wide: Float, narrow: Float): Float =
        if (mode == Mode.WIDE) tuned(name, wide) else tuned(name + "_N", narrow)

    /** Stats column (GEAR/RPM/TWR/...) size multiplier. */
    const val STATS_SCALE = 1.1334f
    const val STATS_DX = -0.0138f
    const val STATS_DY = -0.0234f
    /** STATS in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val STATS_SCALE_N = 1f
    const val STATS_DX_N = 0f
    const val STATS_DY_N = 0f
    /** Delta-V headline block (segments + label + units line). */
    const val DELTAV_SCALE = 1f
    const val DELTAV_DX = -0.1363f
    const val DELTAV_DY = -0.0443f
    /** DELTAV in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val DELTAV_SCALE_N = 1f
    const val DELTAV_DX_N = 0f
    const val DELTAV_DY_N = 0f
    /** Isp block above the headline. */
    const val ISP_SCALE = 1f
    const val ISP_DX = -0.0724f
    const val ISP_DY = -0.0261f
    /** ISP in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val ISP_SCALE_N = 1f
    const val ISP_DX_N = 0f
    const val ISP_DY_N = 0f
    /** Speed block below the headline. */
    const val SPEED_SCALE = 1f
    const val SPEED_DX = -0.08f
    const val SPEED_DY = 0.0131f
    /** SPEED in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val SPEED_SCALE_N = 1f
    const val SPEED_DX_N = 0f
    const val SPEED_DY_N = 0f
    /** RADAR mode: the demoted readout row under the scope. */
    const val READOUTS_SCALE = 1f
    const val READOUTS_DX = 0.0162f
    const val READOUTS_DY = 0f
    /** READOUTS in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val READOUTS_SCALE_N = 1f
    const val READOUTS_DX_N = 0f
    const val READOUTS_DY_N = 0f
    /** Navball radius multiplier. 1.0 = the paired radius. */
    const val NAVBALL_SCALE = 1.2076f
    /** Navball horizontal nudge, as a fraction of panel WIDTH. */
    const val NAVBALL_DX = 0.0001f
    /** Navball vertical nudge, as a fraction of panel height. */
    const val NAVBALL_DY = 0.0677f
    /** NAVBALL in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val NAVBALL_SCALE_N = 1f
    const val NAVBALL_DX_N = 0f
    const val NAVBALL_DY_N = 0f
    /** Radar scope radius multiplier. 1.0 = the paired radius. */
    const val SCOPE_SCALE = 1.5871f

    /** Scope horizontal nudge, as a fraction of panel WIDTH. */
    const val SCOPE_DX = 0.0175f
    /** Scope vertical nudge, as a fraction of panel height. */
    const val SCOPE_DY = 0.0443f
    /** SCOPE in the COLLAPSED (NARROW) layout. Separate on purpose. */
    const val SCOPE_SCALE_N = 1f
    const val SCOPE_DX_N = 0f
    const val SCOPE_DY_N = 0f
    // --- The WIDE centre column's vertical stack ---
    //
    // These were inline literals in GaugeRenderer until the desktop layout
    // harness rendered the ND2 geometry with a realistic state and showed
    // "START 8412" and "SPEED" drawn 1.2px apart -- i.e. on top of each
    // other. Named here so the collision is arithmetic that a test can
    // check, rather than something only a screenshot can reveal.
    //
    // The bug hid for so long because `tripStartDeltaV` is null until the
    // fuel tracker seeds, so the START line only appears mid-drive.

    /** Top of the delta-V segments, as a fraction of panel height. */
    const val DV_TOP_WIDE = 0.3984f
    const val DV_TOP_NARROW = 0.42f

    /**
     * Baseline of the units line below the delta-V segments.
     *
     * This line carries the units AND the trip-start reference, merged.
     * They were briefly separate lines; the harness showed the column has
     * only ~18px between this line and the SPEED block while a second line
     * needs ~34px, so they cannot both fit. Pinned by
     * `CentreColumnSpacingTest`.
     */
    const val UNITS_LINE_OFFSET = 0.0838f
    /** Text size of the units + START line. */
    const val UNITS_LINE_TEXT_FRACTION = 0.050f


    /** Top of the speed block. */
    const val SPEED_TOP = 0.8385f
    /** How far a section label sits above the block it names. */
    const val SECTION_LABEL_LIFT = 0.0196f
    /** Text size of a section label ("SPEED", "Isp"). */
    const val SECTION_LABEL_FRACTION = 0.045f

    /**
     * Baseline of the units + START line, in pixels from the panel top.
     *
     * Split out so the spacing can be checked without a Canvas.
     */
    fun unitsLineBaselinePx(top: Float, heightPx: Float, mode: Mode): Float {
        val dvTop = top + heightPx * (
            if (mode == Mode.NARROW) DV_TOP_NARROW else DV_TOP_WIDE
        )
        val segH = heightPx * deltaVTextFraction(mode)
        return dvTop + segH + heightPx * UNITS_LINE_OFFSET
    }

    /** Baseline of the SPEED section label, in pixels from the panel top. */
    fun speedLabelBaselinePx(top: Float, heightPx: Float): Float =
        top + heightPx * SPEED_TOP - heightPx * SECTION_LABEL_LIFT

    /**
     * Clear vertical space between the units line and the SPEED label.
     *
     * Negative or near-zero means they overlap. Must stay at least the
     * units line's own text height, or the two read as one smear -- which
     * is exactly what the ND2 was doing before this was measured.
     */
    fun unitsToSpeedGapPx(top: Float, heightPx: Float, mode: Mode): Float =
        speedLabelBaselinePx(top, heightPx) - unitsLineBaselinePx(top, heightPx, mode)

    fun deltaVTextFraction(mode: Mode): Float = when (mode) {
        Mode.WIDE -> 0.26f
        Mode.NARROW -> 0.20f
        Mode.MINIMAL -> 0.34f
    }

    fun labelTextFraction(mode: Mode): Float = when (mode) {
        Mode.WIDE -> 0.055f
        Mode.NARROW -> 0.050f
        Mode.MINIMAL -> 0.070f
    }

    // --- Status strip ---
    //
    // Transient plain-text status -- HANDSHAKE, NO ADAPTER, DRIVE TO ORIENT
    // -- used to be positioned by ad-hoc multipliers off whatever it sat
    // near. Two of them collided on the real head unit: the link banner
    // landed on the Isp label, and DRIVE TO ORIENT was drawn at a baseline
    // of 1.2px, clipped by the top edge and overlapping the attitude
    // readout beneath it.
    //
    // The fix is to RESERVE a strip rather than to nudge offsets. Status
    // text owns the top band; instrument content starts below it. Neither
    // needs to know where the other is.

    /**
     * Height of the reserved status strip, as a fraction of panel height.
     *
     * Sized to hold one line of [statusTextFraction] text plus its
     * descenders and a margin above and below.
     */
    fun statusStripFraction(mode: Mode): Float = when (mode) {
        // MINIMAL is too small to spend height on chrome; status is
        // suppressed there entirely and the strip collapses to nothing.
        Mode.MINIMAL -> 0.0f
        // 10%, not 9%: a fault shows TWO lines (label plus remedy hint), and
        // at 9% the hint's descenders overflowed the strip by ~2px -- which
        // is exactly the kind of overlap the strip exists to prevent. Found
        // by StatusStripTest, not by looking at a screen.
        else -> 0.10f
    }

    /** Text size for status text, as a fraction of panel height. */
    fun statusTextFraction(mode: Mode): Float = when (mode) {
        Mode.MINIMAL -> 0.0f
        Mode.NARROW -> 0.042f
        Mode.WIDE -> 0.045f
    }

    /**
     * Baseline for the status line, as a fraction of panel height.
     *
     * Far enough down that ascenders clear the top edge -- the previous
     * DRIVE TO ORIENT baseline of 1.2px was clipped by it.
     */
    fun statusBaselineFraction(mode: Mode): Float =
        if (mode == Mode.MINIMAL) 0.0f else statusTextFraction(mode) * 1.35f

    /**
     * Baseline for a secondary hint line beneath the status line.
     *
     * Only drawn for faults, where the label alone cannot convey the
     * remedy. Sits inside the same reserved strip.
     */
    fun statusHintBaselineFraction(mode: Mode): Float =
        if (mode == Mode.MINIMAL) 0.0f else statusStripFraction(mode) * 0.90f

    /**
     * True when a status line fits without eating the instrument.
     *
     * Below this height the strip would take a punishing share of the
     * surface, and the panel drops status text rather than shrink the
     * numbers that matter.
     */
    fun showsStatusStrip(mode: Mode, heightPx: Int): Boolean =
        mode != Mode.MINIMAL && heightPx >= MIN_STATUS_STRIP_HEIGHT_PX

    /** Below this panel height there is no room for a status strip. */
    const val MIN_STATUS_STRIP_HEIGHT_PX = 200

    // --- Navball sizing ---
    //
    // The navball is not just the circle. It carries an attitude readout and
    // a DRIVE TO ORIENT prompt ABOVE it, and a three-row compass strip
    // BELOW. Sizing the radius against the raw column, then positioning the
    // circle's centre by eye, is what left the ball small with dead space
    // under it -- the reservation for the compass overshot by more than the
    // strip actually needs.
    //
    // These constants describe the cluster's true extent in units of the
    // radius, mirroring what GaugeRenderer.drawNavball actually draws. They
    // live here so the fit can be checked numerically instead of by looking
    // at a screenshot, which is the rule the status strip established.

    /**
     * How far the cluster extends ABOVE the ball centre, in radii.
     *
     * Ball edge (1.0) + attitude readout + the prompt above it + ascenders.
     */
    const val NAVBALL_EXTENT_ABOVE = 1.628

    /**
     * How far the cluster extends BELOW the ball centre, in radii.
     *
     * Ball edge (1.0) + the compass strip's three rows + descenders.
     */
    const val NAVBALL_EXTENT_BELOW = 1.756

    /** Total vertical extent of the navball cluster, in radii. */
    const val NAVBALL_EXTENT_TOTAL = NAVBALL_EXTENT_ABOVE + NAVBALL_EXTENT_BELOW

    /**
     * Side margin inside the navball column, as a fraction of the half-column.
     *
     * The rim stroke and roll ticks sit just inside the radius, so a little
     * clearance stops them touching the centre column. 6% is enough; the old
     * code reserved 20% and paid for it in legibility.
     */
    const val NAVBALL_SIDE_MARGIN = 0.94

    /**
     * Share of the available height the cluster may occupy.
     *
     * Filling the height exactly would leave the topmost prompt and the
     * bottom row of compass letters touching the edges, and any inset the
     * renderer applies later -- the status strip is the one that exists
     * today -- would clip them. 4% of breathing room costs almost nothing
     * at these radii and keeps the fit robust.
     */
    const val NAVBALL_VERTICAL_MARGIN = 0.96

    /**
     * Navball radius in pixels, given the column and the height available.
     *
     * Bound by whichever of width and height runs out first, using the REAL
     * vertical extent rather than a guessed fraction. Previously this was
     * `min(navW * 0.40, h * 0.28)`, where the 0.40 wasted a fifth of the
     * column and the 0.28 was a height cap that never actually engaged.
     */
    fun navballRadiusPx(columnWidthPx: Float, heightPx: Float): Float {
        if (columnWidthPx <= 0f || heightPx <= 0f) return 0f
        val widthCap = (columnWidthPx / 2f) * NAVBALL_SIDE_MARGIN.toFloat()
        val heightCap =
            (heightPx * NAVBALL_VERTICAL_MARGIN.toFloat()) / NAVBALL_EXTENT_TOTAL.toFloat()
        return minOf(widthCap, heightCap)
    }

    /**
     * Where the ball's CENTRE sits, as a fraction of the available height.
     *
     * The cluster is taller below the centre than above it, so centring the
     * circle would push the compass strip off the bottom while leaving a gap
     * on top. This places the centre so the whole cluster is centred, which
     * is what removes the dead band the panel used to show underneath.
     */
    fun navballCentreFraction(): Float =
        (NAVBALL_EXTENT_ABOVE / NAVBALL_EXTENT_TOTAL).toFloat()

    // --- WIDE gets the same status-strip correction NARROW already had ---
    //
    // `draw` insets the whole content rect by the status strip whenever the
    // link is not LIVE, then hands that inset rect to drawWide. But the
    // banner is drawn top-CENTRE, over the centre column only -- it renders
    // no pixels in the navball column or the scope column.
    //
    // So both circular instruments were paying for chrome that appears
    // somewhere else. Measured on the DHU at 778x404 with NO ADAPTER shown:
    // 47px of dead space ABOVE the navball cluster and 8px below it, with
    // the whole cluster pushed down and the scope pushed with it.
    //
    // This is the identical defect NAVBALL_NARROW_HEIGHT_SHARE describes for
    // split view ("the column was paying for chrome that renders somewhere
    // else"), and it gets the identical fix: the columns take back the strip
    // and centre their clusters against the height they actually own.

    // --- height-bound instruments must be PACKED, not centred in columns ---
    //
    // A head unit is short and wide: 778x406 on the DHU. Both circular
    // instruments are HEIGHT-bound there -- measured, navball widthCap 124
    // against heightCap 115, scope 161 against 119 -- so the column
    // fractions do not change their size at all. Widening a column buys
    // nothing; it only adds slack around a circle that cannot grow.
    //
    // Centring each circle in its own column then puts ALL of that slack
    // BETWEEN them: 118px of dead space between the navball's right edge and
    // the scope's left edge, photographed on the DHU, with the pair reading
    // as two instruments drifting apart rather than one panel.
    //
    // The fix is to place the circles by their own diameters rather than by
    // column centres, and let the leftover width fall outside the pair. The
    // stats column keeps its own edge -- it is TEXT and genuinely width-
    // bound, so it is the one element that wants its column.

    /**
     * Gap between the two circular instruments, as a fraction of the smaller
     * instrument's diameter.
     *
     * Enough that they read as two instruments rather than one wide blob,
     * small enough that they read as a pair.
     *
     * **0.70, after 0.16 and 0.32 were both reported as too close.**
     *
     * Worth recording why this took three attempts: at 0.32 the geometry was
     * measurably correct -- 59px of clear space between the navball's compass
     * strip and the scope's range label, verified against the render log --
     * and it still read as crowded. Two circles of the same size sitting
     * side by side need much more air between them than a numeric margin
     * suggests, because the eye compares the gap to the DIAMETER, not to the
     * ink. 61px beside a 178px circle is a third of a diameter and reads as
     * a seam; 133px reads as two instruments.
     *
     * Widening this separates BOTH ways -- the navball moves left and the
     * scope moves right -- which is why it is the right lever rather than
     * biasing the pair's position.
     */
    const val INSTRUMENT_GAP = 0.70

    /**
     * Centre X for a pair of height-bound circular instruments, as offsets
     * from the left edge of the space they share.
     *
     * Packs them side by side with [INSTRUMENT_GAP] between, then centres
     * the resulting group in the available width. When the two cannot fit
     * side by side the group simply overflows symmetrically, which is the
     * least-bad failure and cannot happen on any real head-unit geometry.
     *
     * @return left instrument's centre X, then the right instrument's.
     */
    /**
     * Half-width of the NAVBALL cluster, in radii.
     *
     * **The circle is not the instrument.** `drawNavball` draws its compass
     * strip at `radius * 2.3` wide -- 220px against a 192px ball -- so the
     * cluster reaches 14px past each rim at r=96.
     *
     * Packing by radius therefore silently overstated the gap: a computed
     * 61px rim separation rendered as 47px of visible space, and widening
     * [INSTRUMENT_GAP] could never fix it because the compass reclaimed a
     * fixed slice of whatever was granted. Photographed on the DHU as the
     * navball's compass touching the scope.
     */
    const val NAVBALL_HALF_EXTENT = 1.15

    /**
     * Half-width of the SCOPE cluster, in radii.
     *
     * The range label ("80 MI") is drawn flush with the rim at `cx - radius`
     * and extends further left, so the scope also reaches past its circle --
     * less than the navball does, but not by nothing.
     */
    const val SCOPE_HALF_EXTENT = 1.06

    fun packInstrumentPair(
        availableWidthPx: Float,
        leftRadiusPx: Float,
        rightRadiusPx: Float
    ): Pair<Float, Float> {
        // Packed by what the instruments actually OCCUPY, not by their radii.
        val leftHalf = leftRadiusPx * NAVBALL_HALF_EXTENT.toFloat()
        val rightHalf = rightRadiusPx * SCOPE_HALF_EXTENT.toFloat()
        val diameters = leftHalf * 2f + rightHalf * 2f

        // The gap YIELDS when the pair is close to filling the width.
        //
        // Two circles that together need more than the space available
        // would otherwise be pushed off both edges symmetrically -- the
        // group is centred, so an overflow of N costs N/2 at each end and
        // clips both instruments rather than neither.
        //
        // Squeezing the gap first is the right trade: the separation exists
        // to stop the pair reading as one blob, and a narrow gap does that
        // job while a clipped instrument does not. Found by a sweep, not on
        // a screen -- no real head-unit geometry reaches this, but a
        // future one with a taller panel could.
        val wanted = minOf(leftHalf, rightHalf) * 2f * INSTRUMENT_GAP.toFloat()
        val gap = minOf(wanted, (availableWidthPx - diameters).coerceAtLeast(0f))

        val groupWidth = diameters + gap

        // BIASED LEFT, not centred.
        //
        // Centring splits the slack evenly, which sounds right and is not:
        // the pair's right neighbour is the stats column (text, hard edge,
        // must stay legible) while its left neighbour is the panel edge
        // (nothing). Equal margins therefore crowd the stats column and
        // waste the same space on the outside -- photographed on the DHU as
        // the scope brushing GEAR/RPM with a matching void to the left of
        // the navball.
        //
        // Biasing left moves BOTH instruments away from the text while
        // preserving the gap between them, which is the one thing that was
        // finally right.
        val slack = (availableWidthPx - groupWidth).coerceAtLeast(0f)
        val groupLeft = slack * PAIR_LEFT_BIAS.toFloat()

        val leftCx = groupLeft + leftHalf
        val rightCx = groupLeft + leftHalf * 2f + gap + rightHalf
        return leftCx to rightCx
    }

    /**
     * Share of the pair's leftover width placed to its LEFT.
     *
     * 0.5 centres the pair; 0.0 pins it to the left edge. 0.12 keeps a
     * small visible outer margin while giving the stats column the large
     * majority of the slack, because text needs the breathing room and the
     * panel edge does not.
     *
     * Tightened from 0.30 after the scope still read as crowding the stat
     * rows: 10px outside the navball against 74px before the stats column.
     * The asymmetry is the point, not a defect.
     */
    const val PAIR_LEFT_BIAS = 0.12

    /**
     * Share of the FULL SURFACE height the WIDE instrument cluster may use.
     *
     * The surface is the right rect to lay out against -- the stable area
     * excludes an 88px band of host chrome that is empty on the left -- but
     * using all of it makes the cluster 92% of the panel height, which
     * renders as two circles crowding every edge. Measured on the DHU:
     * r=85 against the stable area was too small, r=109 against the whole
     * surface was too big.
     *
     * 0.96 with a DERIVED centre lands at r=96. An earlier 0.84 with a
     * hand-picked 0.48 centre gave r=70 -- the budget was doing the work the
     * centre should have done. Balance the centre first, then the budget only
     * has to buy edge margin.
     */
    const val WIDE_INSTRUMENT_HEIGHT_BUDGET = 0.96

    /**
     * Height below the pair reserved for the demoted readout row, as a
     * fraction of the surface height.
     *
     * Both the centre derivation and the radius use this, so the row and the
     * circles cannot disagree about who owns the bottom band.
     *
     * Raised from 0.12 to 0.17 on 2026-08-24. The band has to hold the
     * readout row AND the START reference line beneath it; at 0.12 it only
     * ever accounted for the row, and the desktop harness showed START
     * overflowing the ND2's panel bottom by 6.1px. The scope loses a little
     * radius, which is the correct trade: a clipped readout is a bug, a
     * marginally smaller scope is not. Pinned by `RadarReadoutBandTest`.
     */
    const val WIDE_READOUT_BAND = 0.25
    /**
     * ONE radius for BOTH circular instruments at full width.
     *
     * ## Why this exists
     *
     * The navball and the scope were each sizing themselves with their own
     * formula: the navball divided its height budget by its FULL extent
     * (3.384 radii), the scope took the min of two HALF extents (1.20 and
     * 1.34). Same available space, different divisors, so they came out at
     * different sizes -- measured on the DHU as navR=91.5 against
     * scopeR=103.7, which is the "one is bigger than the other" and the
     * overlapping readouts in one bug.
     *
     * Worse, the scope was SIZED against a strip-reclaimed centre and DRAWN
     * at the un-reclaimed one -- 165 versus 185 -- so its lower rim ran 20px
     * further down than anything had accounted for, straight into the
     * readout row.
     *
     * A pair of instruments that must look like a pair cannot have two
     * sizing rules. This is the single rule: the tightest constraint either
     * circle faces, applied to both.
     *
     * @param navColumnWidthPx width available to the navball
     * @param scopeColumnWidthPx width available to the scope
     * @param heightPx the surface height both are laid out against
     * @param centreFraction where both centres sit, as a fraction of height
     * @param bottomReservedFraction height below the centre that belongs to
     *   the readout row, so neither circle grows into it
     */
    fun pairRadiusPx(
        navColumnWidthPx: Float,
        scopeColumnWidthPx: Float,
        heightPx: Float,
        centreFraction: Float,
        bottomReservedFraction: Float,
        topReservedFraction: Float = 0f
    ): Float {
        if (heightPx <= 0f) return 0f

        val centre = heightPx * centreFraction
        val budget = WIDE_INSTRUMENT_HEIGHT_BUDGET.toFloat()

        // Vertical room above and below the SHARED centre, scaled by the
        // budget so the pair does not crowd the panel edges. The top band
        // belongs to the banner, so it is not room the circles may use.
        val above = (centre - heightPx * topReservedFraction) * budget
        val below =
            (heightPx - centre - heightPx * bottomReservedFraction).coerceAtLeast(0f) *
                budget

        // Each instrument's own extents applied to the same space. The
        // navball is the taller cluster (attitude readout above, compass
        // below), so it is usually the binding one -- but taking the min of
        // all four keeps that an observation rather than an assumption.
        val navCap = minOf(
            above / NAVBALL_EXTENT_ABOVE.toFloat(),
            below / NAVBALL_EXTENT_BELOW.toFloat()
        )
        val scopeCap = minOf(
            above / RadarLayout.SCOPE_EXTENT_ABOVE.toFloat(),
            below / RadarLayout.SCOPE_EXTENT_BELOW.toFloat()
        )

        val widthCap = minOf(
            (navColumnWidthPx / 2f) * NAVBALL_SIDE_MARGIN.toFloat(),
            (scopeColumnWidthPx / 2f) * RadarLayout.SCOPE_MARGIN.toFloat()
        )

        return minOf(widthCap, navCap, scopeCap).coerceAtLeast(0f)
    }

    /**
     * Where both circles' centres sit at full width, as a fraction of the
     * surface height.
     *
     * Deliberately a plain constant-derived value with NO strip term: the
     * status banner is drawn top-centre over the scope only, and letting it
     * move the shared centre is what put the two circles 20px apart.
     * The banner is cleared by shrinking the pair, never by moving it.
     */
    /**
     * Height at the TOP of the surface reserved for the link banner, as a
     * fraction of surface height.
     *
     * The banner and the instruments were laid out against different rects
     * -- the banner against the stable area, the pair against the full
     * surface -- so "top of the panel" meant two different y values 80px
     * apart and the banner kept landing on the ball. With both on the
     * surface, the pair simply yields this band.
     *
     * 0.06 is one line of status text plus its descenders. It costs the
     * radius about 6px, which is the right trade against a label printed
     * through the navball.
     */
    const val WIDE_BANNER_BAND = 0.06

    /**
     * @param topReservedFraction band at the top the pair must stay below,
     *   normally [WIDE_BANNER_BAND] when a banner is showing and 0 when not.
     */
    fun pairCentreFraction(
        bottomReservedFraction: Float,
        topReservedFraction: Float = 0f
    ): Float {
        // DERIVED, not chosen. The centre that balances the navball's own
        // extents is the one that lets the pair be largest: put it too high
        // and the compass strip runs out of room below, too low and the
        // attitude readout runs out above. Either way the radius collapses
        // -- picking 0.48 by eye gave r=70 where the balance point gives 96.
        //
        // Solving above/EXTENT_ABOVE == below/EXTENT_BELOW for the centre:
        //   c / A == (usable - c) / B   =>   c == usable * A / (A + B)
        val usable = 1f - bottomReservedFraction - topReservedFraction
        return usable * (NAVBALL_EXTENT_ABOVE /
            (NAVBALL_EXTENT_ABOVE + NAVBALL_EXTENT_BELOW)).toFloat() +
            topReservedFraction
    }

    /**
     * Fraction of the status strip the flanking columns take back at full
     * width.
     *
     * 1.0 -- the whole strip. The banner is top-centre and overlaps neither
     * flanking column, so neither should reserve any of it. Named rather
     * than inlined so the reasoning has somewhere to live.
     */
    const val WIDE_STRIP_RECLAIM = 1.0

    /**
     * Where the navball centre sits at full width, given how much status
     * strip was deducted from the content rect above it.
     *
     * @param stripPx Height the status strip took, or 0 when it is hidden.
     * @param contentHeightPx Height of the rect [drawWide] was handed, i.e.
     *   already reduced by [stripPx].
     * @return Fraction of the CONTENT height at which to place the centre.
     *   May be negative-adjusted upward into the strip, which is correct:
     *   the strip is empty in this column.
     */
    fun navballCentreWideFraction(stripPx: Float, contentHeightPx: Float): Float {
        if (contentHeightPx <= 0f) return navballCentreFraction()
        val reclaimed = stripPx * WIDE_STRIP_RECLAIM.toFloat()
        val owned = contentHeightPx + reclaimed
        // Centre the cluster in the height the column really owns, then
        // express that back as a fraction of the content rect the renderer
        // is drawing into.
        val centreInOwned = owned * (NAVBALL_EXTENT_ABOVE / NAVBALL_EXTENT_TOTAL).toFloat()
        return (centreInOwned - reclaimed) / contentHeightPx
    }

    /**
     * Navball radius at full width, measured against the height the column
     * actually owns rather than the strip-reduced content rect.
     */
    fun navballRadiusWidePx(
        columnWidthPx: Float,
        contentHeightPx: Float,
        stripPx: Float
    ): Float {
        val owned = (contentHeightPx + stripPx * WIDE_STRIP_RECLAIM.toFloat()) *
            WIDE_INSTRUMENT_HEIGHT_BUDGET.toFloat()
        return navballRadiusPx(columnWidthPx, owned)
    }

    // --- Split view gets its own navball budget ---
    //
    // Split view is the layout that actually gets used -- as of 2026-08-22
    // every live run has been split, and full width has never rendered for a
    // whole session. So NARROW is the case to optimise, not WIDE.
    //
    // Measured on the DHU: the ball came out ~176px across on a ~800x420
    // surface, and it is HEIGHT-bound, not width-bound. That is the opposite
    // of the WIDE defect and it means widening the column buys NOTHING --
    // proven numerically: at column fractions from 0.38 to 0.62 the radius
    // does not move by a single pixel.
    //
    // The height was being spent before the ball ever saw it. NARROW deducted
    // a 10% status strip and an 18% stats strip, then divided what was left
    // by the cluster's 3.384 extent -- so the ball got 42% of the panel
    // height. But NEITHER deduction draws pixels in the nav column: the
    // status banner is top-CENTRE and the stats row is centred text whose
    // first cell sits well right of the ball. The column was paying for
    // chrome that renders somewhere else.
    //
    // The compass is NOT trimmed to make room. It is part of the instrument
    // and scales with the ball, keeping its position relative to it -- the
    // extents above are what guarantee that, since everything in drawNavball
    // is expressed in radii.

    /**
     * Share of panel height the navball column may use in split view.
     *
     * The cluster owns its column rather than inheriting the status and stats
     * deductions that are drawn elsewhere.
     *
     * **0.86, not 0.98.** The first attempt at 0.98 took the full column
     * height and grew the ball until its compass row sat level with the stats
     * row -- which, combined with the stats row yielding the column, crushed
     * the remaining cells into each other. Photographed on the DHU as
     * `GEAR 6RPM 2669TWR 85FUEL 37L`. The ball is still well up on where it
     * started; it just no longer reaches the bottom strip.
     */
    const val NAVBALL_NARROW_HEIGHT_SHARE = 0.86

    /**
     * Navball radius for the split-view layout.
     *
     * Separate from [navballRadiusPx] because NARROW measures against the
     * FULL panel height, not the body height left over after the stats
     * strip. Takes the raw panel height and applies its own share.
     */
    fun navballRadiusNarrowPx(columnWidthPx: Float, panelHeightPx: Float): Float =
        navballRadiusPx(
            columnWidthPx,
            panelHeightPx * NAVBALL_NARROW_HEIGHT_SHARE.toFloat() /
                NAVBALL_VERTICAL_MARGIN.toFloat()
        )

    /**
     * Where the ball centre sits in split view, as a fraction of PANEL height.
     *
     * Same cluster-centring rule as [navballCentreFraction], but measured
     * against the full height the column now owns.
     */
    fun navballCentreNarrowFraction(): Float =
        (NAVBALL_NARROW_HEIGHT_SHARE *
            (NAVBALL_EXTENT_ABOVE / NAVBALL_EXTENT_TOTAL)).toFloat() +
            ((1.0 - NAVBALL_NARROW_HEIGHT_SHARE) / 2.0).toFloat()

    /**
     * Left edge of the split-view stats row, as a fraction of panel width.
     *
     * The stats row spans the full width in four centred cells, so its first
     * cell would sit under the enlarged ball. Starting it after the navball
     * column keeps the two apart without shrinking either. The row simply
     * distributes its cells across the narrower span.
     */
    fun statsRowLeftFraction(navballColumnFraction: Double): Float =
        navballColumnFraction.toFloat()

    /**
     * Cells shown in the split-view stats row.
     *
     * Three, not four. TWR was dropped: it is a ratio that only means
     * something read next to Isp, which has its own readout in the centre
     * column, and at half width a fourth cell could not fit without the
     * cells running into each other -- photographed on the DHU as
     * `GEAR 6RPM 2669TWR 85FUEL 37L`. TWR stays in the WIDE stats column.
     */
    const val NARROW_STAT_CELLS = 3

    /**
     * Share of a cell its content may occupy before the row shrinks to fit.
     *
     * Each cell centres its own content and nothing enforced a gutter, so
     * when the content outgrew the cell the cells simply overlapped. Below
     * 1.0 this leaves visible space between neighbours.
     */
    const val STAT_CELL_FILL = 0.88

    /**
     * Scale factor to apply to stat-row text so the widest cell fits.
     *
     * Returns 1.0 when it already fits -- the row never grows, only shrinks.
     * The renderer measures real glyph widths and passes them in; the rule
     * itself is pure so it can be tested without a Canvas.
     *
     * @param widestCellPx Width of the widest cell's content, as drawn.
     * @param cellWidthPx Width available to one cell.
     */
    fun statRowTextScale(widestCellPx: Float, cellWidthPx: Float): Float {
        if (widestCellPx <= 0f || cellWidthPx <= 0f) return 1f
        val budget = cellWidthPx * STAT_CELL_FILL.toFloat()
        return if (widestCellPx > budget) budget / widestCellPx else 1f
    }
}
