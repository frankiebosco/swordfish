package dev.swordfish.physics

/**
 * Seven-segment glyph definitions.
 *
 * ## Why glyphs rather than a font
 *
 * The look we are after — the *Ghost in the Machine* cover, or any real LCD
 * fuel computer — is not a typeface. It is **segment geometry**: thick angled
 * bars with hard-cut ends, and, crucially, the **unlit segments faintly
 * visible behind the lit ones**. That last detail is what makes a display read
 * as a display rather than as lettering, and no font can reproduce it because
 * a font has no concept of a segment that is present but off.
 *
 * So the digits are drawn as bars. This file owns which segments each
 * character lights; the renderer owns how a bar is shaped.
 *
 * ## Segment naming
 *
 * The standard layout, clockwise from the top:
 *
 * ```
 *      AAAA
 *     F    B
 *     F    B
 *      GGGG
 *     E    C
 *     E    C
 *      DDDD
 * ```
 */
object SevenSegment {

    const val A = 1 shl 0   // top
    const val B = 1 shl 1   // upper right
    const val C = 1 shl 2   // lower right
    const val D = 1 shl 3   // bottom
    const val E = 1 shl 4   // lower left
    const val F = 1 shl 5   // upper left
    const val G = 1 shl 6   // middle

    /**
     * Decimal point.
     *
     * Not one of the seven bars: on a real display it is a dot in the
     * bottom-right corner of a digit cell, attached to the digit before it
     * rather than occupying a cell of its own. Handled as a flag on the
     * preceding character so "0.15" costs three cells, not four.
     */
    const val DP = 1 shl 7

    /** All seven bars — the "ghost" layer drawn behind a glyph. */
    const val ALL = A or B or C or D or E or F or G

    /**
     * Which segments each supported character lights.
     *
     * Seven segments cannot render arbitrary text. Digits are exact; a small
     * set of letters is conventional and legible; everything else is refused
     * rather than approximated, because a mangled letter looks like a fault
     * rather than a character. Labels use ordinary type instead — which is
     * how real dashboards do it too.
     */
    private val GLYPHS: Map<Char, Int> = mapOf(
        '0' to (A or B or C or D or E or F),
        '1' to (B or C),
        '2' to (A or B or G or E or D),
        '3' to (A or B or G or C or D),
        '4' to (F or G or B or C),
        '5' to (A or F or G or C or D),
        '6' to (A or F or G or E or C or D),
        '7' to (A or B or C),
        '8' to ALL,
        '9' to (A or B or C or D or F or G),
        '-' to G,
        ' ' to 0,

        // The conventional legible subset.
        'A' to (A or B or C or E or F or G),
        'b' to (F or E or D or C or G),
        'C' to (A or F or E or D),
        'c' to (G or E or D),
        'd' to (B or C or D or E or G),
        'E' to (A or F or G or E or D),
        'F' to (A or F or G or E),
        'H' to (F or E or G or B or C),
        'h' to (F or E or G or C),
        'L' to (F or E or D),
        'n' to (E or G or C),
        'o' to (G or E or D or C),
        'P' to (A or B or F or G or E),
        'r' to (E or G),
        't' to (F or G or E or D),
        'U' to (B or C or D or E or F),
        'u' to (E or D or C)
    )

    /** Segment mask for [c], or null when it cannot be rendered. */
    fun segmentsFor(c: Char): Int? = GLYPHS[c]

    /** True when every character in [text] can be rendered as segments. */
    fun canRender(text: String): Boolean = text.all { GLYPHS.containsKey(it) }

    /**
     * Replace unrenderable characters so a string can always be displayed.
     *
     * Falls back to a blank rather than a wrong glyph: a gap reads as "no
     * data", whereas a substituted character reads as a value.
     */
    fun sanitise(text: String): String =
        text.map { if (GLYPHS.containsKey(it)) it else ' ' }.joinToString("")

    /**
     * Whether a segment is lit for a given character.
     *
     * @param segment One of [A]..[G].
     */
    fun isLit(c: Char, segment: Int): Boolean {
        val mask = GLYPHS[c] ?: return false
        return (mask and segment) != 0
    }

    /** Every bar segment, for iterating when drawing a glyph. */
    val SEGMENTS = intArrayOf(A, B, C, D, E, F, G)

    /**
     * Split a display string into per-cell glyphs, folding any decimal point
     * onto the character before it.
     *
     * `"0.15"` becomes `['0'+DP, '1', '5']` — three cells. Rendering the point
     * as its own character would waste a full cell and read as a gap, the same
     * problem the thousands separator had.
     *
     * A leading decimal point (".5") is kept as its own cell with no digit,
     * since there is nothing to attach it to.
     *
     * @return pairs of (character, hasDecimalPoint).
     */
    fun cells(text: String): List<Pair<Char, Boolean>> {
        val out = mutableListOf<Pair<Char, Boolean>>()
        for (c in text) {
            if (c == '.' || c == ',') {
                if (out.isNotEmpty() && !out.last().second) {
                    out[out.size - 1] = out.last().first to true
                } else {
                    out.add(' ' to true)
                }
            } else {
                out.add(c to false)
            }
        }
        return out
    }

    /** True when every character in [text] can be shown, decimal points included. */
    fun canDisplay(text: String): Boolean =
        cells(text).all { GLYPHS.containsKey(it.first) }
}

/**
 * Display colour schemes.
 *
 * Green is the default — the classic instrument-cluster phosphor. Red is
 * offered because it is the *Ghost in the Machine* palette, and is arguably
 * more faithful to the reference that inspired the look.
 *
 * Stored as plain ints so `:physics` need not depend on `android.graphics`.
 * Packed 0xRRGGBB.
 */
enum class DisplayTheme(
    val label: String,
    val bright: Int,
    val mid: Int,
    val dim: Int,
    /** Unlit segments: present, but only just. */
    val ghost: Int,
    val accent: Int
) {
    GREEN(
        label = "Phosphor green",
        bright = 0x7CFFB4,
        mid = 0x3FB876,
        dim = 0x226642,
        // Ghost segments must be *barely* perceptible. An earlier build used
        // 0x0E2A1B, which was bright enough that a 7 read as a 78 and RPM was
        // nearly unreadable -- the off-segments competed with the lit ones.
        ghost = 0x07160E,
        accent = 0xFFB32E
    ),

    RED(
        label = "LCD red",
        bright = 0xFF4438,
        mid = 0xC02A22,
        dim = 0x6E1712,
        ghost = 0x140404,
        accent = 0xFFB32E
    ),

    AMBER(
        label = "Amber",
        bright = 0xFFBE45,
        mid = 0xC7841C,
        dim = 0x6B4710,
        ghost = 0x140D03,
        accent = 0x7CFFB4
    );

    companion object {
        val DEFAULT = GREEN

        /** Look up by stored preference name, falling back to the default. */
        fun fromName(name: String?): DisplayTheme =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
