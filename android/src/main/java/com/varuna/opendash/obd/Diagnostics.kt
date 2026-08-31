package com.varuna.opendash.obd

import com.varuna.opendash.protocol.IsoTp
import com.varuna.opendash.protocol.Sm3Client

/**
 * Request and response over the bus, with the segmentation handled.
 *
 * Everything here is a read. Nothing writes to a module, clears a code or runs
 * a routine, and that is deliberate: this is a tool for looking at a car, and
 * the failure modes of the other kind are expensive.
 */
class Diagnostics(private val sm3: Sm3Client) {

    private val isotp = IsoTp()

    /**
     * Send [payload] to [txId] and wait for the answer from the matching
     * response id. Returns null on timeout.
     */
    /**
     * Services this tool is allowed to emit. All of them read.
     *
     * This is a hard stop, not a convention. The catalogue extracted from GDS2
     * lists 2517 parameters with "Command" in the name, 879 with "Test" and 782
     * with "Learn": in the factory tool those can be commanded as well as read,
     * and the same identifier that reports a relay state can also close it.
     * Reading them is harmless; writing them moves things on a car with someone
     * in it. So the write services never leave here.
     *
     * 0x2E WriteDataByIdentifier, 0x2F InputOutputControl, 0x31 RoutineControl,
     * 0x14 ClearDiagnosticInformation and 0x27 SecurityAccess are absent on
     * purpose. Adding one is a decision, not a patch.
     */
    private val readServices = setOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x19, 0x1A, 0x22, 0xAA, 0x3E)

    private fun refuseIfNotRead(payload: ByteArray) {
        val service = payload.firstOrNull()?.toInt()?.and(0xff) ?: return
        // A flow-control frame is transport, not a service.
        if (service and 0xf0 == 0x30) return
        require(service in readServices) {
            "refusing to send service 0x" + service.toString(16) + ": this tool only reads"
        }
    }

    fun request(
        txId: Int,
        payload: ByteArray,
        timeoutMs: Long = 1500,
        rxId: Int = responseIdFor(txId),
    ): ByteArray? {
        refuseIfNotRead(payload)
        isotp.reset()
        sm3.drain()
        sm3.send(txId, payload)

        val deadline = System.currentTimeMillis() + timeoutMs
        var flowControlSent = false
        while (System.currentTimeMillis() < deadline) {
            sm3.poll()
            for (frame in sm3.drain()) {
                if (frame.id != rxId && rxId != ANY) continue
                val pci = (frame.data.getOrNull(0)?.toInt() ?: 0) and 0xf0
                val done = isotp.push(frame.id, frame.data)
                if (done != null) return done
                // A multi-frame answer stalls until the tester says go ahead.
                if (pci == 0x10 && !flowControlSent) {
                    sm3.send(txId, IsoTp.FLOW_CONTROL)
                    flowControlSent = true
                }
            }
            Thread.sleep(10)
        }
        return null
    }

    /** Mode 01. Returns the data after the echoed mode and pid. */
    fun mode01(pid: Int, txId: Int = FUNCTIONAL): ByteArray? {
        val r = request(txId, byteArrayOf(0x01, pid.toByte()), rxId = ANY) ?: return null
        if (r.size < 2 || (r[0].toInt() and 0xff) != 0x41) return null
        return r.copyOfRange(2, r.size)
    }

    /** The supported-PID bitmasks, four requests instead of ninety-six. */
    fun supportedPids(): Set<Int> {
        val masks = HashMap<Int, ByteArray>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60)) {
            val m = mode01(base) ?: break
            masks[base] = m
            // bit 0 of the last byte says whether the next block exists
            if (m.size < 4 || (m[3].toInt() and 0x01) == 0) break
        }
        return Pids.supported(masks)
    }

    fun readiness(): Readiness? = mode01(0x01)?.let { Readiness(it) }

    /**
     * Stored fault codes, mode 03. Read only — clearing them is service 0x14,
     * which this tool does not emit.
     *
     * Answered functionally, so every module that has something to say does,
     * and the same code can arrive from more than one of them.
     */
    fun storedFaults(): List<Dtc> = faults(0x03, pending = false)

    /**
     * Pending codes, mode 07: seen once but not yet often enough to light the
     * lamp. Worth reading precisely because they do not show anywhere else.
     */
    fun pendingFaults(): List<Dtc> = faults(0x07, pending = true)

    private fun faults(mode: Int, pending: Boolean): List<Dtc> {
        val r = request(FUNCTIONAL, byteArrayOf(mode.toByte()), rxId = ANY) ?: return emptyList()
        // 43 <count> <pairs...> on a segmented answer, 43 <pairs...> on a short one.
        if (r.isEmpty() || (r[0].toInt() and 0xff) != mode + 0x40) return emptyList()
        val body = if (r.size > 1 && r.size % 2 == 0) r.copyOfRange(2, r.size)
        else r.copyOfRange(1, r.size)
        return decodeDtcs(body, pending)
    }

    /** Mode 09 PID 02: the VIN, as the engine reports it. */
    fun vin(): String? {
        val r = request(FUNCTIONAL, byteArrayOf(0x09, 0x02), rxId = ANY) ?: return null
        if (r.size < 3) return null
        // 49 02 <count> then the characters
        return String(r.copyOfRange(3, r.size), Charsets.US_ASCII).trim { it <= ' ' }
    }

    /**
     * GM service 0x1A: how the car says what it is. This is what GDS2 asks,
     * and it beats any make-and-model menu — it works on a swapped engine.
     */
    fun identify(txId: Int = ENGINE): VehicleId {
        fun read(localId: Int): String? {
            val r = request(txId, byteArrayOf(0x1A, localId.toByte())) ?: return null
            if (r.size < 3 || (r[0].toInt() and 0xff) != 0x5A) return null
            return String(r.copyOfRange(2, r.size), Charsets.US_ASCII).trim { it <= ' ' }
        }
        return VehicleId(
            vin = read(0x90),
            system = read(0x92),
            engine = read(0x97),
            calibration = read(0x98),
            moduleSerial = read(0xB4),
        )
    }

    class VehicleId(
        val vin: String?,
        val system: String?,
        val engine: String?,
        val calibration: String?,
        val moduleSerial: String?,
    ) {
        val known: Boolean get() = !vin.isNullOrBlank() || !engine.isNullOrBlank()
    }

    companion object {
        /** OBD functional request: every module that listens answers. */
        const val FUNCTIONAL = 0x7DF
        /** The engine, addressed directly. */
        const val ENGINE = 0x7E0
        /** Accept whichever module replies. */
        const val ANY = -1

        /** GM keeps request and response 0x400 apart; OBD uses 0x7E0/0x7E8. */
        fun responseIdFor(txId: Int): Int = when {
            txId == FUNCTIONAL -> ANY
            txId in 0x7E0..0x7E7 -> txId + 8
            txId in 0x240..0x25F -> txId + 0x400
            else -> ANY
        }
    }
}
