package dev.swordfish.obd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.swordfish.physics.LinkState

/**
 * Keeps the OBD poll running while the phone screen is off.
 *
 * ## Why a service is required, not merely tidy
 *
 * While projecting to the head unit the phone screen is normally off and
 * the app is not foreground. Android will freeze background threads in that
 * state, which would stop the poll every time the display slept — the exact
 * condition the app spends its entire life in. A foreground service with
 * the `connectedDevice` type is the sanctioned way to hold a device
 * connection open across that.
 *
 * The manifest already declares `FOREGROUND_SERVICE` and
 * `FOREGROUND_SERVICE_CONNECTED_DEVICE` for this.
 *
 * ## Ownership
 *
 * The service owns the single [ObdPoller] instance for the process, exposed
 * through [poller] so `GaugeScreen` can read the latest values without
 * binding. A bound service would be more orthodox, but the car screen's
 * lifecycle is driven by the Android Auto host rather than by us, and a
 * binding that outlives its screen is a leak waiting to happen.
 */
class TelemetryService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A connectedDevice foreground service needs BLUETOOTH_CONNECT to be
        // GRANTED, not merely declared. Declaring it in the manifest is not
        // enough: startForeground throws SecurityException and kills the
        // process, which took the whole instrument panel down with it.
        //
        // The permission is requested by ProbeActivity, so a user who has
        // never opened the probe screen has not granted it. Bail quietly
        // instead: the panel then runs on its sample frame and shows
        // NO_ADAPTER, which is honest and survivable.
        // startForegroundService() makes a PROMISE: this service must call
        // startForeground() within a few seconds or Android kills the whole
        // process with ForegroundServiceDidNotStartInTimeException. So an
        // early return that skips the promotion is itself fatal -- calling
        // stopSelf() first does NOT release us from the promise.
        //
        // Therefore: promote first, always, and only then decide whether
        // there is anything to do. The permission check lives in
        // Companion.start so the promise is never made when it cannot be
        // kept; this is the last line of defence.
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        } catch (e: Exception) {
            // The connectedDevice type requires BLUETOOTH_CONNECT to be
            // GRANTED, not merely declared. If it is not, promotion throws
            // and there is nothing further to attempt.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasBluetoothPermission()) {
            android.util.Log.w(POLL_LOG_TAG, "poll: BLUETOOTH_CONNECT not granted")
            updateNotification("Bluetooth permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter

        val device: BluetoothDevice? = try {
            when {
                address != null -> adapter?.getRemoteDevice(address)
                // No address given: fall back to the first bonded device
                // that looks like an adapter, which is the common case
                // where the user has exactly one dongle paired.
                else -> adapter?.bondedDevices?.firstOrNull { d ->
                    val n = (d.name ?: "").uppercase()
                    LIKELY_NAMES.any { n.contains(it) }
                }
            }
        } catch (e: SecurityException) {
            null
        }

        if (device == null || adapter == null) {
            // Logged, not just shown in a notification nobody reads while
            // driving. The 2026-08-21 drive produced ZERO poller output and
            // there was no way to tell which branch had been taken.
            android.util.Log.w(
                POLL_LOG_TAG,
                "poll: no adapter (device=${device != null}, adapter=${adapter != null})"
            )
            updateNotification("No adapter paired")
            stopSelf()
            return START_NOT_STICKY
        }

        android.util.Log.i(POLL_LOG_TAG, "poll: starting for ${device.address}")
        poller.start(device, adapter)
        return START_STICKY
    }

    override fun onDestroy() {
        poller.stop()
        super.onDestroy()
    }

    /**
     * True when the runtime Bluetooth permission required by a
     * `connectedDevice` foreground service has been granted.
     *
     * Below API 31 the granular permissions do not exist and the legacy
     * `BLUETOOTH` permission is install-time, so there is nothing to check.
     */
    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Telemetry",
                        // LOW: this notification exists to satisfy the
                        // foreground-service requirement, not to be read.
                        // Anything higher would buzz on every drive.
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Swordfish")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val CHANNEL_ID = "swordfish.telemetry"

        /** logcat tag for the poll session: `adb logcat -s swordfish-poll`. */
        const val POLL_LOG_TAG = "swordfish-poll"
        private const val NOTIFICATION_ID = 1

        private val LIKELY_NAMES = listOf("OBD", "ELM", "VLINK", "VEEPEAK", "SCAN")

        /**
         * The process-wide poller.
         *
         * Static because the car screen and the service have independent,
         * host-driven lifecycles and neither can own the other. The screen
         * reads; the service starts and stops.
         */
        val poller = ObdPoller { msg ->
            // The poller runs unattended for a whole drive. Without a log,
            // a failure at 60 mph leaves nothing to diagnose afterwards --
            // unlike the probe, which writes NDJSON. android.util.Log is
            // enough: `adb logcat -s swordfish-poll` recovers the session.
            android.util.Log.i(POLL_LOG_TAG, msg)
        }

        /** True when telemetry is flowing well enough to drive the panel. */
        val isLive: Boolean get() = poller.linkState == LinkState.LIVE

        /**
         * Start the poll, if it can possibly succeed.
         *
         * Returns false without starting anything when the runtime
         * Bluetooth permission is missing. That check belongs HERE rather
         * than inside the service: `startForegroundService` obliges the
         * service to promote itself within seconds, and a service that
         * cannot promote takes the process down with it. The safe move is
         * never to make the promise.
         */
        fun start(context: Context, deviceAddress: String? = null): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            val intent = Intent(context, TelemetryService::class.java).apply {
                if (deviceAddress != null) putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelemetryService::class.java))
        }
    }
}
