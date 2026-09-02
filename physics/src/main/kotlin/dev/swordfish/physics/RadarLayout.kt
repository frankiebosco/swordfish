package dev.swordfish.physics

/**
 * Geometry for the radar display.
 *
 * ## Why this is in `:physics`
 *
 * Same rule as [PanelLayout]: given a rectangle, decide where the scope sits
 * and how big it is. That arithmetic is pure, so every sizing rule is
 * unit-testable on the JVM with no head unit and no emulator. The Android
 * side only turns the resulting geometry into Canvas calls.
 *
 * ## What the radar IS
 *
 * A **heading-up threat display**, not a map. The car is fixed at the centre
 * of the scope pointing up the screen, and the world rotates around it. There
 * is no basemap, no roads and no route: those would need a tile source, an
 * API key and a quota, and none of them answer the question the display
 * exists for — *is there weather between me and where I am going.*
 *
 * Range rings give distance. The compass rose gives orientation. Everything
 * else on the scope is precipitation.
 *
 * ## Heading-up, and why it is not north-up
 *
 * The panel already has a drift-free course-over-ground bearing from GPS
 * doppler (see `ImuSource`), and the navball beside it is heading-up too.
 * A north-up scope next to a heading-up navball would make the driver do the
 * rotation in their head at exactly the moment they should not be.
 */
object RadarLayout {

    /**
     * What the centre column is showing.
     *
     * This is a **second axis, orthogonal to [PanelLayout.Mode]**. Mode says
     * what geometry the surface has — a fact about the head unit, decided by
     * the host. Content says what the driver asked to see — a fact about the
     * driver, decided by the MODE button.
     *
     * Keeping them separate is what stops radar mode from becoming a third
     * copy of every breakpoint rule. `choose` still decides WIDE/NARROW/
     * MINIMAL exactly as before; content only changes which elements that
     * decision applies to.
     */
    enum class CentreContent {
        /** Delta-V headline, Isp and speed. The original panel. */
        INSTRUMENTS,

        /** Radar scope large, with delta-V and Isp demoted to small lines. */
        RADAR;

        fun next(): CentreContent = if (this == INSTRUMENTS) RADAR else INSTRUMENTS

        companion object {
            /**
             * Parse a persisted name, falling back to the instrument panel.
             *
             * Unknown values resolve to [INSTRUMENTS] rather than throwing:
             * a preference written by a future build must never stop the
             * panel from rendering. Same rule as `DisplayTheme.fromName`.
             */
            fun fromName(name: String?): CentreContent =
                entries.firstOrNull { it.name == name } ?: INSTRUMENTS
        }
    }

    /**
     * Share of the centre column's height given to the scope itself.
     *
     * The rest carries the demoted delta-V and Isp lines beneath it. 0.72
     * leaves room for two text rows at a readable size without the scope
     * shrinking to a token.
     */
    const val SCOPE_HEIGHT_SHARE = 0.72

    /**
     * Share of the scope's band the cluster may occupy vertically.
     *
     * At full width the scope is WIDTH-bound -- the centre column is wide
     * enough that the height cap never engages -- so without this the
     * cluster sits flush against the top of its band with the range label
     * touching the panel edge. Photographed on the DHU at 800x420: the rim
     * at y=0.
     *
     * The navball has the same guard for the same reason
     * ([PanelLayout.NAVBALL_VERTICAL_MARGIN]); 6% costs a couple of pixels
     * of radius and buys back the breathing room the panel is drawn with
     * everywhere else.
     */
    const val SCOPE_VERTICAL_MARGIN = 0.94

    /**
     * Share of the scope's half-extent the outer ring may occupy.
     *
     * A little clearance stops the outer ring and its range label touching
     * the column edge, the same reason [PanelLayout.NAVBALL_SIDE_MARGIN]
     * exists.
     */
    const val SCOPE_MARGIN = 0.94

    /**
     * How far the scope cluster extends ABOVE the ring centre, in radii.
     *
     * The rim (1.0) plus the range label, which is drawn at the top-left
     * just inside the rim and whose ascenders sit above it.
     *
     * Expressed in radii for the same reason the navball's extents are
     * ([PanelLayout.NAVBALL_EXTENT_ABOVE]): it lets the fit be checked
     * numerically instead of by looking at a screenshot.
     */
    const val SCOPE_EXTENT_ABOVE = 1.20

