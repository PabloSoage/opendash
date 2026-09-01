package com.varuna.opendash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.opendash.obd.Diagnostics
import com.varuna.opendash.protocol.Sm3Client

/**
 * The one live link to the device, shared by the screens and the service.
 *
 * A single socket, deliberately. The SM3 takes one client at a time — a second
 * connection while the first is open is how the earlier captures ended in
 * refusals — so the app funnels everything through here rather than letting
 * each screen open its own.
 */
object Session {

    val sm3 = Sm3Client()
    val diagnostics = Diagnostics(sm3)

    var state by mutableStateOf(State.DISCONNECTED)
        private set

    var serial by mutableStateOf("")
        private set

    var firmware by mutableStateOf("")
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    var vehicle by mutableStateOf<Diagnostics.VehicleId?>(null)

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, CHANNEL_OPEN }

    /** Point the client at whatever the settings say before opening a socket. */
    fun configure(host: String, port: Int) {
        sm3.host = host
        sm3.port = port
    }

    /** Greet the device. Cheap, and it proves the Wi-Fi is right. */
    fun connect(): Boolean {
        state = State.CONNECTING
        lastError = null
        return try {
            val id = sm3.connect()
            serial = id.serial
            firmware = id.firmware
            state = State.CONNECTED
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            state = State.DISCONNECTED
            false
        }
    }

    /** Replay the recorded opening so the bus can be polled. */
    fun openChannel(): Boolean {
        if (state == State.DISCONNECTED && !connect()) return false
        return try {
            sm3.openChannel()
            state = State.CHANNEL_OPEN
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    fun disconnect() {
        sm3.close()
        state = State.DISCONNECTED
        serial = ""
        firmware = ""
        vehicle = null
    }

    /**
     * Battery millivolts, cached.
     *
     * Read from the device rather than from the bus, so it works as soon as the
     * socket is up and does not need a channel. Cached for a second because
     * ATRV gets asked far more often than the voltage moves.
     */
    private var voltsAt = 0L

    /**
     * The last reading, for anything on the main thread to display. It is a
     * Compose state so a screen showing it redraws when it moves, and reading
     * it never touches the socket — which matters, because composition happens
     * on the main thread and a socket read there is a crash.
     */
    var batteryMillivolts by mutableStateOf(0)
        private set

    /** Blocking. Call it from a worker thread; the bridge and the UI both do. */
    fun refreshBattery(): Int {
        if (state == State.DISCONNECTED) return 0
        val now = System.currentTimeMillis()
        if (now - voltsAt < 1000) return batteryMillivolts
        batteryMillivolts = try {
            sm3.voltages()?.batteryMillivolts ?: batteryMillivolts
        } catch (_: Exception) {
            batteryMillivolts
        }
        voltsAt = now
        return batteryMillivolts
    }
}
