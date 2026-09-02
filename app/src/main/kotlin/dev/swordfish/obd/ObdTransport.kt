package dev.swordfish.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import dev.swordfish.physics.ElmProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic SPP transport to the OBD adapter.
 *
 * This class owns the socket and nothing else. Every decision about what the
 * bytes *mean* lives in `:physics` ([ElmProtocol], [dev.swordfish.physics.ObdPid])
 * where it can be tested without hardware. What is left here is genuinely
 * untestable off-device — opening a socket, blocking on a stream — and is
 * kept as small as that implies.
 *
 * ## Threading
 *
 * Every method blocks. Call from a background thread; the caller owns the
 * threading policy. This is deliberate: an OBD poll is a strictly sequential
 * request/response conversation, and wrapping it in coroutines or callbacks
 * would hide the one property that matters — that command N+1 must not be
 * written until command N's prompt has arrived.
 */
class ObdTransport(
    private val onLog: (String) -> Unit = {}
) {

    /**
     * The standard Serial Port Profile UUID.
     *
     * Every ELM327-compatible adapter, genuine or clone, advertises this.
     * It is not adapter-specific and should not be made configurable.
     */
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /** The device we are connected to, for the log. */
    var deviceLabel: String = "—"
        private set

    /**
     * Bonded devices that plausibly look like an OBD adapter.
     *
     * Matching on name is crude, but the alternative — connecting to every
     * bonded device in turn to see which answers `ATZ` — would try to open
     * an SPP socket against the user's headphones and the car's hands-free
     * unit. The full bonded list is returned alongside so the UI can offer
     * a manual pick when the guess is wrong.
     */
    @SuppressLint("MissingPermission")
    fun bondedCandidates(adapter: BluetoothAdapter?): Pair<List<BluetoothDevice>, List<BluetoothDevice>> {
        val all = try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            onLog("bonded device list denied: ${e.message}")
            emptyList()
        }
        val likely = all.filter { d ->
            val n = (d.name ?: "").uppercase()
            LIKELY_NAMES.any { n.contains(it) }
        }
        return likely to all
    }

    /**
     * Open the SPP socket.
     *
     * Tries the secure socket first and falls back to the insecure variant,
     * which is the documented workaround for adapters that refuse an
     * authenticated channel. Both are attempted before reporting failure
     * because the failure mode is identical from the caller's side and
     * trying only one produces a "broken dongle" report for a working one.
     */
    @SuppressLint("MissingPermission")
    fun open(device: BluetoothDevice, adapter: BluetoothAdapter?): Boolean {
        deviceLabel = try {
            "${device.name ?: "unnamed"} (${device.address})"
        } catch (e: SecurityException) {
            device.address
        }
        onLog("opening SPP to $deviceLabel")

        // Discovery is expensive and will slow or break an in-flight
        // connection attempt. Android documents cancelling it first.
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // Not fatal; the connect below may still succeed.
        }

        if (tryOpen(secure = true, device)) return true
        onLog("secure socket failed, trying insecure")
        return tryOpen(secure = false, device)
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(secure: Boolean, device: BluetoothDevice): Boolean {
        return try {
            val s = if (secure) {
                device.createRfcommSocketToServiceRecord(sppUuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            }
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
            onLog("connected (${if (secure) "secure" else "insecure"})")
            true
        } catch (e: IOException) {
            onLog("${if (secure) "secure" else "insecure"} connect failed: ${e.message}")
            closeQuietly()
            false
        } catch (e: SecurityException) {
            onLog("BLUETOOTH_CONNECT permission missing: ${e.message}")
            closeQuietly()
            false
        }
    }

    /**
     * Send one command and read until the prompt.
     *
     * @return the raw reply, or null on timeout or I/O failure.
     *
     * The read loop terminates on [ElmProtocol.PROMPT], never on a byte
     * count. See the note in [ElmProtocol] — a fixed-size read appears to
     * work at low rates and silently desynchronises under load, at which
     * point every reply belongs to the previous command and the gauge shows
     * confident nonsense.
     */
    fun send(command: String, timeoutMs: Long = 1000): String? {
        val out = output ?: return null
        val inp = input ?: return null

        return try {
            // Drain anything left over from a previous timeout. Without
            // this, one late reply desynchronises every command after it.
            drain(inp)

            out.write((command + ElmProtocol.TERMINATOR).toByteArray())
            out.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = StringBuilder()
            val chunk = ByteArray(256)

            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(chunk)
                    if (n > 0) {
                        buf.append(String(chunk, 0, n, Charsets.US_ASCII))
                        if (ElmProtocol.isComplete(buf.toString())) {
                            return buf.toString()
                        }
                    }
                } else {
                    // Yield rather than spin. 2 ms is well under the ~25 ms
                    // a fast adapter takes to answer, so it does not cost
                    // throughput, but it keeps the thread off the CPU.
                    Thread.sleep(2)
                }
            }
            onLog("timeout after ${timeoutMs}ms: $command")
            null
        } catch (e: IOException) {
            onLog("io error on $command: ${e.message}")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Send a command and stream reply lines until [durationMs] elapses.
     *
     * For `ATMA`, which does not answer once and stop — it prints CAN
     * frames continuously until something is written back. The normal
     * [send] would block waiting for a prompt that never arrives.
     *
     * @param continuing when true, the adapter is ALREADY streaming from a
     *   previous call and must not be re-armed: the command is not resent and
     *   the input buffer is not drained. Frames that arrived between calls are
     *   kept rather than discarded. Used to run one long `ATMA` as a series of
     *   short, stoppable slices without a gap at every boundary.
     * @param leaveRunning when true, the monitor is NOT stopped on the way
     *   out, so the next [monitor] call with `continuing = true` picks up the
     *   same stream. The caller MUST eventually call once with this false (or
     *   call [stopMonitor]) or the adapter keeps streaming into later commands.
     * @return the number of lines delivered to [onLine].
     */
    fun monitor(
        command: String,
        durationMs: Long,
        continuing: Boolean = false,
        leaveRunning: Boolean = false,
        onLine: (String) -> Unit
    ): Int {
        val out = output ?: return 0
        val inp = input ?: return 0
        var lines = 0

        return try {
            // Re-arming mid-capture would drop whatever arrived since the last
            // slice ended. A continuing slice inherits the live stream.
            if (!continuing) {
                drain(inp)
                out.write((command + ElmProtocol.TERMINATOR).toByteArray())
                out.flush()
            }

            val deadline = System.currentTimeMillis() + durationMs
            val buf = StringBuilder()
            val chunk = ByteArray(512)

            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(chunk)
                    if (n > 0) {
                        buf.append(String(chunk, 0, n, Charsets.US_ASCII))
                        // Emit complete lines as they arrive; a monitor run
                        // can produce thousands, and buffering the lot would
                        // mean holding a whole drive in memory.
                        var idx = buf.indexOfFirst { it == '\r' || it == '\n' }
                        while (idx >= 0) {
                            val line = buf.substring(0, idx).trim()
                            buf.delete(0, idx + 1)
                            if (line.isNotEmpty()) {
                                onLine(line)
                                lines++
                            }
                            idx = buf.indexOfFirst { it == '\r' || it == '\n' }
                        }
                    }
                } else {
                    Thread.sleep(2)
                }
            }

            // Anything written stops a running ATMA. Without this the
            // adapter keeps streaming into every subsequent command. Skipped
            // when the caller is going to continue this same stream -- the
            // stop/settle/drain costs ~200ms of deafness per boundary.
            if (!leaveRunning) {
                out.write(ElmProtocol.TERMINATOR.toString().toByteArray())
                out.flush()
                Thread.sleep(200)
                drain(inp)
            }

            lines
        } catch (e: IOException) {
            onLog("io error during monitor: ${e.message}")
            lines
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lines
        }
    }

    /**
     * Stop an `ATMA` left running by `monitor(leaveRunning = true)`.
     *
     * Safe to call when nothing is streaming: the write is harmless and the
     * drain finds an empty buffer. Callers that leave a monitor running MUST
     * reach this on every exit path, including exceptions -- otherwise the
     * adapter streams CAN frames into the next command and every reply after
     * it belongs to the wrong request.
     */
    fun stopMonitor() {
        val out = output ?: return
        val inp = input ?: return
        try {
            out.write(ElmProtocol.TERMINATOR.toString().toByteArray())
            out.flush()
            Thread.sleep(200)
            drain(inp)
        } catch (e: IOException) {
            onLog("io error stopping monitor: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Discard any unread bytes. Called before each command. */
    private fun drain(inp: InputStream) {
        try {
            val junk = ByteArray(256)
            var total = 0
            while (inp.available() > 0) {
                val n = inp.read(junk)
                if (n <= 0) break
                total += n
            }
            if (total > 0) onLog("drained $total stale bytes")
        } catch (e: IOException) {
            // Nothing useful to do; the next read will surface the fault.
        }
    }

    fun close() {
        closeQuietly()
        onLog("closed")
    }

    private fun closeQuietly() {
        try { input?.close() } catch (e: IOException) { }
        try { output?.close() } catch (e: IOException) { }
        try { socket?.close() } catch (e: IOException) { }
        input = null
        output = null
        socket = null
    }

    private companion object {
        /**
         * Name fragments that suggest an OBD adapter.
         *
         * OBDLink devices announce themselves as `OBDLink MX+` or similar;
         * the generic clones almost universally use `OBDII` or `ELM327`.
         */
        val LIKELY_NAMES = listOf("OBD", "ELM", "VLINK", "VEEPEAK", "SCAN")
    }
}
