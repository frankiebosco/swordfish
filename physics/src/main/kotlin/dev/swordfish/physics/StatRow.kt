package dev.swordfish.physics

/**
 * The rows the head unit's stats column can show, in display priority order.
 *
 * Order is significant: the narrow (split-screen) layout has room for only
 * [PanelLayout.NARROW_STAT_CELLS] cells and takes them from the FRONT of the
 * list, so the most glanceable readouts come first.
 *
 * [label] is exactly what the panel prints. [description] is for the phone
 * settings screen, where a bare "EFF" means nothing to someone choosing rows.
 */
enum class StatRow(val label: String, val description: String) {
    GEAR("GEAR", "Inferred gear"),
    RPM("RPM", "Engine speed"),
    TWR("TWR", "Thrust-to-weight, as a percentage"),
    FUEL("FUEL", "Fuel aboard, litres"),
    MASS("MASS", "Total mass: car, crew, cargo and fuel"),
    EFF("EFF", "Thermal efficiency"),
    ALT("ALT", "Altitude above sea level"),
    VOLTS("VOLTS", "Electrical bus voltage"),
    TEMP("TEMP", "Coolant temperature");
}

/**
 * Which [StatRow]s the driver has chosen to show.
 *
 * ## Display only
 *
 * Hiding a row changes what is DRAWN, never what is recorded. The drive log
 * keeps every field regardless, so turning VOLTS off for a month leaves no
 * hole in the history.
 *
 * ## Never empty
 *
 * A stats column with zero rows would draw nothing in a band the layout has
 * already reserved, which looks like a fault rather than a choice. [without]
 * refuses to remove the last row, and [decode] falls back to every row when
 * stored data would leave none.
 *
 * ## Fewer rows read bigger
 *
 * The renderer sizes row text from the column height divided by the row
 * count, so a five-row selection draws each value roughly 80% larger than
 * the full nine. That is the practical payoff of choosing.
 */
class StatSelection private constructor(val visible: Set<StatRow>) {

    fun isVisible(row: StatRow): Boolean = row in visible

    /** The visible rows in display priority order. */
    val ordered: List<StatRow> get() = StatRow.entries.filter { it in visible }

    /** A copy with [row] shown. */
    fun with(row: StatRow): StatSelection = StatSelection(visible + row)

    /**
     * A copy with [row] hidden -- unless it is the last visible row, in which
     * case this selection is returned unchanged.
     */
    fun without(row: StatRow): StatSelection =
        if (visible.size <= 1 && row in visible) this
        else StatSelection(visible - row)

    /** True when [row] is the only one left and so cannot be hidden. */
    fun isLastVisible(row: StatRow): Boolean = visible.size == 1 && row in visible

    /**
     * Comma-separated enum names, for SharedPreferences.
     *
     * Names rather than ordinals, so reordering or adding rows in a later
     * build cannot silently remap a stored choice onto different rows.
     */
    fun encode(): String = ordered.joinToString(",") { it.name }

    override fun equals(other: Any?): Boolean =
        other is StatSelection && other.visible == visible

    override fun hashCode(): Int = visible.hashCode()

    override fun toString(): String = "StatSelection(${encode()})"

    companion object {
        /** Every row: what the panel showed before the choice existed. */
        val ALL = StatSelection(StatRow.entries.toSet())

        fun of(rows: Collection<StatRow>): StatSelection =
            if (rows.isEmpty()) ALL else StatSelection(rows.toSet())

        /**
         * Parse a stored selection.
         *
         * `null` (never set) is every row, so existing installs see no change
         * on upgrade. Unknown names -- a row a later build removed -- are
         * skipped rather than failing the whole read, and a value that leaves
         * nothing visible falls back to every row.
         */
        fun decode(stored: String?): StatSelection {
            if (stored == null) return ALL
            val rows = stored.split(',')
                .mapNotNull { name -> StatRow.entries.firstOrNull { it.name == name.trim() } }
            return of(rows)
        }
    }
}
