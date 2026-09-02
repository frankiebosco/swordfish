package dev.swordfish.harness

import java.io.File

/**
 * Writes tuned values straight back into `PanelLayout.kt`.
 *
 * ## Why this writes to source, when the tuner deliberately did not
 *
 * The first version only SHOWED you the Kotlin, on the reasoning that a
 * preview tool silently rewriting source is a surprising thing to leave
 * running. That reasoning was sound but the cost landed in the wrong place:
 * it meant copy-pasting constants out of a text box on every iteration,
 * which is friction on the exact loop this tool exists to remove.
 *
 * So saving is now explicit and one button, and the surprise is handled by
 * being loud rather than by refusing:
 *
 *  - it only ever touches lines matching `const val NAME = <number>` for
 *    names it already knows -- it cannot invent, reorder or delete anything
 *  - every comment, blank line and doc block is preserved byte for byte
 *  - it writes a `.bak` beside the file first
 *  - it reports exactly which constants changed, and from what to what
 *
 * ## What still has to happen after a save
 *
 * The DBG build on the phone is a compiled APK; editing source does not
 * change it. After saving, run `tools\dbg.bat` (build + install +
 * force-stop) to see the change on the head unit. The tuner window itself
 * keeps its in-memory values and does not need restarting.
 */
object SaveValues {

    /** Where PanelLayout.kt lives, relative to the repo root. */
    private const val PANEL_LAYOUT =
        "physics/src/main/kotlin/dev/swordfish/physics/PanelLayout.kt"

    /**
     * Constants that are Doubles in the source, so they must not gain an `f`.
     *
     * Writing `0.2969f` where a Double is declared is a compile error, and
     * the tuner would have "saved" a repo that no longer builds.
     */
    private val DOUBLE_VALUED = setOf("WIDE_READOUT_BAND")

    data class Change(val name: String, val from: String, val to: String)

    data class Result(
        val ok: Boolean,
        val message: String,
        val changes: List<Change> = emptyList()
    )

    private fun locate(): File? {
        for (p in listOf(PANEL_LAYOUT, "../$PANEL_LAYOUT", "../../$PANEL_LAYOUT")) {
            val f = File(p)
            if (f.isFile) return f
        }
        return null
    }

    /** Format a value the way the source expects for that constant. */
    fun format(name: String, v: Float): String {
        val text = "%.4f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        val cleaned = if (text == "-0") "0" else text
        return if (name in DOUBLE_VALUED) cleaned else cleaned + "f"
    }

    /**
     * Apply [values] to PanelLayout.kt.
     *
     * @param values constant name -> new value
     */
    fun save(values: Map<String, Float>): Result {
        if (values.isEmpty()) return Result(true, "Nothing to save.")

        val file = locate()
            ?: return Result(
                false,
                "Cannot find $PANEL_LAYOUT from ${File(".").absolutePath}. " +
                    "Run the tuner from the repo root."
            )

        val original = file.readText()
        var text = original
        val changes = ArrayList<Change>()
        val missing = ArrayList<String>()

        for ((name, v) in values.entries.sortedBy { it.key }) {
            // Anchored to a whole line so a name that appears in a comment
            // or a doc block is never touched.
            val re = Regex(
                "^(\\s*const val " + Regex.escape(name) + " = )(-?[0-9.]+f?)\\s*$",
                RegexOption.MULTILINE
            )
            val m = re.find(text)
            if (m == null) {
                missing += name
                continue
            }
            val old = m.groupValues[2]
            val new = format(name, v)
            if (old == new) continue
            text = text.replaceRange(m.range, m.groupValues[1] + new)
            changes += Change(name, old, new)
        }

        if (missing.isNotEmpty()) {
            return Result(
                false,
                "Not found in PanelLayout.kt: ${missing.joinToString(", ")}. " +
                    "Nothing was written."
            )
        }
        if (changes.isEmpty()) {
            return Result(true, "Already up to date - no constants differed.")
        }

        // Backup BEFORE writing. A tuner that eats a hand-written file is
        // worse than one that never saved at all.
        File(file.parentFile, file.name + ".bak").writeText(original)
        file.writeText(text)

        val detail = changes.joinToString("\n") {
            "  ${it.name}: ${it.from} -> ${it.to}"
        }
        return Result(
            true,
            "Saved ${changes.size} value(s) to PanelLayout.kt\n" + detail +
                "\n\nRun tools\\dbg.bat to put this on the head unit.",
            changes
        )
    }
}
