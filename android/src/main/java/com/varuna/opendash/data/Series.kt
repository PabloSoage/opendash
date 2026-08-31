package com.varuna.opendash.data

/**
 * A bounded window of recent readings, for charting.
 *
 * A ring buffer rather than a growing list: a session runs for an hour and the
 * chart only ever shows the last few hundred points, so keeping the rest costs
 * memory and buys nothing. The recording on disk is the archive.
 *
 * Not synchronised. The poller writes and the UI reads, both on their own
 * threads, and a torn read costs one frame of one chart — which is cheaper than
 * a lock on every sample.
 */
class Series(val capacity: Int = 300) {

    private val values = DoubleArray(capacity)
    private var head = 0

    var size = 0
        private set

    var last: Double = 0.0
        private set

    var min: Double = Double.MAX_VALUE
        private set

    var max: Double = -Double.MAX_VALUE
        private set

    fun add(value: Double) {
        values[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
        last = value
        if (value < min) min = value
        if (value > max) max = value
    }

    /** Oldest to newest, for drawing. */
    fun snapshot(): DoubleArray {
        val out = DoubleArray(size)
        val start = if (size < capacity) 0 else head
        for (i in 0 until size) out[i] = values[(start + i) % capacity]
        return out
    }

    fun clear() {
        size = 0
        head = 0
        last = 0.0
        min = Double.MAX_VALUE
        max = -Double.MAX_VALUE
    }
}
