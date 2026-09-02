package dev.swordfish.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * The head-unit entry point.
 *
 * Android Auto binds to this service when the app is opened on the car
 * screen. Everything Swordfish draws on the head unit originates here.
 */
class SwordfishCarAppService : CarAppService() {

    /**
     * Which hosts may connect to this service.
     *
     * [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] permits any host, which
     * Google's own documentation flags as unsafe for a published app — a
     * malicious host could drive the app. It is acceptable here **only**
     * because Swordfish is a personal, sideload-distributed build that will
     * never reach the Play Store.
     *
     * If this project ever changes distribution model, this must become a
     * real allowlist keyed on the host package signature.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        SwordfishSession()
}
