package dev.swordfish.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import dev.swordfish.physics.MountAutoCalibrator
import dev.swordfish.physics.NavballScale
import dev.swordfish.physics.OperatingState
import dev.swordfish.physics.SevenSegment
import dev.swordfish.physics.DisplayTheme
import dev.swordfish.physics.PanelLayout
import dev.swordfish.physics.RadarLayout
import dev.swordfish.physics.DisplayUnits
import dev.swordfish.physics.UnitSystem
import dev.swordfish.physics.Units
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the instrument panel onto the head-unit surface.
 *
 * ## Responsive by necessity, not politeness
 *
 * Android Auto lets the user toggle between full-width and split view at any
 * time by tapping the app button, so the panel must handle both. It does not
 * scale one design down: [PanelLayout] picks a *different* arrangement per
 * aspect ratio and drops elements in a documented order. Delta-V is the last
 * thing standing.
 *
 * All positions are fractions of the stable-area rect reported by
 * [onStableAreaChanged]. Nothing here is a fixed pixel.
 */
class GaugeRenderer(private val carContext: CarContext) : SurfaceCallback {

    private var surfaceContainer: SurfaceContainer? = null
    private var stableArea = Rect()
    private var visibleArea = Rect()


    @Volatile
    private var state: PanelState = PanelState.EMPTY

    /**
     * Display colour scheme. Green by default; red and amber are offered in
     * preferences because red is the *Ghost in the Machine* palette that
     * inspired the look.
     */
    /** Draw the faint unlit segments behind lit ones. */
    @Volatile
    var ghostSegments: Boolean = true

    /** Draw the faint scanline overlay. */
    @Volatile
    var scanlines: Boolean = true

    @Volatile
    var theme: DisplayTheme = DisplayTheme.DEFAULT
        set(value) {
            field = value
            applyTheme()
            render()
        }

    /**
     * What the centre column shows.
     *
     * Set from the MODE button on the head unit and persisted in `Prefs`, so
     * it survives the car screen being destroyed and recreated -- which
     * Android Auto does freely, switching to Maps and back.
     *
     * `@Volatile` and repainting on write, matching [theme]: the write
     * arrives on the main thread from the action strip, the read happens on
     * the render path.
     */
    @Volatile
    var centreContent: RadarLayout.CentreContent = RadarLayout.CentreContent.INSTRUMENTS
        set(value) {
            field = value
            render()
        }

    /** Scope range in statute miles. Also button-driven and persisted. */
    @Volatile
    var radarRangeMiles: Int = RadarLayout.DEFAULT_RANGE_MILES

    /**
     * Which units the LABELS use. Geometry is never affected.
     *
     * The scope's range is still requested from NOAA in miles -- see
     * RadarTile -- because that is what the bbox maths uses. Only the string
     * beside the rings changes.
     */
    @Volatile
    var unitSystem: UnitSystem = UnitSystem.DEFAULT
        set(value) {
            field = value
            render()
        }

    /**
     * One-shot geometry log, re-armed on every surface/area change.
     *
     * Layout defects are not diagnosable from a screenshot: the stable area
     * is not the DHU window, and several rounds of this session were lost to
     * inferring one from the other. Logging the ACTUAL rect and the ACTUAL
     * radii turns a guess into a measurement. One line per layout change,
     * not per frame -- at 20fps that would be a flood.
     */
    @Volatile
    private var logGeometry = true

    private val bgPaint = Paint()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val statValuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Solid fill for the roll pointer. */
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballSkyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballGroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanlinePaint = Paint()

    /** Lit segments. */
    private val segLitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Unlit segments -- the "ghost" layer that makes it read as a display. */
    private val segGhostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Range rings and the scope rim. */
    private val scopeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The car's own mark at the scope centre. */
    private val scopeShipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Radar imagery, filtered so the 512px source scales smoothly. */
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Reused so the render path allocates nothing per frame. */
    private val radarClip = Path()
    private val radarDst = RectF()

    /**
     * The current radar picture, pushed in from the screen.
     *
     * Held rather than reached for so the renderer has no dependency on the
     * fetcher -- it draws whatever it was last given, including nothing.
     */
    @Volatile
    var radarBitmap: android.graphics.Bitmap? = null

    /** True when the last fetch attempt failed and no picture has arrived. */
    @Volatile
    var radarFetchFailed: Boolean = false


    /** How far to lift the phosphor in day mode. */
    private val DAY_BOOST = 0.34f

    /**
     * Where the link banner sits in WIDE radar mode, as a fraction of the
     * panel width.
     *
     * The empty top-left corner: the pair is centred well below it and the
     * stats column is far to the right. Left-aligned from here, so a longer
     * label grows toward the middle rather than into the navball.
     */
    private val RADAR_BANNER_CX_FRACTION = 0.012f

    /**
     * Inset from the physical surface edge, as a fraction of its height.
     *
     * Small: the point of laying out against the full surface is to USE it.
     * This exists only so a circle's rim and the range label do not sit
     * exactly on the boundary.
     */
    private val SURFACE_EDGE_INSET = 0.02f

    init {
        // Monospace numerals: digits must not jitter in width as values
        // change. A real instrument concern, not an aesthetic one.
        valuePaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        statValuePaint.typeface = Typeface.MONOSPACE
        unitPaint.typeface = Typeface.MONOSPACE
        labelPaint.typeface = Typeface.DEFAULT
        accentPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        statPaint.typeface = Typeface.DEFAULT
        ballLinePaint.style = Paint.Style.STROKE
        ballMarkPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        ballMarkPaint.color = C_BALL_MARK
        applyTheme()
    }

    /**
     * Apply the current [DisplayTheme].
     *
     * Day mode does not invert the panel — it brightens it. Real instrument
     * clusters do not swap to a light scheme in daylight, and an inverted
     * phosphor display reads as a web page rather than an instrument.
     */
    private fun applyTheme() {
        val t = theme
        val boost = if (carContext.isDarkMode) 0f else DAY_BOOST

        val bright = lift(t.bright, boost)
        val mid = lift(t.mid, boost)
        val dim = lift(t.dim, boost)
        val ghost = lift(t.ghost, boost * 0.5f)
        val accent = lift(t.accent, boost)

        bgPaint.color = C_GROUND
        scanlinePaint.color = withAlpha(bright, 22)
        scanlinePaint.strokeWidth = 1f

        segLitPaint.color = bright
        segLitPaint.style = Paint.Style.FILL
        segGhostPaint.color = ghost
        segGhostPaint.style = Paint.Style.FILL

        valuePaint.color = bright
        statValuePaint.color = bright
        unitPaint.color = mid
        labelPaint.color = mid
        statPaint.color = mid
        accentPaint.color = accent
        ballSkyPaint.color = C_BALL_SKY
        ballGroundPaint.color = C_BALL_GROUND
        ballLinePaint.color = dim
        ballMarkPaint.color = bright

        // Rings are DIM, deliberately. They are a scale, not a reading --
        // the same reason the navball's grid sits at `dim` while its marks
        // sit at `bright`. Once real returns are painted over them, rings
        // competing with the weather would be actively harmful.
        scopeRingPaint.color = dim
        scopeRingPaint.style = Paint.Style.STROKE
        scopeShipPaint.color = bright
        scopeShipPaint.style = Paint.Style.FILL
    }

    private fun ghostPaintOrNull(): Paint? = if (ghostSegments) segGhostPaint else null

    /**
     * Colour for stat-row LABELS.
     *
     * Theme-aware rather than the hardcoded `C_PHOSPHOR_DIM` this replaced,
     * which ignored the red and amber palettes entirely and rendered green
     * labels on a red panel. Lifted in day mode by the same boost every
     * other colour gets.
     */
    private fun statLabelColour(): Int =
        lift(theme.mid, if (carContext.isDarkMode) 0f else DAY_BOOST)

    /** Convert a packed 0xRRGGBB theme colour to an opaque Android colour. */
    private fun lift(rgb: Int, boost: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val a = boost.coerceIn(0f, 1f)
        fun up(c: Int) = (c + (255 - c) * a).toInt().coerceIn(0, 255)
        return Color.rgb(up(r), up(g), up(b))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Push a new snapshot. Safe from the telemetry thread. */
    fun update(newState: PanelState) {
        state = newState
        render()
    }

    fun onConfigurationChanged() {
        applyTheme()
        render()
    }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        render()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = stableArea
        logGeometry = true
        // Logged because LAYOUT BUGS CANNOT BE DIAGNOSED FROM A SCREENSHOT.
        // Several rounds of this were spent inferring the rect from pixel
        // measurements of a DHU window and getting it wrong -- the stable
        // area is not the window, and the difference is exactly what the
        // instruments are sized against.
        android.util.Log.i(
            "SwordfishGeom",
            "stableArea=$stableArea visible=$visibleArea " +
                "container=${surfaceContainer?.width}x${surfaceContainer?.height}"
        )
        render()
    }


    // --- Drawing ---

    private fun render() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (container.width <= 0 || container.height <= 0) return

