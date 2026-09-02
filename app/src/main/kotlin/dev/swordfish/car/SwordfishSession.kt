package dev.swordfish.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session
import dev.swordfish.ui.Prefs

/**
 * One connection to the car head unit.
 *
 * Owns the [GaugeRenderer] so it survives screen changes, and forwards car
 * configuration changes (notably light/dark switches, which happen mid-drive
 * at dusk or in tunnels) so the panel repaints correctly.
 */
class SwordfishSession : Session() {

    private var renderer: GaugeRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val r = GaugeRenderer(carContext)
        // Preferences are read at screen creation rather than watched: the car
        // screen is recreated when the app is reopened, which is a natural
        // point to pick up a changed setting.
        val prefs = Prefs(carContext)
        r.ghostSegments = prefs.ghostSegments
        r.scanlines = prefs.scanlines
        r.theme = prefs.displayTheme
        renderer = r
        return GaugeScreen(carContext, r)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        renderer?.onConfigurationChanged()
    }
}
