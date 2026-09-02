package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import dev.swordfish.physics.RadarLayout
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * DRAG-TO-TUNE panel layout.
 *
 *     ./gradlew :layout-harness:tune
 *
 * ## What this is for
 *
 * Describing a layout in words does not survive the trip. "Move the speed
 * block down a bit" is not a number, and finding out whether "a bit" was
 * right used to run through a Play upload and a drive. This window closes
 * that loop: drag the thing, watch the REAL renderer redraw at the real
 * head-unit size, then press Copy Kotlin and hand over an exact diff.
 *
 * ## Why it drives the real renderer
 *
 * Same reason as the snapshot tool: GaugeRenderer.kt is compiled into this
 * module and drawn through the Java2D shim, so what you drag is what the
 * car draws. A mock would let you tune something that does not exist.
 *
 * ## Why dragging does not write to the source
 *
 * The values are `const val`s. Dragging holds an override in memory and
 * SHOWS you the Kotlin; it never edits the files. A preview tool that
 * silently rewrites source is a surprising thing to leave running, and the
 * paste is a one-second step.
 */

/** One draggable value: a layout constant expressed as a fraction of height. */
class Knob(
    val name: String,
    val owner: String,
    val default: Float,
    val help: String,
    /** Where this knob's handle sits, in px down the panel. */
    val handleY: (top: Float, height: Float, value: Float) -> Float,
    /** Inverse of [handleY]: what value puts the handle at y? */
    val valueAtY: (top: Float, height: Float, y: Float) -> Float
) {
    var value: Float = default
    val changed: Boolean get() = kotlin.math.abs(value - default) > 1e-5f
    fun reset() { value = default }

    fun kotlinLine(): String {
        val v = "%.4f".format(value).trimEnd('0').trimEnd('.')
        // WIDE_READOUT_BAND is a Double in the source; the rest are Float.
        val suffix = if (name == "WIDE_READOUT_BAND") "" else "f"
        return "    const val " + name + " = " + v + suffix
    }
}

/**
 * Overrides consulted by the layout while the tuner is open.
 *
 * PanelLayout's values are compile-time constants and cannot be poked at
 * runtime, so the tuner writes here and the layout accessors read here.
 * Empty in the app, so this costs the shipping build nothing.
 */
object LayoutOverride {
    val values: MutableMap<String, Float> = java.util.concurrent.ConcurrentHashMap()
    fun of(name: String, fallback: Float): Float = values[name] ?: fallback
    fun clear() = values.clear()
}

private const val PANEL_INSET = 8f

private fun buildKnobs(): List<Knob> = listOf(
    Knob(
        "DV_TOP_WIDE", "PanelLayout", PanelLayout.DV_TOP_WIDE,
        "Top of the delta-V segments",
        { top, h, v -> top + h * v },
        { top, h, y -> (y - top) / h }
    ),
    Knob(
        "UNITS_LINE_OFFSET", "PanelLayout", PanelLayout.UNITS_LINE_OFFSET,
        "Baseline of the m/s + START line",
        { top, h, v ->
            top + h * LayoutOverride.of("DV_TOP_WIDE", PanelLayout.DV_TOP_WIDE) +
                h * PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE) + h * v
        },
        { top, h, y ->
            val segBottom = top +
                h * LayoutOverride.of("DV_TOP_WIDE", PanelLayout.DV_TOP_WIDE) +
                h * PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE)
            (y - segBottom) / h
        }
    ),
    Knob(
        "SPEED_TOP", "PanelLayout", PanelLayout.SPEED_TOP,
        "Top of the SPEED segments",
        { top, h, v -> top + h * v },
        { top, h, y -> (y - top) / h }
    ),
    Knob(
        "SECTION_LABEL_LIFT", "PanelLayout", PanelLayout.SECTION_LABEL_LIFT,
        "How far a section label sits above its block",
        { top, h, v ->
            top + h * LayoutOverride.of("SPEED_TOP", PanelLayout.SPEED_TOP) - h * v
        },
        { top, h, y ->
            (top + h * LayoutOverride.of("SPEED_TOP", PanelLayout.SPEED_TOP) - y) / h
        }
    ),
    Knob(
        "WIDE_READOUT_BAND", "PanelLayout", PanelLayout.WIDE_READOUT_BAND.toFloat(),
        "RADAR: bottom band reserved (drives scope size)",
        { top, h, v -> top + h * (1f - v) },
        { top, h, y -> 1f - (y - top) / h }
    )
)