    /**
     * How far the scope cluster extends BELOW the ring centre, in radii.
     *
     * The rim (1.0) plus the NO RADAR DATA line beneath it and its
     * descenders. Larger than the extent above, so centring the CIRCLE in
     * the band would push that line into the readouts below.
     */
    const val SCOPE_EXTENT_BELOW = 1.34

    /** Total vertical extent of the scope cluster, in radii. */
    const val SCOPE_EXTENT_TOTAL = SCOPE_EXTENT_ABOVE + SCOPE_EXTENT_BELOW

    // --- where the demoted readouts sit ---
    //
    // Delta-V and Isp move below the scope in radar mode. Their row is
    // positioned as a fraction of the column height rather than measured off
    // the scope, so the two never fight: the scope is capped by
    // SCOPE_HEIGHT_SHARE and the row starts after it.

    /**
     * Top of the readout row at full width, as a fraction of column height.
     *
     * Sits just below the scope's band, which ends at [SCOPE_HEIGHT_SHARE].
     * The gap is deliberate breathing room, not a gap to be closed -- the
     * NO RADAR DATA line lives inside the scope's own extent above this.
     */
    const val READOUT_TOP_FRACTION_WIDE = 0.765

    /**
     * Top of the readout row in split view, in pixels from the column top.
     *
     * **Derived from the scope's real extent, not a fraction.** In split
     * view the scope inherits the navball's centre and is sized to fit
     * around it, so how far down the panel it actually reaches depends on
     * the geometry — a fixed fraction that clears it at 468 px wide buries
     * itself in the rim at 390 px. Computed at both widths, a constant
     * fraction overlapped the scope by 45–71 px.
     *
     * So the row is placed *below where the scope ends*, with a gap
     * proportional to the panel. The caller passes the same panel height it
     * used to size the scope, and the two cannot disagree.
     *
     * @param panelHeightPx Full panel height — the same value passed to
     *   [scopeRadiusNarrowPx], not the body height after the stats row.
     * @param scopeRadiusPx The radius that sizing actually produced.
     */
    fun readoutTopNarrowPx(panelHeightPx: Float, scopeRadiusPx: Float): Float {
        if (panelHeightPx <= 0f) return 0f
        val centre = panelHeightPx * scopeCentreNarrowFraction()
        val scopeBottom = centre + scopeRadiusPx * SCOPE_EXTENT_BELOW.toFloat()
        return scopeBottom + panelHeightPx * READOUT_GAP_FRACTION.toFloat()
    }

    /**
     * Gap between the scope's lower extent and the readout row, as a
     * fraction of panel height.
     *
     * Enough to read as separation rather than crowding, small enough that
     * the row still clears the bottom of the panel.
     *
     * **0.070, up from 0.025.** At the smaller value the readouts sat
     * directly beneath the scope's rim with 48px of unused panel below them
     * -- tucked up under the instrument rather than sitting as their own
     * row. The extra drops them 17px and still leaves 31px of clearance.
     */
    /**
     * The START reference line below the demoted readout row.
     *
     * Named here (rather than inline in the renderer) because the bottom
     * band has to be big enough for the row AND this line: the desktop
     * harness showed START overflowing the ND2's panel bottom by 6.1px,
     * because `WIDE_READOUT_BAND` only ever accounted for the row.
     */
    const val RADAR_START_OFFSET_FRACTION = 0.048

    /** Text size of the radar-mode START line. */
    const val RADAR_START_TEXT_FRACTION = 0.036

    /**
     * Baseline of the radar-mode START line, in px from the panel top.
     *
     * @param rowTopPx where the demoted readout row starts
     * @param rowHeightPx the row's segment height
     */
    fun radarStartBaselinePx(
        rowTopPx: Float, rowHeightPx: Float, panelHeightPx: Float
    ): Float = rowTopPx + rowHeightPx +
        panelHeightPx * RADAR_START_OFFSET_FRACTION.toFloat()

    const val READOUT_GAP_FRACTION = 0.070

