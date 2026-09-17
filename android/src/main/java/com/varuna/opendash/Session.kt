package com.varuna.opendash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.varuna.opendash.net.WifiLink
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

    /**
     * The socket is pinned to the adapter's Wi-Fi when the app joined it
     * itself. [WifiLink.bind] does nothing when it did not, so this covers both
     * the phone-joins-the-adapter case and a tablet already on the same
     * network, without the screens having to know which is which.
     */
    val sm3 = Sm3Client().apply { bindSocket = { WifiLink.bind(it) } }
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

    /**
     * CAN addresses that answered the last time modules were swept for.
     *
     * Remembered because it is the one piece of evidence that tells a brand
     * catalogue apart from this car. The catalogue gives every module an
     * address, so intersecting the two turns 21 382 parameters for the marque
     * into the few thousand belonging to modules that are actually fitted, and
     * lets a screen say which of the names on offer this vehicle answered on.
     *
     * Empty means nobody has looked, which is not the same as nothing being
     * there, so a screen must not read it as a denial.
     */
    var modulesPresent by mutableStateOf<List<Int>>(emptyList())

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, CHANNEL_OPEN }

    /**
     * Whether the adapter is filtering the bus for us, or handing over all of
     * it. Worth saying on screen: it is the difference between four addresses
     * and about 1340 frames a second, and everything downstream feels it.
     */
    var busNarrowed by mutableStateOf(false)
        private set

    // ── the bridge, as the service actually found it ──────────────────────

    /**
     * The port the bridge is listening on, or zero.
     *
     * Owned by the service rather than by the button that started it. A
     * foreground service can refuse to start — a missing permission, a port
     * already taken — and a screen that turned its own label to "listening"
     * when the button was pressed would then be telling the user something
     * that is not true.
     */
    var bridgePort by mutableStateOf(0)
        private set

    var bridgeError by mutableStateOf<String?>(null)
        private set

    fun noteBridgeUp(port: Int) {
        bridgePort = port
        bridgeError = null
    }

    fun noteBridgeFault(why: String) {
        bridgePort = 0
        bridgeError = why
    }

    fun noteBridgeDown() {
        bridgePort = 0
    }

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

    /**
     * Replay the recorded opening so the bus can be polled.
     *
     * A failure here drops the link rather than leaving it half open. The
     * adapter keeps one client and forgets it slowly: an abandoned session is
     * what leaves it refusing everything, its own application included, until
     * it is unplugged and back in.
     */
    fun openChannel(): Boolean {
        if (state == State.DISCONNECTED && !connect()) return false
        return try {
            sm3.openChannel()
            busNarrowed = sm3.narrowed
            state = State.CHANNEL_OPEN
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            disconnect()
            false
        }
    }

    /**
     * Ask the car what it is.
     *
     * Wrapped because it runs on a worker thread, and an exception on a worker
     * thread with nobody to catch it takes the whole app down. A link that has
     * gone away mid-question is an ordinary event — the adapter loses power
     * with the ignition — and it belongs on the error line, not in a crash.
     */
    fun identify(): Boolean {
        lastError = null
        return guarded {
            vehicle = diagnostics.identify()
            true
        } ?: false
    }

    /**
     * Run something that touches the socket; report rather than crash.
     *
     * Success does not clear the error line. The battery is re-read every two
     * seconds, and clearing on every success would wipe the message explaining
     * why the last thing the user actually asked for did not work, a second
     * after it appeared.
     */
    fun <T> guarded(work: () -> T): T? = try {
        work().also { syncTransport() }
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        syncTransport()
        if (!sm3.isConnected) state = State.DISCONNECTED
        null
    }

    // ── what the transport is reporting about itself ──────────────────────

    /**
     * Bytes the reader had to throw away to find the start of a message, and
     * the last thing that went wrong down there.
     *
     * Mirrored here because the client is plain Kotlin with no Compose in it,
     * and a screen has to be told to redraw. Worth showing: a link that is
     * quietly resynchronising still answers, so nothing looks broken until a
     * value comes back wrong.
     */
    var resynchronised by mutableStateOf(0)
        private set

    var transportFault by mutableStateOf<String?>(null)
        private set

    /** Answers that arrived with nothing waiting for them. See [Sm3Client]. */
    var unpaired by mutableStateOf(0)
        private set

    private fun syncTransport() {
        resynchronised = sm3.resynchronised
        unpaired = sm3.unpaired
        transportFault = sm3.lastFault
    }

    fun disconnect() {
        // Tell whatever might still be emitting to stop, before the link goes.
        // A module left streaming keeps sending a hundred frames a second at an
        // adapter nobody is reading any more; the live screen already stops its
        // own, and this is the case where somebody leaves without stopping it.
        if (state == State.CHANNEL_OPEN) {
            for (module in streaming) runCatching { diagnostics.endStream(module) }
        }
        streaming = emptySet()
        sm3.close()
        state = State.DISCONNECTED
        serial = ""
        firmware = ""
        vehicle = null
        modulesPresent = emptyList()
    }

    /**
     * Modules that have been told to emit a packet and not yet told to stop.
     *
     * Kept here rather than in the screen that started it because the thing
     * that has to undo it is the disconnect, and a screen that has been left
     * is in no position to do anything about it.
     */
    var streaming: Set<Int> = emptySet()
        private set

    fun nowStreaming(module: Int) { streaming = streaming + module }

    fun stoppedStreaming(module: Int) { streaming = streaming - module }

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

    /**
     * Blocking. Call it from a worker thread; the bridge and the UI both do.
     *
     * A failed reading leaves the last good one on screen but does not pretend
     * the link is fine: if the socket has gone, the state goes with it. The
     * alternative is a voltage frozen at a plausible number next to a status
     * line that says connected, which is worse than an error.
     */
    fun refreshBattery(): Int {
        if (state == State.DISCONNECTED) return 0
        val now = System.currentTimeMillis()
        if (now - voltsAt < 1000) return batteryMillivolts
        voltsAt = now
        val reading = guarded { sm3.voltages() }
        if (reading != null) batteryMillivolts = reading.batteryMillivolts
        return batteryMillivolts
    }
}
