package dev.swordfish.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.navigation.model.NavigationTemplate
import dev.swordfish.physics.RadarLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the template-construction rules that only fail at runtime on a head
 * unit.
 *
 * ## Why this exists
 *
 * The first attempt to run Swordfish on the DHU crashed with:
 *
 * ```
 * java.lang.IllegalStateException: Action strip for this template must be set
 *     at NavigationTemplate$Builder.build(NavigationTemplate.java:388)
 * ```
 *
 * `NavigationTemplate` **requires** an `ActionStrip`. Nothing in the type
 * system says so — `setActionStrip` looks optional, the code compiles, lint is
 * clean, and the failure appears only when the host asks for the template on a
 * real (or emulated) car screen.
 *
 * That is a slow feedback loop for a mistake a test can catch in a second, so
 * these tests exercise the builder directly. They need no DHU and no phone.
 */
class GaugeScreenTemplateTest {

    /**
     * The exact bug that broke the first head-unit run.
     *
     * Building a NavigationTemplate without an action strip must throw. If
     * this ever stops throwing, the library has relaxed the requirement and
     * the workaround can be revisited — but until then, the constraint is
     * real and worth pinning.
     */
    @Test
    fun `NavigationTemplate demands an action strip`() {
        var threw = false
        try {
            NavigationTemplate.Builder()
                .setBackgroundColor(CarColor.PRIMARY)
                .build()
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(
                e.message?.contains("Action strip", ignoreCase = true) == true,
                "unexpected message: ${e.message}"
            )
        }
        assertTrue(threw, "building without an action strip should have thrown")
    }

    /**
     * The shape GaugeScreen actually builds. If this throws, the head unit
     * will show "Swordfish has encountered an unexpected error".
     */
    @Test
    fun `the gauge template builds successfully`() {
        val template = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("MODE")
                            .setOnClickListener { }
                            .build()
                    )
                    .build()
            )
            .setBackgroundColor(CarColor.PRIMARY)
            .build()

        assertNotNull(template)
        assertNotNull(template.actionStrip)
    }

    /**
     * The radar-mode shape: two actions, mode and range.
     *
     * The strip is the only input the app has on the real car -- the ND2 has
     * no touchscreen while the engine is running, so the rotary controller
     * driving these buttons is the whole control surface. A strip that fails
     * to build takes the panel down with it, and the failure would only show
     * on the head unit.
     */
    @Test
    fun `the radar-mode template builds with both actions`() {
        val template = NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("PANEL")
                            .setOnClickListener { }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("20 MI")
                            .setOnClickListener { }
                            .build()
                    )
                    .build()
            )
            .setBackgroundColor(CarColor.PRIMARY)
            .build()

        assertNotNull(template)
        assertEquals(2, template.actionStrip?.actions?.size)
    }

    /**
     * Every range label the range button can show must be a legal title.
     *
     * Built from the real range list rather than a literal, so adding a
     * range to `RadarLayout.RANGES_MILES` cannot introduce a title the host
     * rejects without this failing first.
     */
    @Test
    fun `every scope range makes a valid action title`() {
        for (miles in RadarLayout.RANGES_MILES) {
            val action = Action.Builder()
                .setTitle("$miles MI")
                .setOnClickListener { }
                .build()
            assertNotNull(action.title)
        }
    }

    @Test
    fun `an action strip needs at least one action`() {
        // Another empty-builder trap in the same family.
        var threw = false
        try {
            ActionStrip.Builder().build()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "an empty action strip should be rejected")
    }

    @Test
    fun `an action needs a title or an icon`() {
        var threw = false
        try {
            Action.Builder().setOnClickListener { }.build()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "an action with neither title nor icon should be rejected")
    }
}
