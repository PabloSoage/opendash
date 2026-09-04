package com.varuna.opendash.bridge

import com.varuna.opendash.Session
import com.varuna.opendash.obd.Diagnostics

/**
 * The ELM327 server talking to the real device.
 *
 * A phone app sends a request and expects an answer or nothing; this maps that
 * onto the session, which handles the segmentation. Requests are serialised
 * because there is one socket and one bus, and two overlapping requests would
 * interleave their replies.
 */
class Sm3Bridge : Bridge {

    private val lock = Any()

    override val batteryMillivolts: Int get() = Session.refreshBattery()

    override fun request(header: Int, payload: ByteArray, timeoutMs: Long): Pair<Int, ByteArray>? {
        if (Session.state != Session.State.CHANNEL_OPEN) return null
        synchronized(lock) {
            val answer = Session.diagnostics.request(header, payload, timeoutMs, Diagnostics.ANY)
                ?: return null
            // Which module the client is told answered. A request addressed to
            // the body module comes back from the body module: 0x241 answers on
            // 0x641, not on 0x7E8, and a client that set its receive filter to
            // match would throw away an answer labelled with the wrong id.
            val id = Diagnostics.responseIdFor(header)
            return (if (id == Diagnostics.ANY) 0x7E8 else id) to answer
        }
    }
}
