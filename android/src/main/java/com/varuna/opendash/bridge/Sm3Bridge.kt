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
            // The bridge reports the responding module as the engine unless the
            // request was addressed elsewhere, which is what apps expect to see.
            val id = if (header in 0x7E0..0x7E7) header + 8 else 0x7E8
            return id to answer
        }
    }
}