    /**
     * Number of range rings drawn, including the outer edge.
     *
     * Three is the most a glance can count without reading labels. Rings at
     * 1/3, 2/3 and the full range give round divisions for every range in
     * [RANGES_MILES].
     */
    const val RING_COUNT = 3

    /**
     * Selectable scope ranges, in statute miles.
     *
     * Miles because this is a US car reading a US radar product, and because
     * the driver's question is "how far away is that" in the units road
     * signs use. Delta-V stays metric — it is a physics readout and the
     * jet analogy is metric throughout.
     *
     * The spread is deliberate: 10 miles is "it is about to rain on me",
     * 80 miles is "what is the afternoon going to do". Nothing between 10
     * and 80 is worth a separate stop.
     */
    val RANGES_MILES = listOf(10, 20, 40, 80)

    /** Default scope range, in statute miles. */
    const val DEFAULT_RANGE_MILES = 20

    /**
     * Radius of the scope in pixels, given the space available.
     *
     * Bound by whichever of width and height runs out first, on the same
     * pattern as [PanelLayout.navballRadiusPx]. The height term divides by
     * the cluster's REAL extent rather than by 2, because the scope is not
     * just the circle: the range label sits above the rim and the status
     * line below it.
     *
     * **This was `(h * SCOPE_HEIGHT_SHARE) / 2` and that was wrong twice
     * over.** Dividing by 2 sized the circle as though it owned the band
     * alone, so on the DHU at full width the rim touched y=0 with its range
     * label clipped, and in split view the circle overhung the readouts.
     */
    fun scopeRadiusPx(widthPx: Float, heightPx: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f) return 0f
        val widthCap = (widthPx / 2f) * SCOPE_MARGIN.toFloat()
        val heightCap =
            (heightPx * SCOPE_HEIGHT_SHARE.toFloat() * SCOPE_VERTICAL_MARGIN.toFloat()) /
                SCOPE_EXTENT_TOTAL.toFloat()
        return minOf(widthCap, heightCap)
    }

    /**
     * Where the scope's centre sits, as a fraction of the column height.
     *
     * Places the whole CLUSTER centred in the scope's band, not the circle.
     * The cluster is taller below the centre than above it, so centring the
     * circle would push the status line into the readouts while leaving a
     * gap on top — exactly the defect the navball's own extents exist to
     * prevent.
     */
    fun scopeCentreFraction(): Float =
        (SCOPE_HEIGHT_SHARE * SCOPE_VERTICAL_MARGIN *
            (SCOPE_EXTENT_ABOVE / SCOPE_EXTENT_TOTAL)).toFloat() +
            ((SCOPE_HEIGHT_SHARE * (1.0 - SCOPE_VERTICAL_MARGIN)) / 2.0).toFloat()

    /**
     * Where the scope's centre sits at FULL WIDTH, as a fraction of the
     * content height.
     *
     * Delegates to [PanelLayout.navballCentreFraction] for the same reason
     * [scopeCentreNarrowFraction] delegates to the split-view rule: the
     * navball sits beside the scope in both layouts now, and two circles at
     * different heights read as a mistake.
     *
     * The WIDE and NARROW rules differ only because the navball itself is
     * measured differently in each — against the content band at full width,
     * against the whole panel in split.
     */
    fun scopeCentreWideFraction(stripPx: Float, contentHeightPx: Float): Float =
        PanelLayout.navballCentreWideFraction(stripPx, contentHeightPx)

    /**
     * Scope radius at full width, given the column and the content height.
     *
     * Sized to fit around the shared centre, exactly like
     * [scopeRadiusNarrowPx], rather than centred in a band of its own.
     */
    fun scopeRadiusWidePx(widthPx: Float, heightPx: Float, stripPx: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f) return 0f
        // Same height budget as the navball: the two must scale together or
        // one ends up visibly larger than its neighbour.
        val budget = PanelLayout.WIDE_INSTRUMENT_HEIGHT_BUDGET.toFloat()
        // The centre may sit ABOVE the content rect's own top once the strip
        // is reclaimed, so the space above it is measured from the real
        // column top rather than from zero.
        val centre = heightPx * scopeCentreWideFraction(stripPx, heightPx)
        val reserved = heightPx *
            (READOUT_GAP_FRACTION + READOUT_ROW_HEIGHT_FRACTION_WIDE).toFloat()
        val above = (centre + stripPx * PanelLayout.WIDE_STRIP_RECLAIM.toFloat()) *
            budget
        val below = (heightPx - centre - reserved).coerceAtLeast(0f) * budget
        val heightCap = minOf(
            above / SCOPE_EXTENT_ABOVE.toFloat(),
            below / SCOPE_EXTENT_BELOW.toFloat()
        )
        val widthCap = (widthPx / 2f) * SCOPE_MARGIN.toFloat()
        return minOf(widthCap, heightCap)
    }

    /**
     * Top of the readout row at full width, in pixels from the content top.
     *
     * Derived from the scope's real extent for the same reason
     * [readoutTopNarrowPx] is: once the scope is centred on the navball
     * rather than in its own band, how far down it reaches depends on the
     * geometry, and a fixed fraction cannot track that.
     */
    fun readoutTopWidePx(
        heightPx: Float, scopeRadiusPx: Float, stripPx: Float
    ): Float {
        if (heightPx <= 0f) return 0f
        val centre = heightPx * scopeCentreWideFraction(stripPx, heightPx)
        val scopeBottom = centre + scopeRadiusPx * SCOPE_EXTENT_BELOW.toFloat()
        return scopeBottom + heightPx * READOUT_GAP_FRACTION.toFloat()
    }

    /**
     * Where the scope's centre sits in SPLIT view, as a fraction of panel height.
     *
     * ## Why this is not [scopeCentreFraction]
     *
     * In split view the scope sits **beside the navball**, and two circular
     * instruments whose centres do not agree read as a mistake — the eye
     * catches a 79 px step between them long before it reads either one.
     * Photographed on the DHU: the scope rode high enough that its rim
     * nearly touched the panel top while the ball sat comfortably below.
     *
     * So the scope adopts the NAVBALL's centre rather than computing its
     * own. The ball is the instrument with the harder constraint — its
     * cluster carries an attitude readout above and a three-row compass
     * below — so it wins the alignment and the scope follows.
     *
     * This deliberately couples the two. If [PanelLayout.navballCentreNarrowFraction]
     * moves, the scope must move with it, and a test pins exactly that.
     */
    fun scopeCentreNarrowFraction(): Float = PanelLayout.navballCentreNarrowFraction()

    /**
     * Scope radius in split view, given the column and the FULL panel height.
     *
     * Sized so the cluster fits above and below the shared centre without
     * leaving the panel. Because the centre is inherited from the navball
     * rather than centred in the column, the available height is whatever
     * the shorter side of that centre allows — doubling the smaller half is
     * what keeps the whole cluster on screen.
     */
    fun scopeRadiusNarrowPx(widthPx: Float, panelHeightPx: Float): Float {
        if (widthPx <= 0f || panelHeightPx <= 0f) return 0f
        val centre = panelHeightPx * scopeCentreNarrowFraction()

        // The space BELOW the centre is not all the scope's: the readout row
        // and its gap live there too. Reserving them here is what stops the
        // scope from sizing itself to the panel floor and pushing the
        // readouts off the bottom -- which a 500x400 surface did by 3 px,
        // found numerically rather than on a screen.
        val reserved = panelHeightPx *
            (READOUT_GAP_FRACTION + READOUT_ROW_HEIGHT_FRACTION).toFloat()
        val above = centre
        val below = (panelHeightPx - centre - reserved).coerceAtLeast(0f)

        val heightCap = minOf(
            above / SCOPE_EXTENT_ABOVE.toFloat(),
            below / SCOPE_EXTENT_BELOW.toFloat()
        )
        val widthCap = (widthPx / 2f) * SCOPE_MARGIN.toFloat()
        return minOf(widthCap, heightCap)
    }

    /**
     * Height of the demoted readout row, as a fraction of panel height.
     *
     * Reserved by [scopeRadiusNarrowPx] so the scope cannot grow into it.
     * Must stay in step with the segment height the renderer actually uses
     * for that row; a test pins that the row fits beneath the scope on every
     * plausible split geometry.
     *
     * **Carries THREE readouts in radar mode** — delta-V, Isp and speed —
     * because moving the stats row above the scope freed the whole band.
     */
    const val READOUT_ROW_HEIGHT_FRACTION = 0.095

    /**
     * The same, at full width, where the row is drawn taller.
     *
     * **Must cover what the renderer actually draws.** Reserving the split
     * view's 0.095 while full width drew 0.105 pushed the readouts off the
     * bottom on 453 of 506 swept geometries — the scope simply grew into
     * space the row needed. The extra also carries the row's label line.
     */
    const val READOUT_ROW_HEIGHT_FRACTION_WIDE = 0.115

    /**
     * Share of a readout cell its content may occupy before the row shrinks.
     *
     * Below 1.0 so neighbouring cells keep a visible gutter rather than
     * merely not overlapping. Same rule and the same reasoning as
     * [PanelLayout.STAT_CELL_FILL], which exists because the split-view stat
     * cells ran into each other for exactly this reason.
     */
    const val READOUT_CELL_FILL = 0.92

    /**
     * Scale factor for the demoted readout row so its widest cell fits.
     *
     * Returns 1.0 when it already fits — the row never grows, only shrinks.
     * The renderer measures real segment and glyph widths and passes the
     * widest in; the rule itself is pure so it can be tested without a
     * Canvas, exactly like [PanelLayout.statRowTextScale].
     *
     * **Three readouts in a split-view column need this.** Measured on the
     * DHU at 468x402: the column gives each cell 84px, while `31564 s`
     * needs 133px and `7129 m/s` needs 126px — overflows of 49 and 42px,
     * which is what printed the three readouts through each other.
     *
     * @param widestCellPx Width of the widest cell's content, as drawn.
     * @param cellWidthPx Width available to one cell.
     */
    fun readoutTextScale(widestCellPx: Float, cellWidthPx: Float): Float {
        if (widestCellPx <= 0f || cellWidthPx <= 0f) return 1f
        val budget = cellWidthPx * READOUT_CELL_FILL.toFloat()
        return if (widestCellPx > budget) budget / widestCellPx else 1f
    }

    /**
     * Scale for a readout row whose cells are sized to their CONTENT.
     *
     * ## Why not equal thirds
     *
     * Equal thirds makes every cell as wide as the widest needs, so the
     * narrow one wastes what the wide ones are starved of. Measured on the
     * DHU at 468x402: `29 m/s` needs 77px while `31564 s` needs 133, and
     * equal thirds forced the whole row down to **0.58** of its natural
     * size. Packing the three proportionally gets **0.72** — the same row,
     * noticeably more readable, for no extra space.
     *
     * The row still scales as ONE unit. Three readouts at three sizes would
     * read as three different kinds of number.
     *
     * Returns 1.0 when the row already fits; it never grows.
     *
     * @param totalContentPx Summed natural width of every cell.
     * @param rowWidthPx Width available to the whole row.
     */
    fun readoutRowScale(totalContentPx: Float, rowWidthPx: Float): Float {
        if (totalContentPx <= 0f || rowWidthPx <= 0f) return 1f
        val budget = rowWidthPx * READOUT_ROW_FILL.toFloat()
        return if (totalContentPx > budget) budget / totalContentPx else 1f
    }

    /**
     * Share of the row's width its content may occupy.
     *
     * Leaves a gutter between the three readouts and a margin at each end,
     * so cells sized to their content still read as separate cells.
     */
    const val READOUT_ROW_FILL = 0.94

    // --- the stats row moves ABOVE the scope in radar mode ---
    //
    // In instrument mode GEAR/RPM/FUEL sit along the bottom at h * 0.94,
    // and the centre column reserves the bottom 18% for them. Radar mode
    // cannot honour that: the scope is aligned to the navball's centre, so
    // its lower rim reaches far enough down that the demoted readouts and
    // the stats row land within ~12px of each other. Photographed on the
    // DHU as delta-V and Isp printed straight through GEAR / RPM / FUEL.
    //
    // Shrinking the scope to make room would undo the alignment that was
    // just fixed. Instead the stats row moves to the TOP of the column,
    // which is otherwise empty in radar mode -- the scope's own band starts
    // below it -- and the bottom band belongs to delta-V, Isp and speed.
    //
    // This is a radar-mode-only rearrangement. Instrument mode is untouched.

    /**
     * Baseline for the stats row in radar mode, as a fraction of panel height.
     *
     * Sits below the status strip (which owns the top 10% when it is shown)
     * and above the scope's band. Small enough that the scope keeps the
     * height it needs to stay level with the navball.
     */
    const val STATS_ROW_TOP_FRACTION_RADAR = 0.075

    /**
     * Where the scope's band begins in radar mode, as a fraction of panel
     * height, once the stats row is above it.
     *
     * The scope is CENTRED on the navball rather than laid out from this
     * point, so this is a floor the cluster must clear rather than an
     * origin — a test pins that the cluster top stays below it.
     */
    const val SCOPE_TOP_FLOOR_RADAR = 0.105

    /**
     * Radius of ring [index] in pixels, outermost being [RING_COUNT] - 1.
     *
     * Evenly spaced: ring *i* sits at `(i + 1) / RING_COUNT` of the scope
     * radius, so the outermost ring IS the scope edge and carries the range
     * label.
     */
    fun ringRadiusPx(index: Int, scopeRadiusPx: Float): Float {
        if (index < 0 || index >= RING_COUNT || scopeRadiusPx <= 0f) return 0f
        return scopeRadiusPx * (index + 1).toFloat() / RING_COUNT.toFloat()
    }

    /**
     * Range that ring [index] represents, in miles.
     *
     * Mirrors [ringRadiusPx] so a ring's label always matches its radius.
     * Returns a Double because 20 / 3 is not a whole number of miles; the
     * renderer decides how to round for display.
     */
    fun ringRangeMiles(index: Int, rangeMiles: Int): Double {
        if (index < 0 || index >= RING_COUNT) return 0.0
        return rangeMiles.toDouble() * (index + 1).toDouble() / RING_COUNT.toDouble()
    }

    /**
     * Step to the next scope range, wrapping at the end.
     *
     * Wrapping rather than clamping because this is driven by a button on a
     * head unit: a control that silently stops responding at the end of its
     * travel reads as broken when you cannot look at it.
     */
    fun nextRange(currentMiles: Int): Int {
        val i = RANGES_MILES.indexOf(currentMiles)
        if (i < 0) return DEFAULT_RANGE_MILES
        return RANGES_MILES[(i + 1) % RANGES_MILES.size]
    }

    /**
     * Convert a bearing and distance to scope coordinates, heading-up.
     *
     * Returns pixel offsets from the scope centre, x right and y **down**,
     * matching Canvas convention.
     *
     * The car points up the screen, so a target is drawn at its bearing
     * *relative to the car's heading*. A target due north with the car
     * heading north appears straight up; the same target with the car
     * heading east appears to the left.
     *
     * Returns null when the target is beyond the scope range, so the
     * renderer never has to clip — out of range is not drawn at all.
     *
     * @param bearingDeg True bearing to the target, degrees clockwise from north.
     * @param distanceMiles Distance to the target.
     * @param headingDeg The car's course over ground, degrees clockwise from north.
     */
    fun toScopeXY(
        bearingDeg: Double,
        distanceMiles: Double,
        headingDeg: Double,
        rangeMiles: Int,
        scopeRadiusPx: Float
    ): Pair<Float, Float>? {
        if (rangeMiles <= 0 || scopeRadiusPx <= 0f) return null
        if (distanceMiles < 0.0 || distanceMiles > rangeMiles) return null

        val relative = Math.toRadians(bearingDeg - headingDeg)
        val r = scopeRadiusPx * (distanceMiles / rangeMiles).toFloat()

        // Screen y is DOWN, and a relative bearing of zero must draw UP, so
        // the y term is negated. Getting this backwards puts every target on
        // the wrong side of the car, which is the kind of error that looks
        // plausible on a static screenshot and is obvious in motion.
        val x = r * Math.sin(relative).toFloat()
        val y = -r * Math.cos(relative).toFloat()
        return x to y
    }
}
