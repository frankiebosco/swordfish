package androidx.car.app

import android.graphics.Rect

/**
 * The three Car App Library types the renderer touches, reduced to what it
 * actually uses. See android/graphics/Primitives.kt for why.
 *
 * `CarContext` is used for exactly one thing -- `isDarkMode` -- so the
 * harness can drive day/night simply by constructing it either way.
 */
class CarContext(val isDarkMode: Boolean = true)

/**
 * Real SurfaceContainer exposes a `surface`; the harness never touches it,
 * because the harness calls `draw()` directly rather than going through
 * `render()`, which is the part that locks a real Surface.
 */
class SurfaceContainer(val width: Int, val height: Int, val dpi: Int = 160) {
    /**
     * Always null. `render()` bails on a null surface, which is exactly what
     * the harness wants: it calls `draw()` directly. Present only so the
     * real source compiles.
     */
    val surface: Surface? = null
}

/**
 * Never instantiated by the harness -- `SurfaceContainer.surface` is always
 * null, so `render()` returns before reaching any of this. Present only so
 * the real GaugeRenderer source compiles.
 */
class Surface {
    val isValid: Boolean = false
    fun lockCanvas(dirty: android.graphics.Rect?): android.graphics.Canvas? =
        throw UnsupportedOperationException("harness draws via draw(), not render()")
    fun unlockCanvasAndPost(canvas: android.graphics.Canvas): Unit =
        throw UnsupportedOperationException("harness draws via draw(), not render()")
}

interface SurfaceCallback {
    fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {}
    fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {}
    fun onVisibleAreaChanged(visibleArea: Rect) {}
    fun onStableAreaChanged(stableArea: Rect) {}
}
