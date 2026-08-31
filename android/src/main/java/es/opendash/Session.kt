package es.opendash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import es.opendash.obd.Diagnostics
import es.opendash.protocol.Sm3Client

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

    /** Battery volts, from opcode 0x20. Zero when the link is not up. */
    fun batteryMillivolts(): Int = 0
}
