package es.opendash.bridge

/**
 * What the ELM327 server needs from whatever is underneath it.
 *
 * Keeping this an interface means the socket half can be exercised without a
 * device: [LoopbackBridge] answers plausibly and lets the app be driven by a
 * real phone app on a desk. The real one talks to the SM3.
 */
interface Bridge {
    /** Millivolts, for `ATRV`. Zero when unknown. */
    val batteryMillivolts: Int

    /**
     * Put a request on the bus and wait for the reply, already reassembled.
     * Returns null on timeout, which the server turns into `NO DATA`.
     */
    fun request(header: Int, payload: ByteArray, timeoutMs: Long): Pair<Int, ByteArray>?
}

/**
 * A stand-in that answers nothing. Useful to bring the app up and point a phone
 * app at it before the native side is wired: the AT handshake completes and the
 * app reports "no data" rather than failing to connect, which is a much easier
 * thing to debug.
 */
class LoopbackBridge : Bridge {
    override val batteryMillivolts = 0
    override fun request(header: Int, payload: ByteArray, timeoutMs: Long) = null
}