private class PanelView(
    private val allKnobs: List<Knob>,
    private val onChange: () -> Unit
) : JPanel() {

    var geometry: Geometry = Geometry.ND2
    var radar: Boolean = false
    var showHandles: Boolean = true
    var zoom: Int = 1

    private var image: BufferedImage? = null
    private var dragging: Knob? = null
    private var hover: Knob? = null

    val boxes: List<InstrumentBox> = buildBoxes()
    private var boxDrag: InstrumentBox? = null
    private var boxResize: Boolean = false
    private var boxHover: InstrumentBox? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var grabR = 0f

    private val panelTop get() = PANEL_INSET
    private val panelH get() = geometry.height - PANEL_INSET * 2f

    init {
        background = AwtColor(30, 30, 30)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val x = e.x.toFloat() / zoom
                val y = e.y.toFloat() / zoom

                // Boxes take precedence over lines: a corner handle sits on
                // top of the picture and should not be stolen by a line that
                // happens to pass behind it.
                val corner = boxCornerAt(x, y)
                if (corner != null) {
                    boxDrag = corner
                    boxResize = true
                    val c = corner.bounds(geometry, radar)
                    grabR = if (c == null) 1f else kotlin.math.hypot(
                        (x - c.cx) / c.halfW.coerceAtLeast(1f),
                        (y - c.cy) / c.halfH.coerceAtLeast(1f)
                    )
                    repaint(); return
                }
                val inside = boxAt(x, y)
                if (inside != null) {
                    boxDrag = inside
                    boxResize = false
                    val c = inside.bounds(geometry, radar)
                    if (c != null) { grabDx = x - c.cx; grabDy = y - c.cy }
                    repaint(); return
                }
                dragging = knobAt(y)
                repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                dragging = null
                boxDrag = null
                repaint()
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val x = e.x.toFloat() / zoom
                val y = e.y.toFloat() / zoom

                val b = boxDrag
                if (b != null) {
                    val base = b.base(geometry, radar)
                    if (base != null) {
                        if (boxResize) {
                            // Corner drag. Measured as distance from centre in
                            // units of the box's own half-extents, so a wide
                            // text block resizes as naturally as a circle.
                            val cur = b.bounds(geometry, radar)!!
                            val want = kotlin.math.hypot(
                                (x - cur.cx) / cur.halfW.coerceAtLeast(1f),
                                (y - cur.cy) / cur.halfH.coerceAtLeast(1f)
                            )
                            val ratio = if (grabR <= 0.01f) 1f else want / grabR
                            val n = geometry.isNarrow
                            b.setScale(
                                n, (b.curScale(n) * ratio).coerceIn(0.15f, 3.0f)
                            )
                            grabR = want
                        } else {
                            // Body drag: move the centre, in fractions so the
                            // value means the same on another surface size.
                            val n = geometry.isNarrow
                            b.setDx(n, ((x - grabDx) - base.cx) / geometry.width)
                            b.setDy(n, ((y - grabDy) - base.cy) / panelH)
                        }
                        b.push(geometry.isNarrow)
                        refresh()
                        onChange()
                    }
                    return
                }

                val k = dragging ?: return
                k.value = k.valueAtY(panelTop, panelH, y).coerceIn(0.002f, 0.98f)
                LayoutOverride.values[k.name] = k.value
                refresh()
                onChange()
            }
            override fun mouseMoved(e: MouseEvent) {
                val x = e.x.toFloat() / zoom
                val y = e.y.toFloat() / zoom
                val corner = boxCornerAt(x, y)
                val inside = if (corner == null) boxAt(x, y) else null
                val line = if (corner == null && inside == null) knobAt(y) else null

                if (corner !== boxHover || line !== hover ||
                    (inside != null && inside !== boxHover)
                ) {
                    boxHover = corner ?: inside
                    hover = line
                    cursor = Cursor.getPredefinedCursor(
                        when {
                            corner != null -> Cursor.SE_RESIZE_CURSOR
                            inside != null -> Cursor.MOVE_CURSOR
                            line != null -> Cursor.N_RESIZE_CURSOR
                            else -> Cursor.DEFAULT_CURSOR
                        }
                    )
                    repaint()
                }
            }
        })
    }

    /** Knobs that mean something in the mode currently shown. */
    private fun active(): List<Knob> = allKnobs.filter {
        if (radar) it.name == "WIDE_READOUT_BAND"
        else it.name != "WIDE_READOUT_BAND"
    }

    /** Boxes that exist in the mode currently shown. */
    private fun activeBoxes(): List<InstrumentBox> =
        boxes.filter { it.bounds(geometry, radar) != null }

    /**
     * The box containing (x, y).
     *
     * SMALLEST first: the delta-V block sits inside the centre column and
     * the readout row overlaps the scope's lower rim, so picking the
     * largest match would make the inner element unreachable.
     */
    private fun boxAt(x: Float, y: Float): InstrumentBox? =
        activeBoxes().filter {
            val b = it.bounds(geometry, radar)!!
            x >= b.cx - b.halfW && x <= b.cx + b.halfW &&
                y >= b.cy - b.halfH && y <= b.cy + b.halfH
        }.minByOrNull {
            val b = it.bounds(geometry, radar)!!
            b.halfW * b.halfH
        }

    /** The box with a corner grip near (x, y). */
    private fun boxCornerAt(x: Float, y: Float): InstrumentBox? =
        activeBoxes().firstOrNull {
            val b = it.bounds(geometry, radar)!!
            corners(b).any { p ->
                kotlin.math.hypot(x - p.first, y - p.second) <= CORNER_GRAB
            }
        }

    private fun corners(b: Bounds): List<Pair<Float, Float>> =
        listOf(
            (b.cx - b.halfW) to (b.cy - b.halfH),
            (b.cx + b.halfW) to (b.cy - b.halfH),
            (b.cx - b.halfW) to (b.cy + b.halfH),
            (b.cx + b.halfW) to (b.cy + b.halfH)
        )

    private fun knobAt(y: Float): Knob? {
        val near = active().minByOrNull {
            kotlin.math.abs(it.handleY(panelTop, panelH, it.value) - y)
        } ?: return null
        val d = kotlin.math.abs(near.handleY(panelTop, panelH, near.value) - y)
        return if (d <= 7f) near else null
    }

    fun refresh() {
        image = renderTuned(
            Case(
                "tuner", geometry, demoState(),
                centre = if (radar) RadarLayout.CentreContent.RADAR
                else RadarLayout.CentreContent.INSTRUMENTS
            )
        )
        preferredSize = Dimension(geometry.width * zoom, geometry.height * zoom)
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        val img = image ?: return
        g2.drawImage(img, 0, 0, img.width * zoom, img.height * zoom, null)
        if (!showHandles) return

        g2.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
        )
        g2.font = Font("SansSerif", Font.BOLD, 11)
        for (k in active()) {
            val y = (k.handleY(panelTop, panelH, k.value) * zoom).toInt()
            val hot = k === hover || k === dragging
            val col = when {
                hot -> AwtColor(255, 210, 80)
                k.changed -> AwtColor(120, 220, 255)
                else -> AwtColor(255, 90, 90, 200)
            }
            g2.color = col
            g2.drawLine(0, y, width, y)
            // Grab tab on the left, so it is obvious what to reach for.
            g2.fillRect(0, y - 4, 10, 9)

            val text = k.name + "  " + "%.4f".format(k.value)
            val w = g2.fontMetrics.stringWidth(text)
            g2.color = AwtColor(0, 0, 0, 200)
            g2.fillRect(14, y - 15, w + 8, 16)
            g2.color = col
            g2.drawString(text, 18, y - 3)
        }

        // --- instrument boxes, drawn last so their grips sit on top ---
        for (b in activeBoxes()) {
            val bb = b.bounds(geometry, radar)!!
            val hot = b === boxHover || b === boxDrag
            val col = when {
                hot -> AwtColor(255, 210, 80)
                b.changedIn(geometry.isNarrow) -> AwtColor(120, 220, 255)
                else -> AwtColor(90, 230, 160, 200)
            }
            val x0 = ((bb.cx - bb.halfW) * zoom).toInt()
            val y0 = ((bb.cy - bb.halfH) * zoom).toInt()
            val bw = (bb.halfW * 2f * zoom).toInt()
            val bh = (bb.halfH * 2f * zoom).toInt()

            g2.color = col
            g2.drawRect(x0, y0, bw, bh)

            // Corner grips.
            val gs = (CORNER_GRAB * zoom).toInt()
            for (p in listOf(
                x0 to y0, (x0 + bw) to y0,
                x0 to (y0 + bh), (x0 + bw) to (y0 + bh)
            )) {
                g2.fillRect(p.first - gs / 2, p.second - gs / 2, gs, gs)
            }

            val text = b.label + "  x" +
                "%.2f".format(b.curScale(geometry.isNarrow)) +
                (if (geometry.isNarrow) "  [collapsed]" else "")
            val tw = g2.fontMetrics.stringWidth(text)
            g2.color = AwtColor(0, 0, 0, 200)
            g2.fillRect(x0, y0 - 16, tw + 8, 16)
            g2.color = col
            g2.drawString(text, x0 + 4, y0 - 4)
        }
    }

    companion object {
        /** Corner grip radius, in panel px. */
        const val CORNER_GRAB = 9f
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val ks = buildKnobs()
        val frame = JFrame("Swordfish - layout tuner")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

        val out = JTextArea(9, 38)
        out.isEditable = false
        out.font = Font("Monospaced", Font.PLAIN, 12)

        lateinit var view: PanelView

        fun refreshKotlin() {
            val lines = ArrayList<String>()
            ks.filter { it.changed }.forEach { lines.add(it.kotlinLine()) }
            view.boxes.forEach { lines.addAll(it.kotlinLines()) }

            out.text = if (lines.isEmpty()) {
                "// nothing changed yet.\n" +
                    "// drag a red line, or drag/resize a green box."
            } else {
                // Everything tunable lives in PanelLayout today, so one
                // header is honest. If a knob ever moves to another file
                // this must group by owner again.
                "// PanelLayout.kt\n" + lines.joinToString("\n")
            }
        }

        view = PanelView(ks) { refreshKotlin() }

        val side = JPanel()
        side.layout = BoxLayout(side, BoxLayout.Y_AXIS)
        side.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        fun addRow(text: String, c: java.awt.Component) {
            val p = JPanel(BorderLayout(6, 0))
            p.alignmentX = 0f
            p.add(JLabel(text), BorderLayout.WEST)
            p.add(c, BorderLayout.CENTER)
            p.maximumSize = Dimension(Int.MAX_VALUE, 28)
            side.add(p)
            side.add(Box.createVerticalStrut(4))
        }

        val geomBox = JComboBox(Geometry.ALL.map { it.name }.toTypedArray())
        geomBox.addActionListener {
            view.geometry = Geometry.ALL[geomBox.selectedIndex]
            view.refresh()
        }
        val modeBox = JComboBox(arrayOf("instruments", "radar"))
        modeBox.addActionListener {
            view.radar = modeBox.selectedIndex == 1
            view.refresh()
        }
        val zoomBox = JComboBox(arrayOf("1x", "2x"))
        zoomBox.addActionListener {
            view.zoom = zoomBox.selectedIndex + 1
            view.refresh()
        }
        addRow("surface", geomBox)
        addRow("centre", modeBox)
        addRow("zoom", zoomBox)

        val handles = JCheckBox("show drag handles", true)
        handles.alignmentX = 0f
        handles.addActionListener {
            view.showHandles = handles.isSelected
            view.repaint()
        }
        side.add(handles)
        side.add(Box.createVerticalStrut(10))

        val how = JLabel(
            "<html><b>Red lines</b>: drag up/down.<br>" +
                "<b>Green boxes</b>: drag to move,<br>" +
                "drag a corner to resize.<br>" +
                "Blue = changed from source.<br><br>" +
                "<b>Save changes</b> writes straight<br>" +
                "into PanelLayout.kt, then run<br>" +
                "<tt>tools\\dbg.bat</tt> for the head unit.</html>"
        )
        how.alignmentX = 0f
        side.add(how)
        side.add(Box.createVerticalStrut(8))

        for (k in ks) {
            val l = JLabel(
                "<html><tt>" + k.name + "</tt><br><font color='#777'>" +
                    k.help + "</font></html>"
            )
            l.alignmentX = 0f
            side.add(l)
            side.add(Box.createVerticalStrut(4))
        }

        side.add(Box.createVerticalStrut(8))
        val sp = JScrollPane(out)
        sp.alignmentX = 0f
        side.add(sp)
        side.add(Box.createVerticalStrut(6))

        val save = JButton("Save changes")
        save.toolTipText =
            "Write these values into PanelLayout.kt (a .bak is kept)"
        save.addActionListener {
            // Everything tunable, not just what moved: saving the full set
            // means a value dragged back to its default is also written,
            // rather than silently keeping the old number.
            val values = HashMap<String, Float>()
            for (k in ks) values[k.name] = k.value
            for (b in view.boxes) values.putAll(b.allValues())

            val r = SaveValues.save(values)
            out.text = (if (r.ok) "// SAVED\n" else "// SAVE FAILED\n") +
                r.message.lines().joinToString("\n") { "// " + it }
            javax.swing.JOptionPane.showMessageDialog(
                frame, r.message,
                if (r.ok) "Saved" else "Save failed",
                if (r.ok) javax.swing.JOptionPane.INFORMATION_MESSAGE
                else javax.swing.JOptionPane.ERROR_MESSAGE
            )
        }

        val copy = JButton("Copy Kotlin")
        copy.addActionListener {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(out.text), null
            )
        }
        val reset = JButton("Reset all")
        reset.addActionListener {
            ks.forEach { it.reset() }
            view.boxes.forEach { it.reset() }
            LayoutOverride.clear()
            view.refresh()
            refreshKotlin()
        }
        val row = JPanel()
        row.alignmentX = 0f
        row.add(save)
        row.add(copy)
        row.add(reset)
        row.maximumSize = Dimension(Int.MAX_VALUE, 40)
        side.add(row)

        frame.layout = BorderLayout()
        frame.add(JScrollPane(view), BorderLayout.CENTER)
        frame.add(side, BorderLayout.EAST)

        view.refresh()
        refreshKotlin()
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