        // isValid is checked as well as null because a surface can be
        // abandoned by the host without onSurfaceDestroyed having reached
        // us yet -- the window between the two is exactly where the crash
        // below happened.
        if (!surface.isValid) return

        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            return
        } catch (e: IllegalStateException) {
            // Surface already locked or released.
            return
        } ?: return

        try {
            draw(canvas, container)
        } finally {
            // UNLOCK MUST BE GUARDED TOO.
            //
            // Observed 2026-08-20 on the real head unit: Android Auto's own
            // projection process crashed (its bug -- "No matching component
            // for intent" while binding our CarAppService), which tore the
            // surface down mid-frame. lockCanvas had already succeeded, so
            // this unlock ran against a dead surface and threw
            // IllegalArgumentException from native code -- killing OUR
            // process too, which is why the app vanished from the launcher.
            //
            // The host crashing is not something we can prevent. Dying with
            // it is. A dropped frame is the correct response to a surface
            // that disappeared underneath us.
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (e: IllegalArgumentException) {
                surfaceContainer = null
            } catch (e: IllegalStateException) {
                surfaceContainer = null
            }
        }
    }

    /**
     * Draw one frame. `internal` rather than private ONLY so the desktop
     * layout harness (`tools/layout-harness`) can call it directly against a
     * Java2D-backed Canvas; `render()` is unusable there because it locks a
     * real Surface. Nothing in the app should call this instead of render().
     */
    internal fun draw(canvas: Canvas, container: SurfaceContainer) {
        canvas.drawRect(
            0f, 0f, container.width.toFloat(), container.height.toFloat(), bgPaint
        )
        if (scanlines) drawScanlines(canvas, container)

        val area = if (stableArea.isEmpty) {
            Rect(0, 0, container.width, container.height)
        } else stableArea

        val layout = PanelLayout.choose(area.width(), area.height(), centreContent)
        val s = state

        // Instrument content starts BELOW the reserved status strip.
        //
        // Status text (HANDSHAKE, NO ADAPTER, DRIVE TO ORIENT) used to be
        // positioned by offsets from whatever it happened to sit near, and
        // two of them collided on the real head unit -- the link banner
        // landed on the Isp label, and DRIVE TO ORIENT was clipped by the
        // top edge while overlapping the attitude readout.
        //
        // Insetting here rather than inside each layout means WIDE and
        // NARROW both get it, and neither has to know the strip exists.
        // The strip collapses to zero when there is nothing to announce, so
        // a healthy panel gives up no height at all.
        val stripPx = if (s.announcesStatus &&
            PanelLayout.showsStatusStrip(layout.mode, area.height())
        ) {
            (area.height() * PanelLayout.statusStripFraction(layout.mode)).toInt()
        } else 0

        val content = if (stripPx > 0) {
            Rect(area.left, area.top + stripPx, area.right, area.bottom)
        } else area

        when (layout.mode) {
            // WIDE gets the FULL SURFACE for its instruments, not the stable
            // area.
            //
            // Measured on the DHU: container 800x400 but
            // stableArea=Rect(24, 88 - 776, 388) -- only 752x300, with the
            // top 88px reserved for the host's action-strip pills. Sizing
            // the circles against that left them at r=85 on a 400px-tall
            // surface, sitting low, which is what "too small and too low"
            // was. Every earlier attempt to fix it by nudging fractions
            // failed because the rect itself was the problem.
            //
            // The reserved band is real but the pills float TOP-RIGHT; the
            // left two thirds of it is empty. The Car App guidance is to
            // keep CRITICAL content in the stable area, and the elements
            // that reach into the band here are the tops of two circles and
            // a range label -- not values the driver reads. The stats
            // column, which IS text, stays inside the stable area.
            PanelLayout.Mode.WIDE -> drawWide(
                canvas,
                area = fullSurfaceArea(container),
                statsArea = area,
                layout = layout,
                s = s
            )
            else -> drawNarrow(canvas, content, layout, s)
        }

        // Drawn last so it sits above whichever layout ran.
        //
        // WIDE radar mode passes the FULL SURFACE, not the stable area.
        //
        // This was the real reason the banner kept landing on the navball:
        // the instruments lay out against the full surface (top=8) while
        // this was drawing against the stable area (top=88), so a baseline
        // 23px "from the top" rendered at y=111 -- a hundred pixels down,
        // straight into the ball. Moving it horizontally could never fix a
        // vertical mismatch.
        val bannerArea = if (layout.mode == PanelLayout.Mode.WIDE &&
            layout.shows(PanelLayout.Element.RADAR)
        ) {
            fullSurfaceArea(container)
        } else area
        drawLinkBanner(canvas, bannerArea, layout, s)
    }

    /**
     * Telemetry link state, top-centre, shown only when it is not LIVE.
     *
     * A working instrument should not spend pixels saying so — moving
     * numbers are the announcement. But every failure otherwise renders as
     * dashes, and on a first drive "dongle unpaired", "ignition in
     * accessory", and "link dropped mid-drive" are three different problems
     * that look identical without this.
     *
     * Deliberately small and top-anchored: it must not compete with the
     * delta-V readout, which is the one thing that has to be readable in
     * half a second.
     */
    private fun drawLinkBanner(
        canvas: Canvas, area: Rect, layout: PanelLayout.Resolved, s: PanelState
    ) {
        // A running MS-CAN capture takes over this headline.
        //
        // The capture owns the socket, so the link is legitimately LOST and
        // the panel would say so -- accurate and useless, since it describes
        // a consequence of what the driver deliberately started rather than
        // the thing itself. Reporting the capture here answers "is it working"
        // without a glance at the phone.
        //
        // NOTHING about the layout changes: same slot, same paint, same
        // baselines, same fault styling. Only the strings and the fault flag
        // come from a different source.
        val banner = s.msCanBanner
        if (!s.announcesStatus) return
        if (!PanelLayout.showsStatusStrip(layout.mode, area.height())) return

        val label = banner?.label ?: s.linkState.label
        val hint = banner?.hint ?: s.linkState.hint
        val isFault = banner?.isFault ?: s.linkState.isFault

        val h = area.height().toFloat()

        statPaint.color = if (isFault) C_AMBER else C_PHOSPHOR_MID
        statPaint.textAlign = Paint.Align.CENTER
        statPaint.textSize = h * PanelLayout.statusTextFraction(layout.mode)

        // IN WIDE RADAR MODE THE CENTRE IS OCCUPIED.
        //
        // The banner is centred on the panel, which used to be the delta-V
        // column -- text over text, both top-anchored, no conflict. In radar
        // mode the panel centre is the scope, so the banner printed straight
        // across its range label and rim ("80 MI" / "HANDSHAKE" on top of
        // each other, photographed on the DHU).
        //
        // The scope has no spare margin to give: it is a circle, and its
        // widest point is exactly where the banner would sit. So the banner
        // moves to the one region with room -- above the stats column, which
        // starts below this band.
        // In WIDE radar mode the banner goes TOP-LEFT, left-aligned.
        //
        // Two placements were tried and both landed on something: the panel
        // centre is the scope (it printed across the range label), and above
        // the stats column is the stat rows (it printed across GEAR).
        //
        // A circle pair leaves no free horizontal lane at its own height,
        // but the panel's top-left corner is empty -- the navball cluster
        // begins below it and the pair is centred lower. Left-aligned rather
        // than centred so its width cannot grow back into the navball.
        // Centred, and as high as the surface allows.
        //
        // Now that it draws against the full surface it clears the pair
        // vertically, so it no longer has to dodge them horizontally --
        // which is what the earlier left-aligned and stats-column
        // placements were both trying and failing to do.
        val radarWide = layout.mode == PanelLayout.Mode.WIDE &&
            layout.shows(PanelLayout.Element.RADAR)
        statPaint.textAlign = Paint.Align.CENTER
        val cx = area.left + area.width() / 2f
        // Radar mode pins the baseline to the surface top rather than
        // scaling it off the panel height: the point is to be out of the
        // way, and one text ascent of clearance is all that needs.
        val baseline = if (radarWide) {
            area.top + statPaint.textSize * 1.05f
        } else {
            area.top + h * PanelLayout.statusBaselineFraction(layout.mode)
        }
        canvas.drawText(label, cx, baseline, statPaint)

        // The hint explains the remedy, which the label alone cannot. Only
        // for faults: spelling out a transient handshake step would be noise.
        if (isFault && hint.isNotEmpty()) {
            statPaint.color = C_PHOSPHOR_DIM
            statPaint.textSize = h * PanelLayout.statusTextFraction(layout.mode) * 0.62f
            val hintBaseline = if (radarWide) {
                baseline + statPaint.textSize * 1.15f
            } else {
                area.top + h * PanelLayout.statusHintBaselineFraction(layout.mode)
            }
            canvas.drawText(hint, cx, hintBaseline, statPaint)
        }
        // Restored: statPaint is shared, and a stale LEFT alignment would
        // silently shift the next centred text that borrows it.
        statPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Faint horizontal scanlines.
     *
     * The single cheapest cue that sells a phosphor display -- without it the
     * green reads as "dark theme", with it as "instrument". Kept very low
     * contrast so it is felt rather than seen, and spaced in device pixels so
     * it does not moire on a high-DPI head unit.
     */
    private fun drawScanlines(canvas: Canvas, container: SurfaceContainer) {
        val spacing = 3f
        if (container.height / spacing > 900) return  // too dense to be worth it
        var y = 0f
        while (y < container.height) {
            canvas.drawLine(0f, y, container.width.toFloat(), y, scanlinePaint)
            y += spacing
        }
    }

    /** Three zones: navball left, delta-V centre, stats right. */
    /**
     * Width the two circles share: from the surface's left edge to where the
     * stats block actually begins.
     *
     * **Measured to the stats block's real left edge, not `surfaceWidth -
     * statsWidth`.** The stats column is sized from the STABLE area (it is
     * text and must stay clear of host chrome) but the pair is laid out on
     * the FULL surface, so subtracting a stable-area width from a
     * full-surface width left the span 24px too wide and pushed the pair
     * right by half of that.
     *
     * Split out so both call sites cannot drift -- they must agree exactly
     * or the navball and the scope get packed against different spans and
     * the gap between them stops matching the one that was computed.
     */
    private fun pairSpanPx(statsLeftPx: Float, surfaceLeftPx: Float): Float =
        statsLeftPx - surfaceLeftPx

    /**
     * The surface rect the WIDE instruments lay out against.
     *
     * Deliberately the FULL container rather than the stable area, inset
     * only enough that a circle's rim does not touch the physical edge. See
     * the note at the call site for why this is safe.
     */
    private fun fullSurfaceArea(container: SurfaceContainer): Rect {
        val inset = (container.height * SURFACE_EDGE_INSET).toInt()
        return Rect(0, inset, container.width, container.height - inset)
    }

    /**
     * @param area the rect the INSTRUMENTS lay out against — the full
     *   surface, so the circles are sized against the real height.
     * @param statsArea the STABLE rect, which the stats column uses. Text
     *   must not stray under host chrome, so the two rects differ
     *   deliberately.
     */
    private fun drawWide(
        canvas: Canvas, area: Rect, statsArea: Rect,
        layout: PanelLayout.Resolved, s: PanelState
    ) {
        val w = area.width().toFloat()
        val h = area.height().toFloat()

        // No strip reservation at full width.
        //
        // The banner used to be centred on the panel, so the scope had to
        // yield a band to it. It now sits over the STATS column instead
        // (see drawLinkBanner), which frees the pair from paying for it --
        // and paying for it was what made the scope shrink and its centre
        // drift away from the navball's.
        val stripPx = 0f

        val navW = (w * layout.navballColumnFraction).toFloat()
        val statsW = (statsArea.width() * layout.statsColumnFraction).toFloat()
        val centreLeft = area.left + navW
        val centreW = w - navW - statsW

        // Radar mode PACKS the two circles instead of centring each in its
        // own column.
        //
        // On a head unit both are HEIGHT-bound -- measured at 778x406, the
        // navball's width cap is 124 against a height cap of 115, the
        // scope's 161 against 119 -- so the column fractions do not change
        // their size at all, and centring each in a column it cannot fill
        // puts every spare pixel BETWEEN them. Photographed on the DHU: a
        // 118px void down the middle of the panel.
        //
        // Packed by diameter, the gap becomes 37px and the slack moves to
        // the outside where it reads as margin instead of a hole.
        val packed = layout.shows(PanelLayout.Element.RADAR) &&
            layout.shows(PanelLayout.Element.NAVBALL)

        // The banner owns the top band whenever it is showing, so the pair
        // yields it rather than the banner dodging sideways.
        val bannerBand = if (s.announcesStatus) {
            PanelLayout.WIDE_BANNER_BAND.toFloat()
        } else 0f

        // Derived once and shared: the centre that balances the navball's
        // extents is the one that lets the pair be largest.
        val pairCentreFraction = PanelLayout.pairCentreFraction(
            PanelLayout.tuned("WIDE_READOUT_BAND", PanelLayout.WIDE_READOUT_BAND.toFloat()), bannerBand
        )

        if (layout.shows(PanelLayout.Element.NAVBALL)) {
            // Radius and centre both come from PanelLayout, which knows the
            // cluster's REAL extent -- ball, attitude readout and prompt
            // above, three-row compass strip below.
            //
            // This replaced `min(navW * 0.40f, h * 0.28f)` with a centre at
            // h * 0.40f. That pair was wrong twice over: 0.40 of the column
            // threw away a fifth of the width available, and the 0.28 height
            // cap never engaged on any real geometry, so the ball was always
            // width-bound and always smaller than the panel could carry. The
            // upward bias then over-reserved for the compass, leaving the
            // dead band visible under the ball on the real head unit.
            // Measured against the height this column really owns -- the
            // content rect PLUS the status strip that was deducted from it
            // but is drawn top-centre, over the centre column only.
            //
            // Without this the cluster sat with 47px of dead space above it
            // and 8px below, pushed down by chrome that renders elsewhere.
            // ONE radius, ONE centre, for BOTH circles.
            //
            // They had separate formulas before -- the navball dividing its
            // budget by its full extent, the scope taking the min of two
            // half extents -- so the same space produced navR=91.5 against
            // scopeR=103.7 (measured on the DHU). Worse, the scope was SIZED
            // against a strip-reclaimed centre and DRAWN at the un-reclaimed
            // one, 20px apart, which is what walked its lower rim into the
            // readout row.
            //
            // A pair that must look like a pair gets one rule.
            val navRadius = PanelLayout.pairRadiusPx(
                navColumnWidthPx = navW,
                scopeColumnWidthPx = centreW,
                heightPx = h,
                centreFraction = pairCentreFraction,
                bottomReservedFraction = PanelLayout.tuned("WIDE_READOUT_BAND", PanelLayout.WIDE_READOUT_BAND.toFloat()),
                topReservedFraction = bannerBand
            )

            if (logGeometry) {
                android.util.Log.i(
                    "SwordfishGeom",
                    "WIDE area=$area h=$h navW=$navW navR=$navRadius " +
                        "cf=$pairCentreFraction cy=" +
                        (area.top + h * pairCentreFraction) +
                        " packed=$packed centreW=$centreW"
                )
                logGeometry = false
            }

            // Both circles are sized first, then placed together, so the
            // pair is positioned by what it actually occupies rather than by
            // columns neither of them fills.
            val navCx = if (packed) {
                area.left + PanelLayout.packInstrumentPair(
                    pairSpanPx(statsArea.right - statsW, area.left.toFloat()),
                    navRadius, navRadius
                ).first
            } else {
                area.left + navW / 2f
            }

            // Per-instrument nudge on top of the paired radius. All three
            // default to no change, so the pair rule is what ships; they
            // exist so the navball can be deliberately sized or moved
            // independently of the scope. See PanelLayout.NAVBALL_SCALE.
            drawNavball(
                canvas,
                cx = navCx + w.toFloat() *
                    PanelLayout.tuned("NAVBALL_DX", PanelLayout.NAVBALL_DX),
                cy = area.top + h * pairCentreFraction +
                    h * PanelLayout.tuned("NAVBALL_DY", PanelLayout.NAVBALL_DY),
                radius = navRadius *
                    PanelLayout.tuned("NAVBALL_SCALE", PanelLayout.NAVBALL_SCALE),
                s = s
            )
        }

        // The scope's drawing rect is shifted so its CENTRE lands where the
        // pack put it, while keeping its width for the readout row beneath.
        val centreDrawLeft = if (packed) {
            val r = PanelLayout.pairRadiusPx(
                navW, centreW, h, pairCentreFraction,
                PanelLayout.tuned("WIDE_READOUT_BAND", PanelLayout.WIDE_READOUT_BAND.toFloat()), bannerBand
            )
            val scopeCx = area.left + PanelLayout.packInstrumentPair(
                pairSpanPx(statsArea.right - statsW, area.left.toFloat()), r, r
            ).second
            scopeCx - centreW / 2f
        } else centreLeft

        drawCentre(
            canvas,
            left = centreDrawLeft, width = centreW,
            top = area.top.toFloat(), height = h,
            panelTop = area.top.toFloat(), panelHeight = h,
            layout = layout, s = s, stripPx = stripPx,
            navColumnWidthPx = navW
        )

        if (layout.shows(PanelLayout.Element.STATS_BLOCK)) {
            // The stats block reclaims the status strip too, for the same
            // reason the circles do: the banner is top-CENTRE and paints
            // nothing over this column. Without it the block's centre sat
            // 28px below the instruments' shared centre -- measured, and
            // visible on the DHU as a stack that reads low against the
            // circles beside it.
            // Stats live in the STABLE area: they are text, and text under
            // the host's pills is unreadable. Centred on the instruments'
            // centre so the three elements still read as one row.
            val statsH = statsArea.height().toFloat()
            val instrumentCy = area.top + h * PanelLayout.navballCentreFraction()
            val statsBlockH = statsH * 0.72f
            // Per-element nudge. Scale grows the block about its own
            // centre, so resizing does not also slide it -- a box that
            // moved when you resized it would be maddening to aim.
            val statsScale = PanelLayout.tuned("STATS_SCALE", PanelLayout.STATS_SCALE)
            val statsLeft0 = statsArea.right - statsW
            val statsTop0 = (instrumentCy - statsBlockH / 2f)
                .coerceAtLeast(statsArea.top.toFloat())
            val statsCx = statsLeft0 + statsW / 2f
            val statsCy = statsTop0 + statsBlockH / 2f
            val statsW2 = statsW * statsScale
            val statsH2 = statsBlockH * statsScale
            drawStats(
                canvas,
                left = statsCx - statsW2 / 2f +
                    w * PanelLayout.tuned("STATS_DX", PanelLayout.STATS_DX),
                width = statsW2,
                top = statsCy - statsH2 / 2f +
                    h * PanelLayout.tuned("STATS_DY", PanelLayout.STATS_DY),
                height = statsH2,
                s = s
            )
        }
    }

    /**
     * Split view: navball on the left, figure and bar on the right, stats
     * along the bottom.
     *
     * Using the width rather than centring one tall column is what makes room
     * for the navball at a readable size. A centred layout wastes both flanks.
     */
    private fun drawNarrow(
        canvas: Canvas, area: Rect, layout: PanelLayout.Resolved, s: PanelState
    ) {
        val w = area.width().toFloat()
        val h = area.height().toFloat()

        val hasStats = layout.shows(PanelLayout.Element.STATS_BLOCK)
        // Reserve the bottom strip for stats so nothing can overlap it.
        val bodyH = if (hasStats) h * 0.82f else h

        if (layout.shows(PanelLayout.Element.NAVBALL)) {
            val navW = (w * layout.navballColumnFraction).toFloat()
            // Split view measures against the FULL panel height, not bodyH.
            //
            // The ball was HEIGHT-bound here -- the opposite of WIDE -- so
            // widening the column did nothing at all (verified: 0.38 through
            // 0.62 all give the same radius). The height was being spent
            // before the ball saw it: a 10% status strip and an 18% stats
            // strip came off first, leaving the cluster 42% of the panel.
            //
            // Neither of those draws pixels in THIS column. The status banner
            // is top-centre and the stats row is centred text starting well
            // to the right. So the column takes its own full height and the
            // stats row yields the space beneath the ball instead.
            //
            // The compass scales with the ball and holds its position --
            // everything in drawNavball is expressed in radii, so the whole
            // instrument grows as one.
            // NARROW's own navball knobs -- never the WIDE ones. This
            // column is a fraction of the full panel's width and shares
            // none of its slack.
            val navRadius = PanelLayout.navballRadiusNarrowPx(navW, h) *
                PanelLayout.tuned("NAVBALL_SCALE_N", PanelLayout.NAVBALL_SCALE_N)
            drawNavball(
                canvas,
                cx = area.left + navW / 2f +
                    w * PanelLayout.tuned("NAVBALL_DX_N", PanelLayout.NAVBALL_DX_N),
                cy = area.top + h * PanelLayout.navballCentreNarrowFraction() +
                    h * PanelLayout.tuned("NAVBALL_DY_N", PanelLayout.NAVBALL_DY_N),
                radius = navRadius,
                s = s
            )
            drawCentre(
                canvas,
                left = area.left + navW, width = w - navW,
                top = area.top.toFloat(), height = bodyH,
                panelTop = area.top.toFloat(), panelHeight = h,
                layout = layout, s = s
            )
        } else {
            drawCentre(
                canvas,
                left = area.left.toFloat(), width = w,
                top = area.top.toFloat(), height = bodyH,
                panelTop = area.top.toFloat(), panelHeight = h,
                layout = layout, s = s
            )
        }

        if (hasStats) {
            // Start the row AFTER the navball column. The enlarged ball
            // reaches the bottom of its column, and the row's first cell
            // (GEAR) would otherwise sit underneath it. Yielding the column
            // costs the row some width, which it absorbs by distributing its
            // four cells across a narrower span -- cheaper than shrinking the
            // instrument.
            val statsLeft = if (layout.shows(PanelLayout.Element.NAVBALL)) {
                w * PanelLayout.statsRowLeftFraction(layout.navballColumnFraction)
            } else 0f

            // RADAR MODE PUTS THE STATS ROW AT THE TOP.
            //
            // The scope is aligned to the navball's centre, so its lower rim
            // reaches far enough down that the demoted delta-V/Isp row and
            // this one landed within ~12px of each other -- photographed on
            // the DHU as delta-V printed straight through GEAR / RPM / FUEL.
            //
            // Shrinking the scope would undo the alignment. Moving this row
            // into the otherwise-empty top band costs nothing: the scope's
            // own band starts below it either way.
            val radar = layout.shows(PanelLayout.Element.RADAR)
            val statsY = if (radar) {
                area.top + h * RadarLayout.STATS_ROW_TOP_FRACTION_RADAR.toFloat()
            } else {
                area.top + h * 0.94f
            }

            // NARROW's stats are a ROW, not a column, so its scale knob
            // drives TEXT SIZE rather than a block height. Same knob name,
            // different meaning per breakpoint -- which is exactly why the
            // two layouts must not share a value.
            drawStatRow(
                canvas,
                left = area.left + statsLeft +
                    w * PanelLayout.tuned("STATS_DX_N", PanelLayout.STATS_DX_N),
                width = w - statsLeft,
                y = statsY + h * PanelLayout.tuned("STATS_DY_N", PanelLayout.STATS_DY_N),
                textSize = h * 0.052f *
                    PanelLayout.tuned("STATS_SCALE_N", PanelLayout.STATS_SCALE_N),
                s = s
            )
        }
    }

    /**
     * The centre column: Isp above, delta-V in the middle, speed below.
     *
     * Three live values in one glance, all as segments, in descending order of
     * how fast they change. Isp responds to the right foot instant to instant;
     * delta-V drains over minutes; speed sits between.
     *
     * **Speed is shown in m/s deliberately.** It is the same unit as delta-V,
     * which makes the relationship legible — at cruise the budget is roughly
     * 260x the current speed. A mph readout would break that, and the car
     * already has a speedometer.
     *
     * The Isp *bar* that used to sit here was removed: with Isp having its own
     * numeric readout the bar showed the same thing twice, and its lamp read as
     * decoration rather than information.
     */
    private fun drawCentre(
        canvas: Canvas, left: Float, width: Float, top: Float, height: Float,
        panelTop: Float, panelHeight: Float,
        layout: PanelLayout.Resolved, s: PanelState, stripPx: Float = 0f,
        navColumnWidthPx: Float = 0f
    ) {
        // Radar mode takes the column over entirely: the scope where the
        // headline was, delta-V and Isp demoted to a line beneath it.
        if (layout.shows(PanelLayout.Element.RADAR)) {
            drawRadarColumn(
                canvas, left, width, top, height, panelTop, panelHeight,
                layout, s, stripPx, navColumnWidthPx
            )
            return
        }

        val cx = left + width / 2f
        val narrow = layout.mode != PanelLayout.Mode.WIDE

        // Three readouts on an even vertical rhythm: Isp at 12% of the
        // column, delta-V centred at 44%, speed at 78%. Previously they were
        // bunched toward the top with a large gap beneath the speed row.
        // --- specific impulse ---
        //
        // Per-element nudge: scale grows the block, dx/dy move it. cx is
        // shifted per block so each can be aimed independently of the column.
        val ispScale = PanelLayout.tunedFor(
            layout.mode, "ISP_SCALE", PanelLayout.ISP_SCALE, PanelLayout.ISP_SCALE_N
        )
        val ispH = height * 0.115f * ispScale
        val ispTop = top + height * 0.12f +
            height * PanelLayout.tunedFor(
                layout.mode, "ISP_DY", PanelLayout.ISP_DY, PanelLayout.ISP_DY_N
            )
        val ispCx = cx + width * PanelLayout.tunedFor(
            layout.mode, "ISP_DX", PanelLayout.ISP_DX, PanelLayout.ISP_DX_N
        )
        val isp = s.effectiveIsp

        // While idling, Isp is honestly zero — fuel is flowing and no force
        // is being overcome. A dash would say the same thing as "engine
        // off" and "steep descent", which are not the same thing at all.
        //
        // The jet analogy settles what to show instead: an engine at the
        // hold line displays FUEL FLOW, because flow is the meaningful
        // number when thrust is doing nothing. It also makes visible that
        // idling drains the delta-V budget with the odometer stopped.
        val idling = s.operatingState == OperatingState.IDLE
        val flow = s.fuelFlowKgPerSec

        val ispLabel: String
        val ispText: String
        val ispUnit: String

        if (idling && flow != null && flow > 0.0) {
            ispLabel = "IDLE BURN"
            // Auto-scaled to whole numbers: mL/h at idle, L/h under load.
            // The unit travels with the value because the scale switches.
            val burn = GaugeFormat.formatFuelFlow(flow)
            ispText = burn.value
            ispUnit = burn.unit
        } else {
            ispLabel = if (s.operatingState.hasMeaningfulIsp) "Isp"
            else s.operatingState.label
            ispText = if (isp != null && isp > 0.0) {
                GaugeFormat.formatInteger(Math.round(isp))
            } else GaugeFormat.NO_DATA
            ispUnit = "s"
        }

        // MINIMAL declares only DELTA_V + ORBITAL_LABEL: a surface that short
        // "cannot stack anything under the figure". Honour that -- drawing
        // Isp and SPEED anyway is what made the 800x180 case a smear of
        // overlapping text in the layout harness.
        if (layout.shows(PanelLayout.Element.ISP_BAR)) {
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize = height * 0.045f
            // Amber while wasting fuel: the one state the game exists to
            // penalise should not read in the same calm phosphor as a good one.
            //
            // The previous colour is captured and restored rather than reset to
            // a constant -- labelPaint.color is theme-driven (see applyTheme),
            // so hardcoding green here would break the red and amber palettes.
            val labelColour = labelPaint.color
            if (idling) labelPaint.color = C_AMBER
            canvas.drawText(ispLabel, ispCx, ispTop - height * 0.018f, labelPaint)
            labelPaint.color = labelColour
            drawSegmentsWithUnit(
                canvas, ispText, ispUnit, ispCx, ispTop, ispH, width, height
            )
        }

        // --- the headline ---
        val dvScale = PanelLayout.tunedFor(
            layout.mode, "DELTAV_SCALE",
            PanelLayout.DELTAV_SCALE, PanelLayout.DELTAV_SCALE_N
        )
        val segH = height * PanelLayout.deltaVTextFraction(layout.mode) * dvScale
        val dvTop = top + height * (
            if (narrow) PanelLayout.tuned("DV_TOP_NARROW", PanelLayout.DV_TOP_NARROW)
            else PanelLayout.tuned("DV_TOP_WIDE", PanelLayout.DV_TOP_WIDE)
        ) + height * PanelLayout.tunedFor(
            layout.mode, "DELTAV_DY", PanelLayout.DELTAV_DY, PanelLayout.DELTAV_DY_N
        )
        val dvCx = cx + width * PanelLayout.tunedFor(
            layout.mode, "DELTAV_DX", PanelLayout.DELTAV_DX, PanelLayout.DELTAV_DX_N
        )

        labelPaint.textSize = height * PanelLayout.labelTextFraction(layout.mode)
        canvas.drawText(GaugeFormat.LABEL, dvCx, dvTop - height * 0.035f, labelPaint)

        val dv = s.deltaVMps
        val dvText = if (dv == null || !dv.isFinite()) {
            GaugeFormat.NO_DATA
        } else GaugeFormat.formatDeltaV(dv)

        SegmentDisplay.drawCentred(
            canvas, dvText, dvCx, dvTop, segH, segLitPaint, ghostPaintOrNull()
        )
        // Units, and on the same line the budget this drive STARTED at.
        //
        // The remaining budget means little alone: 3075 is only meaningful
        // against the 7129 it began at. It is the same reference a jet's
        // pilot has -- full tank at the hold line, counting down.
        //
        // ONE LINE, not two. The desktop harness measured this column on the
        // real ND2 surface: only 18px separate the units line from the SPEED
        // block, while a START line of its own needs ~34px once clearance
        // above and below is counted. Two lines cannot both fit, and every
        // arrangement that tried collided with something -- first with
        // SPEED, then with the units line itself. Merging costs no vertical
        // space at all and keeps the reference directly under the figure it
        // annotates. See CentreColumnSpacingTest.
        //
        // Deliberately small and dim: a reference, not a reading, and it
        // must never compete with the headline that has to be legible in
        // half a second. Plain type rather than segments for the same
        // reason -- segments read as live data.
        unitPaint.textAlign = Paint.Align.CENTER
        unitPaint.textSize = height * 0.050f
        val start = s.tripStartDeltaV
        val unitsLine = if (start != null && start.isFinite() && start > 0.0) {
            "${GaugeFormat.UNITS}  ${GaugeFormat.START_PREFIX} ${GaugeFormat.formatDeltaV(start)}"
        } else {
            GaugeFormat.UNITS
        }
        canvas.drawText(
            unitsLine, dvCx,
            dvTop + segH + height *
                PanelLayout.tuned("UNITS_LINE_OFFSET", PanelLayout.UNITS_LINE_OFFSET),
            unitPaint
        )

        // --- speed, in the space the bar used to occupy ---
        //
        // Gated with Isp: on a MINIMAL surface delta-V is the last thing
        // standing, and the space this would occupy does not exist.
        if (!layout.shows(PanelLayout.Element.ISP_BAR)) return
        val spdScale = PanelLayout.tunedFor(
            layout.mode, "SPEED_SCALE",
            PanelLayout.SPEED_SCALE, PanelLayout.SPEED_SCALE_N
        )
        val spdH = height * 0.125f * spdScale
        val spdTop = top + height * PanelLayout.tuned("SPEED_TOP", PanelLayout.SPEED_TOP) +
            height * PanelLayout.tunedFor(
                layout.mode, "SPEED_DY", PanelLayout.SPEED_DY, PanelLayout.SPEED_DY_N
            )
        val spdCx = cx + width * PanelLayout.tunedFor(
            layout.mode, "SPEED_DX", PanelLayout.SPEED_DX, PanelLayout.SPEED_DX_N
        )
        val speed = s.speedMps
        val spdText = if (speed != null && speed.isFinite()) {
            GaugeFormat.formatInteger(Math.round(speed))
        } else GaugeFormat.NO_DATA

        labelPaint.textSize = height * PanelLayout.SECTION_LABEL_FRACTION
        canvas.drawText(
            "SPEED", spdCx,
            spdTop - height *
                PanelLayout.tuned("SECTION_LABEL_LIFT", PanelLayout.SECTION_LABEL_LIFT),
            labelPaint
        )
        drawSegmentsWithUnit(
            canvas, spdText, "m/s", spdCx, spdTop, spdH, width, height
        )
    }

    /**
     * The centre column in radar mode: scope above, demoted readouts below.
     *
     * ## What is deliberately NOT here
     *
     * There is no basemap and no route. A tile source would need an API key
     * and a quota, and neither answers the question the scope exists for --
     * *is there weather between me and where I am going.* Range rings give
     * distance and the rose gives orientation; everything else on the scope
     * will be precipitation once the imagery lands.
     *
     * ## The scope is empty until the imagery layer exists
     *
     * This draws the container: rings, rose, range label and the car's mark.
     * The NOAA fetch is a separate piece of work, so the scope currently
     * reports NO DATA rather than pretending. That is the honest state and
     * it is also the state the panel must survive on every drive through a
     * tunnel, so it is worth having drawn correctly from the start.
     */
    /**
     * @param panelTop Top of the FULL panel, and [panelHeight] its full
     *   height -- not the body height left over after the stats row. Split
     *   view sizes and centres the scope against these so it lands on the
     *   navball's centre, which is measured the same way. Ignored at full
     *   width, where the scope owns its band outright.
     */
    private fun drawRadarColumn(
        canvas: Canvas, left: Float, width: Float, top: Float, height: Float,
        panelTop: Float, panelHeight: Float,
        layout: PanelLayout.Resolved, s: PanelState, stripPx: Float,
        navColumnWidthPx: Float = 0f
    ) {
        val cx = left + width / 2f

        // BOTH modes align the scope to the navball beside it. Two circular
        // instruments at different heights read as a mistake -- the eye
        // catches the step before it reads either one.
        //
        // They differ only in WHICH navball centring rule applies, because
        // split view measures the ball against the full panel height while
        // full width measures it against the content band.
        val narrow = layout.mode != PanelLayout.Mode.WIDE

        val radius: Float
        val cy: Float
        if (narrow) {
            // Measured against the FULL panel height, like the navball: the
            // stats row is drawn elsewhere and its reservation must not
            // shift the shared centre. Same reasoning as
            // PanelLayout.navballRadiusNarrowPx.
            radius = RadarLayout.scopeRadiusNarrowPx(width, panelHeight)
            cy = panelTop + panelHeight * RadarLayout.scopeCentreNarrowFraction()
        } else {
            // The SAME radius and the SAME centre the navball got. This is
            // the single sizing rule; there is deliberately no scope-specific
            // formula here any more.
            val band = PanelLayout.tuned("WIDE_READOUT_BAND", PanelLayout.WIDE_READOUT_BAND.toFloat())
            val topBand = if (s.announcesStatus) {
                PanelLayout.WIDE_BANNER_BAND.toFloat()
            } else 0f
            val cf = PanelLayout.pairCentreFraction(band, topBand)
            radius = PanelLayout.pairRadiusPx(
                navColumnWidthPx = navColumnWidthPx,
                scopeColumnWidthPx = width,
                heightPx = height,
                centreFraction = cf,
                bottomReservedFraction = band,
                topReservedFraction = topBand
            )
            cy = top + height * cf
        }
        if (radius <= 0f) return

        // Per-instrument nudge, applied ONLY to the scope's own draw.
        //
        // drawRadarReadouts keeps the UNSCALED radius on purpose: it uses it
        // to work out where the row sits below the scope, and that spacing is
        // pinned by RadarReadoutBandTest. Scaling it here too would move the
        // readouts as a side effect of resizing the circle, which is the
        // coupling these knobs exist to remove.
        drawScope(
            canvas,
            cx + width * PanelLayout.tunedFor(
                layout.mode, "SCOPE_DX", PanelLayout.SCOPE_DX, PanelLayout.SCOPE_DX_N
            ),
            cy + height * PanelLayout.tunedFor(
                layout.mode, "SCOPE_DY", PanelLayout.SCOPE_DY, PanelLayout.SCOPE_DY_N
            ),
            radius * PanelLayout.tunedFor(
                layout.mode, "SCOPE_SCALE",
                PanelLayout.SCOPE_SCALE, PanelLayout.SCOPE_SCALE_N
            ),
            s
        )
        drawRadarReadouts(
            canvas, cx, top, width, height, narrow,
            panelTop, panelHeight, radius, stripPx, s
        )
    }

    /**
     * The scope itself: range rings, compass rose, and the car at the centre.
     *
     * Heading-up, matching the navball beside it. A north-up scope next to a
     * heading-up navball would make the driver do the rotation in their head
     * at exactly the moment they should not be.
     */
    private fun drawScope(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, s: PanelState
    ) {
        scopeRingPaint.strokeWidth = (radius * 0.012f).coerceAtLeast(1f)

        for (i in 0 until RadarLayout.RING_COUNT) {
            canvas.drawCircle(cx, cy, RadarLayout.ringRadiusPx(i, radius), scopeRingPaint)
        }

        // Cross hairs, broken at the centre so they do not obscure the car
        // mark. Quarter-length stubs at the rim read as a bearing scale
        // without drawing lines across the whole weather picture.
        val stub = radius * 0.14f
        for (i in 0 until 4) {
            val angle = Math.toRadians(i * 90.0)
            val sx = Math.sin(angle).toFloat()
            val sy = -Math.cos(angle).toFloat()
            canvas.drawLine(
                cx + sx * (radius - stub), cy + sy * (radius - stub),
                cx + sx * radius, cy + sy * radius,
                scopeRingPaint
            )
        }

        // Imagery UNDER the rings and rose: the rings are a scale to read
        // the weather against, so they must sit on top of it.
        drawRadarImagery(canvas, cx, cy, radius, s)

        drawScopeRose(canvas, cx, cy, radius, s)
        drawScopeRangeLabel(canvas, cx, cy, radius)
        drawShipMark(canvas, cx, cy, radius)

        // Status only when there is nothing to show.
        //
        // An empty scope reads identically to "clear skies", which is the one
        // confusion a weather display must never create -- so silence is not
        // an option, but neither is labelling a working picture.
        val status = radarStatus(s)
        if (status != null) {
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize = radius * 0.155f
            canvas.drawText(status, cx, cy + radius * 0.52f, labelPaint)
        }
    }

    /**
     * The radar picture, rotated heading-up and clipped to the scope.
     *
     * ## Why the bitmap is rotated rather than the request
     *
     * WMS returns north-up imagery; the scope is heading-up, matching the
     * navball beside it. Rotating the canvas is one matrix operation per
     * frame and costs nothing measurable, where asking the service for a
     * rotated image is not possible at all.
     *
     * ## Why it is clipped to a circle
     *
     * The bbox is the scope's bounding SQUARE, so its corners hold weather
     * further away than the outer ring claims -- at the diagonal, 1.41x the
     * range. Drawing those corners would put returns outside the ring that
     * says how far the scope sees, which is a lie about distance. The clip
     * discards them.
     */
    private fun drawRadarImagery(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, s: PanelState
    ) {
        // Read the field ONCE into a local. Re-reading it below could pick
        // up a different bitmap mid-draw, which is the shape of the bug this
        // guards against.
        val bmp = radarBitmap ?: return

        // Defence in depth. RadarSource no longer recycles anything (see the
        // note in its fetchNow), so this should never fire -- but an
        // isRecycled check is CHECK-THEN-USE and cannot be relied on as the
        // primary defence: the bitmap can be freed between this line and the
        // drawBitmap below. The real fix is that nothing recycles it.
        if (bmp.isRecycled) return

        canvas.save()
        try {
            radarClip.reset()
            radarClip.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(radarClip)

            // The bitmap covers the bbox: a square of side 2 * range,
            // centred on the car. The scope's radius IS one range, so the
            // square's half-side maps exactly to the radius.
            val heading = s.headingDegrees
            if (heading != null) {
                // Negative: turning the car right must swing the world left.
                canvas.rotate(-heading.toFloat(), cx, cy)
            }

            radarDst.set(cx - radius, cy - radius, cx + radius, cy + radius)
            try {
                canvas.drawBitmap(bmp, null, radarDst, radarPaint)
            } catch (e: RuntimeException) {
                // RuntimeException, not IllegalArgumentException: the crash
                // this guards against is thrown by BaseCanvas.throwIfCannotDraw
                // as a BARE java.lang.RuntimeException ("trying to use a
                // recycled bitmap"), which an IAE clause would not catch.
                //
                // Cannot happen now that nothing recycles the bitmap, and is
                // survivable if it ever does again: a dropped radar frame is
                // the correct response, not a dead process. The panel keeps
                // painting and the next fetch replaces the picture.
                //
                // Matches how the surface teardown race is handled in
                // render() -- the instrument's job is to keep showing the
                // fuel budget even when a subsystem misbehaves.
                android.util.Log.w("SwordfishRadar", "radar draw skipped: ${e.message}")
            }
        } finally {
            canvas.restore()
        }
    }

    /**
     * What to say when the scope has no usable picture, or null when it has.
     *
     * Three distinct states, because they need three different responses from
     * the driver: wait, grant a permission, or check signal. Collapsing them
     * into one message would make the useful ones unactionable.
     */
    private fun radarStatus(s: PanelState): String? {
        if (!s.hasLocationFix) return "NO GPS"
        if (radarBitmap == null) {
            return if (radarFetchFailed) "RADAR OFFLINE" else "RADAR..."
        }
        return null
    }

    /**
     * Cardinal letters around the rim, rotated for a heading-up scope.
     *
     * Each letter sits at its true bearing relative to the car, so N drifts
     * round the rim as the car turns. That IS the orientation cue: the
     * driver reads where north is rather than where they are pointing, which
     * the scope already says by pointing up.
     *
     * Falls back to a fixed rose with no letters when the heading is unknown
     * -- drawing N at the top without a heading would be a lie, and the
     * bearing comes from GPS doppler which needs the car to be moving.
     */
    private fun drawScopeRose(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, s: PanelState
    ) {
        val heading = s.headingDegrees ?: return

        ballMarkPaint.textAlign = Paint.Align.CENTER
        ballMarkPaint.textSize = radius * 0.16f

        val letters = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
        for ((letter, bearing) in letters) {
            val relative = Math.toRadians(bearing - heading)
            val lx = cx + (radius * 0.86f) * Math.sin(relative).toFloat()
            // The text baseline sits below the glyph, so a third of the text
            // size is added to centre the letter on its point rather than
            // hanging it beneath.
            val ly = cy - (radius * 0.86f) * Math.cos(relative).toFloat() +
                ballMarkPaint.textSize * 0.34f
            canvas.drawText(letter, lx, ly, ballMarkPaint)
        }
    }

    /**
     * The outer ring's range, top-left of the scope.
     *
     * Only the outermost ring is labelled. Labelling every ring turns the
     * scope into a chart to be read rather than a picture to be glanced at,
     * and the inner rings are evenly spaced fractions of a number already
     * on screen.
     */
    private fun drawScopeRangeLabel(
        canvas: Canvas, cx: Float, cy: Float, radius: Float
    ) {
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = radius * 0.155f
        // TEXT ONLY. The anchor, alignment and size are untouched -- this is
        // left-aligned at the scope's top-left, so a longer string grows
        // rightward into empty space and moves nothing. The panel layout was
        // dialled in by hand and must not shift for a unit label.
        canvas.drawText(
            DisplayUnits.radarRange(radarRangeMiles, unitSystem),
            cx - radius, cy - radius + labelPaint.textSize,
            labelPaint
        )
    }

    /**
     * The car, at the centre, pointing up.
     *
     * A chevron rather than a dot: the scope is heading-up, and a shape with
     * a nose says so at a glance where a symmetrical dot would not.
     */
    private fun drawShipMark(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val size = radius * 0.10f
        val path = Path()
        path.moveTo(cx, cy - size)
        path.lineTo(cx + size * 0.72f, cy + size * 0.8f)
        path.lineTo(cx, cy + size * 0.34f)
        path.lineTo(cx - size * 0.72f, cy + size * 0.8f)
        path.close()
        canvas.drawPath(path, scopeShipPaint)
    }

    /**
     * Delta-V and Isp, demoted to a single row beneath the scope.
     *
     * **Demoted, never dropped.** Delta-V is what the app is for, and a
     * driver who switched to radar has not stopped caring about the fuel
     * budget -- they have stopped wanting it to be the biggest thing on the
     * screen. Kept as segments so it still reads as live data, at roughly a
     * third of the headline size.
     */
    private fun drawRadarReadouts(
        canvas: Canvas, cxIn: Float, top: Float, width: Float, height: Float,
        narrow: Boolean, panelTop: Float, panelHeight: Float, scopeRadius: Float,
        stripPx: Float, s: PanelState
    ) {
        // Split view places the row from the scope's REAL extent, because
        // there the scope inherits the navball's centre and how far down it
        // reaches depends on the geometry. A fixed fraction that cleared the
        // rim at 468px buried itself in it at 390px.
        //
        // Full width centres the scope in its own band, so a fraction is
        // exact and needs no measurement.
        val rowTop0 = if (narrow) {
            panelTop + RadarLayout.readoutTopNarrowPx(panelHeight, scopeRadius)
        } else {
            top + RadarLayout.readoutTopWidePx(height, scopeRadius, 0f)
        }
        // Per-element nudge for the demoted readout row.
        // This function is handed `narrow` rather than the layout, so the
        // breakpoint choice is made here directly. Same rule as tunedFor:
        // WIDE and NARROW never share a tuned value.
        val roMode = if (narrow) PanelLayout.Mode.NARROW else PanelLayout.Mode.WIDE
        val roScale = PanelLayout.tunedFor(
            roMode, "READOUTS_SCALE",
            PanelLayout.READOUTS_SCALE, PanelLayout.READOUTS_SCALE_N
        )
        val segH = height * (if (narrow) 0.090f else 0.105f) * roScale
        val rowTop = rowTop0 + height * PanelLayout.tunedFor(
            roMode, "READOUTS_DY", PanelLayout.READOUTS_DY, PanelLayout.READOUTS_DY_N
        )
        val cx = cxIn + width * PanelLayout.tunedFor(
            roMode, "READOUTS_DX", PanelLayout.READOUTS_DX, PanelLayout.READOUTS_DX_N
        )

        // THREE readouts: delta-V, Isp, speed.
        //
        // Speed came back when the stats row moved to the top of the column.
        // Dropping it was a casualty of the two-cell row, not a decision --
        // the instrument panel shows it, and m/s is the same unit as
        // delta-V, which is what makes the relationship legible (at cruise
        // the budget is roughly 260x the current speed).
        //
        // Cells are sized to their CONTENT, not equal thirds. `29 m/s`
        // needs 77px while `31564 s` needs 133, so equal thirds forces the
        // whole row down to 0.58 of natural size where proportional packing
        // holds 0.72 -- the same row, noticeably more readable.

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = height * 0.042f

        val dv = s.deltaVMps
        val dvText = if (dv == null || !dv.isFinite()) {
            GaugeFormat.NO_DATA
        } else GaugeFormat.formatDeltaV(dv)

        // SHRINK THE WHOLE ROW TO FIT ITS WIDEST CELL.
        //
        // Three readouts in a split-view column get ~84px each, while
        // `31564 s` needs 133 and `7129 m/s` needs 126 -- measured, not
        // estimated. That overflow is what printed the three through each
        // other on the DHU.
        //
        // Scaled UNIFORMLY rather than per cell: three readouts at three
        // different sizes would read as three different kinds of number.
        // Same rule as PanelLayout.statRowTextScale, which exists because
        // the stat cells hit this first.
        val ispPreview = ispCellText(s)
        val spdPreview = speedCellText(s)

        val dvW = measureReadoutCell(dvText, GaugeFormat.UNITS, segH, height)
        val ispW = measureReadoutCell(ispPreview.first, ispPreview.second, segH, height)
        val spdW = measureReadoutCell(spdPreview, "m/s", segH, height)

        val scale = RadarLayout.readoutRowScale(dvW + ispW + spdW, width)
        val segHFit = segH * scale

        // Lay the three out left to right, each centred in its own scaled
        // share, with the leftovers split evenly as gutters.
        val dvFit = dvW * scale
        val ispFit = ispW * scale
        val spdFit = spdW * scale
        val gutter = (width - (dvFit + ispFit + spdFit)) / 4f
        val rowLeft = cx - width / 2f
        val dvCx = rowLeft + gutter + dvFit / 2f
        val ispCx = rowLeft + gutter * 2f + dvFit + ispFit / 2f
        val spdCx = rowLeft + gutter * 3f + dvFit + ispFit + spdFit / 2f

        canvas.drawText(GaugeFormat.LABEL, dvCx, rowTop - height * 0.016f, labelPaint)
        drawSegmentsWithUnit(
            canvas, dvText, GaugeFormat.UNITS, dvCx, rowTop, segHFit, width, height,
            unitScale = scale
        )

        // The trip's STARTING budget, under the live figure -- the same
        // reference the instrument panel carries. 3075 alone says nothing;
        // 3075 under START 7129 says the trip cost 4,054 m/s with no
        // arithmetic. Dropping it in radar mode would make the demoted
        // delta-V strictly less useful than the one it replaced.
        //
        // Plain type, not segments: it is a fixed reference, and segments
        // read as live data.
        val start = s.tripStartDeltaV
        if (start != null && start.isFinite() && start > 0.0) {
            labelPaint.textSize =
                height * RadarLayout.RADAR_START_TEXT_FRACTION.toFloat()
            canvas.drawText(
                "START ${GaugeFormat.formatDeltaV(start)}",
                dvCx, RadarLayout.radarStartBaselinePx(rowTop, segH, height), labelPaint
            )
            labelPaint.textSize = height * 0.042f
        }

        // Isp keeps the IDLE BURN substitution from the instrument panel:
        // at idle, thrust is doing nothing and flow is the honest number.
        val idling = s.operatingState == OperatingState.IDLE
        val ispLabel = if (idling && s.fuelFlowKgPerSec.let { it != null && it > 0.0 }) {
            "IDLE BURN"
        } else if (s.operatingState.hasMeaningfulIsp) "Isp"
        else s.operatingState.label

        val labelColour = labelPaint.color
        if (idling) labelPaint.color = C_AMBER
        canvas.drawText(ispLabel, ispCx, rowTop - height * 0.016f, labelPaint)
        labelPaint.color = labelColour
        drawSegmentsWithUnit(
            canvas, ispPreview.first, ispPreview.second, ispCx, rowTop, segHFit,
            width, height, unitScale = scale
        )

        // --- speed ---
        canvas.drawText("SPEED", spdCx, rowTop - height * 0.016f, labelPaint)
        drawSegmentsWithUnit(
            canvas, spdPreview, "m/s", spdCx, rowTop, segHFit, width, height,
            unitScale = scale
        )
    }

    /**
     * The Isp cell's value and unit, as [drawRadarReadouts] will draw them.
     *
     * Split out so the row can be MEASURED before any of it is drawn -- the
     * scale has to be known before the first cell is painted, or the three
     * end up at different sizes.
     */
    private fun ispCellText(s: PanelState): Pair<String, String> {
        val idling = s.operatingState == OperatingState.IDLE
        val flow = s.fuelFlowKgPerSec
        if (idling && flow != null && flow > 0.0) {
            val burn = GaugeFormat.formatFuelFlow(flow)
            return burn.value to burn.unit
        }
        val isp = s.effectiveIsp
        val text = if (isp != null && isp > 0.0) {
            GaugeFormat.formatInteger(Math.round(isp))
        } else GaugeFormat.NO_DATA
        return text to "s"
    }

    /** The speed cell's value, for the same measure-before-draw reason. */
    private fun speedCellText(s: PanelState): String {
        val speed = s.speedMps
        return if (speed != null && speed.isFinite()) {
            GaugeFormat.formatInteger(Math.round(speed))
        } else GaugeFormat.NO_DATA
    }

    /**
     * Width one readout cell would occupy: digits, gap and unit together.
     *
     * Mirrors what [drawSegmentsWithUnit] lays out, so the measurement and
     * the drawing cannot drift apart.
     */
    private fun measureReadoutCell(
        value: String, unit: String, segHeight: Float, panelHeight: Float
    ): Float {
        unitPaint.textSize = panelHeight * 0.040f
        return SegmentDisplay.measure(value, segHeight) +
            segHeight * 0.22f +
            unitPaint.measureText(unit)
    }

    /**
     * Draw a centred segment value with a small unit suffix beside it.
     *
     * The unit rides to the right of the digits and is centred as a group, so
     * the numerals stay optically centred rather than being pushed left by the
     * suffix.
     */
    /**
     * @param unitScale shrinks the unit suffix along with the digits. The
     *   radar readout row scales as a whole, and a unit left at full size
     *   beside shrunken digits is what makes a scaled row look broken
     *   rather than smaller. Defaults to 1.0, so every existing caller is
     *   unaffected.
     */
    private fun drawSegmentsWithUnit(
        canvas: Canvas,
        value: String,
        unit: String,
        centreX: Float,
        top: Float,
        segHeight: Float,
        columnWidth: Float,
        panelHeight: Float,
        unitScale: Float = 1f
    ) {
        unitPaint.textSize = panelHeight * 0.040f * unitScale
        unitPaint.textAlign = Paint.Align.LEFT
        val digitsW = SegmentDisplay.measure(value, segHeight)
        val gap = segHeight * 0.22f
        val unitW = unitPaint.measureText(unit)
        val totalW = digitsW + gap + unitW
        val startX = centreX - totalW / 2f

        SegmentDisplay.draw(
            canvas, value, startX, top, segHeight, segLitPaint, ghostPaintOrNull()
        )
        canvas.drawText(unit, startX + digitsW + gap, top + segHeight * 0.82f, unitPaint)
        unitPaint.textAlign = Paint.Align.CENTER
    }

    /** Vertical stat list for the wide layout. */
    private fun drawStats(
        canvas: Canvas, left: Float, width: Float, top: Float, height: Float,
        s: PanelState
    ) {
        val rows = statRows(s)
        if (rows.isEmpty()) return

        val rowH = height / rows.size.coerceAtLeast(1)
        statPaint.textSize = min(rowH * 0.42f, height * 0.085f)
        statValuePaint.textSize = min(rowH * 0.52f, height * 0.105f)

        // LABELS AT MID, NOT DIM.
        //
        // C_PHOSPHOR_DIM measures 2.97:1 against the panel ground -- below
        // even the WCAG large-text floor of 3:1 -- while the VALUE beside it
        // sits at 16:1. On a desk that reads as a pleasant hierarchy; on a
        // real drive the labels were reported as "practically invisible",
        // and a car in daylight needs more contrast than a desk, not less.
        //
        // C_PHOSPHOR_MID is 8.11:1, a 2.7x improvement, and still visibly
        // subordinate to the value it names -- which is all the dimming was
        // ever meant to achieve.
        statPaint.color = statLabelColour()
        statPaint.textAlign = Paint.Align.LEFT
        statValuePaint.textAlign = Paint.Align.RIGHT

        // Fixed columns. An earlier version right-aligned each value
        // independently, so a row with a unit suffix ("9.9 gal") sat at a
        // different x from one without ("6"), and the labels no longer read as
        // a column. Reserving a unit column fixes every row to the same grid.
        val unitColX = left + width * 0.98f
        val numberRightX = left + width * 0.72f

        rows.forEachIndexed { i, (label, value) ->
            // 0.5 + a third of the cap height, not 0.6.
            //
            // drawText takes a BASELINE, so centring a row means placing the
            // baseline below the row's middle by about a third of the glyph
            // height -- not by a flat 10% of the row, which is what 0.6 was.
            // At 7 rows that pushed the block visibly low in its band, which
            // is what "further down than they should be" was seeing.
            val y = top + rowH * (i + 0.5f) + statValuePaint.textSize * 0.34f
            statPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, left, y, statPaint)
            drawStatValue(canvas, value, numberRightX, unitColX, y, statValuePaint.textSize)
        }
    }

    /** Horizontal stat strip for the narrow layout. */
    private fun drawStatRow(
        canvas: Canvas, left: Float, width: Float, y: Float, textSize: Float,
        s: PanelState
    ) {
        // TWR is dropped in split view. It is the least glanceable of the
        // four -- a ratio that only means something next to Isp, which has
        // its own readout in the centre column -- and at half width the row
        // could not fit four cells without them running into each other.
        // It stays in the WIDE stats column, which has room.
        val rows = statRows(s).filterNot { it.first == "TWR" }
            .take(PanelLayout.NARROW_STAT_CELLS)
        if (rows.isEmpty()) return

        statPaint.textSize = textSize
        statPaint.textAlign = Paint.Align.CENTER
        val cellW = width / rows.size

        // Shrink to fit rather than overlap.
        //
        // Each cell centres its own content and nothing enforced a gutter, so
        // once cellW fell below what the widest cell needed, the cells simply
        // drew over each other -- photographed on the DHU as
        // "GEAR 6RPM 2669TWR 85FUEL 37L". Measuring the real content and
        // scaling the whole row down is the fix that cannot regress: it holds
        // for any cell count, any column width and any value the car reports.
        val widest = rows.maxOf { (label, value) ->
            val parts = value.trim().split(' ', limit = 2)
            val number = parts[0]
            val unit = parts.getOrNull(1) ?: ""
            statPaint.measureText(label) + textSize * 0.4f +
                SegmentDisplay.measure(number, textSize * 0.95f) +
                (if (unit.isEmpty()) 0f else statPaint.measureText(unit) + textSize * 0.2f)
        }
        statPaint.textSize = textSize * PanelLayout.statRowTextScale(widest, cellW)
        val ts = statPaint.textSize

        rows.forEachIndexed { i, (label, value) ->
            val cx = left + cellW * (i + 0.5f)
            // Label in type, value in segments -- the split the whole panel
            // follows, because seven segments cannot render arbitrary words.
            val parts = value.trim().split(' ', limit = 2)
            val number = parts[0]
            val unit = parts.getOrNull(1) ?: ""

            val labelW = statPaint.measureText(label)
            val segH = ts * 0.95f
            val numberW = SegmentDisplay.measure(number, segH)
            val unitW = if (unit.isEmpty()) 0f else statPaint.measureText(unit) + ts * 0.2f
            val total = labelW + ts * 0.4f + numberW + unitW
            val startX = cx - total / 2f

            statPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, startX, y, statPaint)
            val numX = startX + labelW + ts * 0.4f
            SegmentDisplay.draw(
                canvas, number, numX, y - segH * 0.78f, segH,
                segLitPaint, ghostPaintOrNull()
            )
            if (unit.isNotEmpty()) {
                canvas.drawText(unit, numX + numberW + ts * 0.2f, y, statPaint)
            }
        }
        statPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Draw one stat value on a fixed grid: number in segments, unit in type.
     *
     * The number is right-aligned to [numberRightX] and the unit is
     * left-aligned at [unitX], so every row lines up regardless of how many
     * digits it has or whether it carries a unit at all.
     *
     * Decimal points are part of the segment string now, so "0.15" and
     * "9.9 gal" both render entirely as segments plus a plain-type suffix —
     * no more mixed "0" in segments and ".15" in text.
     */
    private fun drawStatValue(
        canvas: Canvas,
        value: String,
        numberRightX: Float,
        unitX: Float,
        baselineY: Float,
        textSize: Float
    ) {
        val parts = value.trim().split(' ', limit = 2)
        val number = parts[0]
        val unit = parts.getOrNull(1) ?: ""

        val segH = textSize * 0.95f
        val w = SegmentDisplay.measure(number, segH)
        SegmentDisplay.draw(
            canvas, number, numberRightX - w, baselineY - segH * 0.78f, segH,
            segLitPaint, ghostPaintOrNull()
        )

        if (unit.isNotEmpty()) {
            statPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(unit, unitX - statPaint.measureText(unit), baselineY, statPaint)
        }
    }

    /** The secondary readouts, in priority order. */
    private fun statRows(s: PanelState): List<Pair<String, String>> = buildList {
        s.gear?.let { add("GEAR" to it.toString()) }
        s.rpm?.let { add("RPM" to GaugeFormat.formatInteger(Math.round(it))) }
        // TWR as a percentage rather than a fraction. "0.15" needs a decimal
        // point that is only ~4px across at stat-row size and cannot be read;
        // "15%" needs none and says the same thing. Percentages are also the
        // more natural way to hear it -- 15% of the thrust needed to hover.
        s.twr?.let { add("TWR" to "${"%.0f".format(it * 100)}%") }
        // Fuel in LITRES, not gallons.
        //
        // Gallons need a tenth to be useful ("9.9"), and the decimal point is
        // only ~4px across at stat-row size -- unreadable. Litres are whole
        // numbers across the entire tank range (0-45 L) at the same
        // resolution, so the decimal simply is not needed.
        //
        // It also matches the rest of the panel, which is already SI: speed
        // and delta-V are both in m/s.
        s.fuelGallons?.let {
            add("FUEL" to "${"%.0f".format(Units.gallonsToLiters(it))} L")
        }
        // Mass in kg, for the same reason fuel is in litres: the panel is SI
        // throughout, and kg needs no more digits than lb.
        s.totalMassKg?.let {
            add("MASS" to "${GaugeFormat.formatInteger(Math.round(it))} kg")
        }
        s.thermalEfficiency?.let { add("EFF" to "${"%.0f".format(it * 100)}%") }
        // Altitude, in whole metres.
        //
        // A KSP panel without an altimeter is a strange omission, and this
        // one is tied directly to physics already modelled: gravity losses
        // ARE the power going into altitude, so this shows where that fuel
        // went.
        //
        // Whole metres because the panel draws no decimal points, and
        // because a tenth of a metre is well inside the absolute error of
        // any barometric altitude anyway. Placed AFTER the first four rows
        // so it is the narrow layout that drops it, not RPM.
        s.altitudeM?.let {
            add("ALT" to "${GaugeFormat.formatInteger(Math.round(it))} m")
        }
        // Electrical. The ND has no voltmeter, and a factory one would be
        // useless anyway: it reads 13.5-14.5 V for the whole drive. What is
        // worth showing is the NUMBER the dashboard refuses to give, plus
        // the state -- which is normally, honestly, "nothing is happening".
        //
        // One decimal because the interesting range is narrow: 12.2 V and
        // 12.7 V are half a tank of charge apart.
        s.busVolts?.let { add("VOLTS" to "%.1f".format(it)) }
        // Coolant is the same story: the stock needle sits dead centre
        // across the entire 88-100 C a warm engine occupies.
        s.coolantC?.let {
            add("TEMP" to "${GaugeFormat.formatInteger(Math.round(it))} C")
        }
    }

    /**
     * Artificial horizon driven by real attitude.
     *
     * Pitch tips the horizon; roll rotates it. Both come from the phone IMU
     * via `Attitude`, with the caveat documented there that roll conflates
     * chassis lean with cornering force.
     */
    private fun drawNavball(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, s: PanelState
    ) {
        if (radius <= 4f) return

        // An uncalibrated navball is meaningless, not merely imprecise. Show
        // the calibration state instead of a confidently wrong horizon.
        if (s.mountState == MountAutoCalibrator.State.UNCALIBRATED || s.attitude == null) {
            ballLinePaint.color = C_PHOSPHOR_DIM
            ballLinePaint.strokeWidth = radius * 0.04f
            canvas.drawCircle(cx, cy, radius, ballLinePaint)
            statPaint.color = C_PHOSPHOR
            statPaint.textAlign = Paint.Align.CENTER
            statPaint.textSize = radius * 0.30f
            canvas.drawText("LEVELLING", cx, cy + radius * 0.10f, statPaint)
            return
        }

        val att = s.attitude
        // Prefer measured ROAD GRADE over accelerometer pitch.
        //
        // Accelerometer pitch describes the phone's attitude, which is
        // meaningful in a cradle and meaningless on a passenger seat. Air
        // pressure over distance describes the road itself and does not care
        // how the phone is lying -- so the horizon stays true even while the
        // phone slides around.
        val pitch = s.gradeRadians ?: att?.pitchRadians ?: 0.0
        val roll = att?.rollRadians ?: 0.0

        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.rotate(Math.toDegrees(-roll).toFloat(), cx, cy)

        val horizonY = cy + (NavballScale.horizonOffsetFraction(pitch) * radius).toFloat()

        canvas.drawRect(
            cx - radius * 2, cy - radius * 2, cx + radius * 2, horizonY, ballSkyPaint
        )
        canvas.drawRect(
            cx - radius * 2, horizonY, cx + radius * 2, cy + radius * 2, ballGroundPaint
        )

        // Horizon line, drawn heavier than the ladder.
        ballLinePaint.color = C_BALL_MARK
        ballLinePaint.strokeWidth = radius * 0.035f
        canvas.drawLine(cx - radius, horizonY, cx + radius, horizonY, ballLinePaint)

        // Pitch ladder. Positions and visibility come from NavballScale, so
        // the renderer never has to reason about which ticks fall off the ball.
        //
        // Only MAJOR graduations are labelled, and only on one side. The
        // earlier version labelled every tick on both flanks, which at this
        // ball size produced a wall of tiny digits harder to read than no
        // labels at all.
        ballMarkPaint.textAlign = Paint.Align.CENTER
        ballMarkPaint.textSize = radius * 0.26f
        ballLinePaint.color = C_BALL_MARK
        ballLinePaint.strokeWidth = radius * 0.028f

        for (tick in NavballScale.pitchTicks(pitch)) {
            if (tick.degrees == 0.0) continue  // the horizon is drawn above
            val y = cy + (tick.offsetFraction * radius).toFloat()
            val halfW = (tick.widthFraction * radius).toFloat()
            canvas.drawLine(cx - halfW, y, cx + halfW, y, ballLinePaint)
            if (tick.isMajor) {
                tick.label?.let { label ->
                    canvas.drawText(
                        label, cx - halfW - radius * 0.20f, y + radius * 0.09f, ballMarkPaint
                    )
                }
            }
        }

        canvas.restore()

        // --- fixed chrome, outside the rotating ball ---

        // Roll scale around the top of the rim. Uses the high-contrast text
        // colour rather than the dim colour: these are read at a glance.
        ballLinePaint.color = C_PHOSPHOR
        for (deg in NavballScale.ROLL_TICKS_DEG) {
            val isMajor = deg in NavballScale.ROLL_MAJOR_DEG
            // -90 puts zero at the top of the circle.
            val a = Math.toRadians(deg - 90.0)
            val inner = radius * (if (isMajor) 0.80f else 0.87f)
            val outer = radius * 0.97f
            ballLinePaint.strokeWidth = radius * (if (isMajor) 0.060f else 0.035f)
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(),
                ballLinePaint
            )
        }

        // Roll pointer: a triangle that swings with the horizon.
        val pointerAngle = Math.toRadians(Math.toDegrees(roll) - 90.0)
        val pr = radius * 0.74f
        val px = cx + (cos(pointerAngle) * pr).toFloat()
        val py = cy + (sin(pointerAngle) * pr).toFloat()
        pointerPaint.color = C_ROLL_POINTER
        canvas.drawCircle(px, py, radius * 0.085f, pointerPaint)

        // Fixed aircraft-style reference marker.
        ballLinePaint.color = C_AMBER
        ballLinePaint.strokeWidth = radius * 0.05f
        canvas.drawLine(cx - radius * 0.45f, cy, cx - radius * 0.15f, cy, ballLinePaint)
        canvas.drawLine(cx + radius * 0.15f, cy, cx + radius * 0.45f, cy, ballLinePaint)
        canvas.drawCircle(cx, cy, radius * 0.05f, ballLinePaint)

        // Rim.
        ballLinePaint.color = C_PHOSPHOR
        ballLinePaint.strokeWidth = radius * 0.055f
        canvas.drawCircle(cx, cy, radius, ballLinePaint)

        // Compass strip beneath the ball. Decorative -- no physics depends on
        // it -- but it is a real magnetometer bearing, not a GPS track, so it
        // still reads correctly when stationary.
        drawCompassStrip(canvas, cx, cy + radius * 1.26f, radius * 2.3f, radius * 0.22f, s)

        // Numeric attitude, for when the ball alone is ambiguous.
        statPaint.color = C_PHOSPHOR
        statPaint.textAlign = Paint.Align.CENTER
        statPaint.textSize = radius * 0.26f
        if (att != null) {
            val pitchDeg = Math.toDegrees(att.pitchRadians)
            val rollDeg = Math.toDegrees(att.rollRadians)
            canvas.drawText(
                "P %.0f°  R %.0f°".format(pitchDeg, rollDeg),
                cx, cy - radius * 1.22f, statPaint
            )
        }

        // Until a straight-line pull reveals which way is forward, roll and
        // pitch cannot be told apart from each other. Say so rather than
        // implying more precision than we have.
        // ABOVE the ball, beside the attitude readout.
        //
        // It lived below the compass strip, which grew to three rows and
        // pushed this off the bottom of the surface entirely. There is no
        // room left underneath, and there is plenty above.
        if (s.mountState == MountAutoCalibrator.State.LEVELLED) {
            statPaint.color = C_ROLL_POINTER
            statPaint.textAlign = Paint.Align.CENTER

            // Sized and placed off the ATTITUDE READOUT it sits above, not
            // off the ball. At radius*0.20 above a radius*0.26 line only
            // 0.22*radius apart, the two overlapped -- and on the real head
            // unit the baseline computed to 1.2px, clipped by the top edge.
            //
            // Smaller than the attitude line (it is a prompt, not a
            // reading) and separated by a full line height of its own.
            val attSize = radius * 0.26f
            val promptSize = radius * 0.17f
            val attBaseline = cy - radius * 1.22f
            canvas.drawText(
                "DRIVE TO ORIENT",
                cx,
                attBaseline - attSize * 0.85f - promptSize * 0.35f,
                statPaint.apply { textSize = promptSize }
            )
        }
    }

    /**
     * A short arc of compass headings, HSI-tape style.
     *
     * Drawn as **three rows**, top to bottom:
     *
     *   1. graduation ticks — the fine scale
     *   2. bearing in degrees — the precise value
     *   3. cardinal letters — the fast read
     *
     * Generating ticks and labels independently is what allows a fine scale
     * without the collisions a single-row version produced. Each row gets its
     * own band so nothing can overlap, and the rows are ordered by how long
     * they take to read: a tick is instant, a letter is fast, a number is
     * slowest but exact.
     */
    private fun drawCompassStrip(
        canvas: Canvas, cx: Float, y: Float, width: Float, height: Float,
        s: PanelState
    ) {
        val marks = NavballScale.compassTickMarks(s.headingDegrees)
        val labels = NavballScale.compassTicks(s.headingDegrees)
        val heading = s.headingDegrees
        if (marks.isEmpty() && labels.isEmpty() && heading == null) return

        val left = cx - width / 2f

        // Row 1: graduations.
        val tickBottom = y
        for ((pos, isMajor) in marks) {
            if (pos < 0.04 || pos > 0.96) continue
            val x = left + (width * pos).toFloat()
            val len = height * (if (isMajor) 0.62f else 0.34f)
            ballLinePaint.color = if (isMajor) C_PHOSPHOR else C_PHOSPHOR_DIM
            ballLinePaint.strokeWidth = height * (if (isMajor) 0.15f else 0.10f)
            canvas.drawLine(x, tickBottom - len, x, tickBottom, ballLinePaint)
        }

        // Row 2: the bearing in degrees, as segments.
        if (heading != null && heading.isFinite()) {
            val degH = height * 0.80f
            val degTop = tickBottom + height * 0.22f
            val degText = GaugeFormat.formatInteger(Math.round(heading) % 360)
            val degW = SegmentDisplay.measure(degText, degH)
            SegmentDisplay.draw(
                canvas, degText, cx - degW / 2f, degTop, degH,
                segLitPaint, ghostPaintOrNull()
            )
            statPaint.color = C_PHOSPHOR_MID
            statPaint.textAlign = Paint.Align.LEFT
            statPaint.textSize = height * 0.55f
            canvas.drawText("\u00B0", cx + degW / 2f + height * 0.12f,
                degTop + degH * 0.55f, statPaint)
        }

        // Row 3: cardinal letters, nudged clear of the degrees above.
        //
        // ALL of these stay in plain type, deliberately. Only E and S have
        // honest seven-segment forms; uppercase N has no clean one, and W
        // needs more than seven segments -- no amount of cleverness renders
        // it, which is why segment clocks spell nothing interesting.
        //
        // Rendering some labels as segments and others as type would read as a
        // rendering fault rather than as a style. One row, one treatment.
        val letterY = tickBottom + height * 2.05f
        statPaint.textAlign = Paint.Align.CENTER
        statPaint.textSize = height * 0.82f
        for ((label, pos) in labels) {
            if (pos < 0.10 || pos > 0.90) continue
            val isCurrent = kotlin.math.abs(pos - 0.5) < 0.06
            statPaint.color = if (isCurrent) C_ROLL_POINTER else C_PHOSPHOR_MID
            canvas.drawText(label, left + (width * pos).toFloat(), letterY, statPaint)
        }

        // Centre index, spanning the tick row only.
        ballLinePaint.color = C_ROLL_POINTER
        ballLinePaint.strokeWidth = height * 0.17f
        canvas.drawLine(cx, tickBottom - height * 0.95f, cx, tickBottom, ballLinePaint)
    }

    companion object {
        /**
         * Phosphor-green instrument palette.
         *
         * ## Why there is no light mode
         *
         * Android Auto follows the car's day/night setting, and earlier builds
         * honoured it — which produced a white panel in daylight that read like
         * a web page rather than an instrument.
         *
         * Real gauge clusters do not invert during the day; they get brighter.
         * So this palette is dark **always**, and daylight is handled by
         * [dayBoost] lifting the phosphor intensity rather than by swapping to
         * a light theme. That is a deliberate departure from the platform
         * convention, made because the alternative undermines the whole point
         * of the display.
         *
         * The greens are picked to sit apart from each other at a glance:
         * bright for live values, mid for labels, dim for chrome.
         */

        /** True black, as the reference sleeve is. */
        val C_GROUND = Color.rgb(0x03, 0x05, 0x04)

        /** Slightly lifted ground, for panel wells and bar troughs. */
        val C_WELL = Color.rgb(0x0A, 0x16, 0x0E)

        /** Live numerals. The brightest thing on the panel. */
        val C_PHOSPHOR = Color.rgb(0x6C, 0xFF, 0xA8)

        /** Labels and units. Clearly subordinate to the values. */
        val C_PHOSPHOR_MID = Color.rgb(0x3F, 0xB8, 0x76)

        /** Chrome, rules and inactive marks. */
        val C_PHOSPHOR_DIM = Color.rgb(0x22, 0x66, 0x42)

        /** Amber, for the things the eye should find first. */
        val C_AMBER = Color.rgb(0xFF, 0xB3, 0x2E)

        /** Navball sky: deep enough that green markings read on top. */
        val C_BALL_SKY = Color.rgb(0x08, 0x1E, 0x2C)

        /** Navball ground. */
        val C_BALL_GROUND = Color.rgb(0x1A, 0x14, 0x08)

        /** Markings on the ball. */
        val C_BALL_MARK = C_PHOSPHOR

        /** Roll pointer and compass index. */
        val C_ROLL_POINTER = C_AMBER

        /**
         * Lift a phosphor colour for daylight legibility.
         *
         * Instrument clusters brighten in sunlight rather than inverting. This
         * blends toward white, which raises luminance without losing the green
         * character the way a straight white would.
         */
        fun dayBoost(color: Int, amount: Float): Int {
            val a = amount.coerceIn(0f, 1f)
            fun lift(c: Int) = (c + (255 - c) * a).toInt().coerceIn(0, 255)
            return Color.rgb(
                lift(Color.red(color)),
                lift(Color.green(color)),
                lift(Color.blue(color))
            )
        }
    }
}