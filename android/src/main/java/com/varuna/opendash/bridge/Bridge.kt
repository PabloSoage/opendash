package com.varuna.opendash.bridge

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

    /**
     * Put one whole CAN frame on the bus, protocol byte included, and hand back
     * every frame that [accepts] lets through, as it came off the bus.
     *
     * This is `AT CAF0`: the client does its own ISO-TP and the adapter only
     * carries frames. Collection stops once something has arrived and then
     * nothing more has for [silenceMs], or at [timeoutMs] if nothing arrives at
     * all. When a first frame (`1x`) arrives, [flowControl] says where to send
     * the "go ahead" and what it carries, or null to leave it to the client.
     */
    fun raw(
        header: Int,
        frame: ByteArray,
        accepts: (Int) -> Boolean,
        flowControl: (Int) -> Pair<Int, ByteArray>?,
        silenceMs: Long,
        timeoutMs: Long,
    ): List<Pair<Int, ByteArray>>
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
    override fun raw(
        header: Int,
        frame: ByteArray,
        accepts: (Int) -> Boolean,
        flowControl: (Int) -> Pair<Int, ByteArray>?,
        silenceMs: Long,
        timeoutMs: Long,
    ): List<Pair<Int, ByteArray>> = emptyList()
}
