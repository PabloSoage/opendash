package com.varuna.opendash.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Live values out to another machine, and the selection back in.
 *
 * The ELM327 bridge lets another app on the phone ask questions; this is the
 * other direction. Every value the live screen receives is also written, as a
 * line of JSON, to whoever is connected: a head unit, a laptop, a board in the
 * dashboard with its own screen. And that machine can say what it wants to
 * watch. A selection sent here replaces the one on the live screen exactly as a
 * preset does, and if the stream was running it is declared again with the new
 * rows, which takes a couple of seconds -- the time it takes the module to stop
 * its packets, take the new declarations and start again.
 *
 * Nothing here talks to the car. A selection is a list of rows to read, so the
 * worst a client can do is choose badly; the app still only reads.
 *
 * The wire format is in `docs/telemetry.md`. Off by default, because once on
 * it listens on the network interface chosen in the settings.
 */
object Telemetry {

    /** One row the live screen offers, as a client needs to name it. */
    class Row(val key: String, val name: String, val identifier: String, val unit: String)

    /** What a client asked for. Handled by the live screen, on its own thread. */
    sealed class Command {
        /** Watch these rows, in this order; [always] ride in every round. */
        class Select(val keys: List<String>, val always: List<String>, val start: Boolean) : Command()

        /** Apply a stored preset by name. */
        class Preset(val name: String, val start: Boolean) : Command()

        object Start : Command()
        object Stop : Command()
    }

    @Volatile
    private var server: TelemetryServer? = null

    /** Set by the live screen while it is on screen; commands wait for it. */
    @Volatile
    var onCommand: ((Command) -> Unit)? = null

    val isRunning: Boolean get() = server != null
    val port: Int get() = server?.port ?: 0
    val clients: Int get() = server?.clients ?: 0
    val dropped: Long get() = server?.dropped ?: 0

    @Volatile
    private var rowsLine: String? = null

    @Volatile
    private var stateLine: String = TelemetryProtocol.state(false, emptyList(), 0)

    fun start(port: Int, host: String) {
        if (server != null) return
        val s = TelemetryServer(port, host) { line -> handle(line) }
        s.greeting = { listOfNotNull(TelemetryProtocol.hello(), rowsLine, stateLine) }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** The rows the live screen can stream, sent to every client and to each new one. */
    fun offer(module: String, rows: List<Row>) {
        val line = TelemetryProtocol.rows(module, rows)
        rowsLine = line
        server?.broadcast(line)
    }

    /** Running or not, which rows, how many rounds. */
    fun state(running: Boolean, keys: List<String>, rounds: Int) {
        val line = TelemetryProtocol.state(running, keys, rounds)
        stateLine = line
        server?.broadcast(line)
    }

    /** One value, from the reading thread. Cheap when nobody is listening. */
    fun sample(name: String, identifier: String, unit: String, value: Double) {
        val s = server ?: return
        if (s.clients == 0) return
        s.broadcast(TelemetryProtocol.sample(System.currentTimeMillis(), name, identifier, unit, value))
    }

    private fun handle(line: String): String {
        val command = TelemetryProtocol.parse(line)
            ?: return TelemetryProtocol.ack(false, "not understood")
        val sink = onCommand ?: return TelemetryProtocol.ack(false, "the live screen is not open")
        sink(command)
        return TelemetryProtocol.ack(true, "")
    }
}

/**
 * The text of the protocol, apart from any socket, so it tests on a host.
 *
 * Newline-delimited JSON, one object per line. Values are written with a dot
 * and no grouping whatever the phone's locale, because a comma inside a number
 * is a different JSON document.
 */
object TelemetryProtocol {
    const val NAME = "opendash-telemetry/1"

    fun hello(): String = "{\"type\":\"hello\",\"proto\":\"$NAME\"}"

    fun rows(module: String, rows: List<Telemetry.Row>): String {
        val b = StringBuilder("{\"type\":\"rows\",\"module\":").append(quote(module)).append(",\"rows\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) b.append(',')
            b.append("{\"key\":").append(quote(r.key))
                .append(",\"name\":").append(quote(r.name))
                .append(",\"id\":").append(quote(r.identifier))
                .append(",\"unit\":").append(quote(r.unit)).append('}')
        }
        return b.append("]}").toString()
    }

    fun state(running: Boolean, keys: List<String>, rounds: Int): String {
        val b = StringBuilder("{\"type\":\"state\",\"running\":").append(running)
            .append(",\"rounds\":").append(rounds).append(",\"keys\":[")
        keys.forEachIndexed { i, k -> if (i > 0) b.append(','); b.append(quote(k)) }
        return b.append("]}").toString()
    }

    fun sample(ms: Long, name: String, identifier: String, unit: String, value: Double): String =
        "{\"t\":$ms,\"n\":${quote(name)},\"id\":${quote(identifier)},\"u\":${quote(unit)},\"v\":${number(value)}}"

    fun ack(ok: Boolean, why: String): String =
        if (ok) "{\"type\":\"ack\",\"ok\":true}" else "{\"type\":\"ack\",\"ok\":false,\"why\":${quote(why)}}"

    fun number(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> "null"
        v == Math.rint(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> String.format(Locale.ROOT, "%.6g", v).let { s ->
            if ('e' in s || 'E' in s) s else s.trimEnd('0').trimEnd('.')
        }
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /**
     * A command line from a client, or null.
     *
     *     {"select":["key", ...], "always":["key", ...], "start":true}
     *     {"preset":"Astra J A17DTJ", "start":true}
     *     {"start":true}      {"stop":true}
     */
    fun parse(line: String): Telemetry.Command? {
        val obj = try { MiniJson(line).obj() } catch (_: Exception) { return null }
        val start = obj["start"] == true
        @Suppress("UNCHECKED_CAST")
        fun strings(name: String): List<String>? =
            (obj[name] as? List<Any?>)?.let { l -> if (l.all { it is String }) l as List<String> else null }
        return when {
            obj.containsKey("select") -> {
                val keys = strings("select") ?: return null
                val always = if (obj.containsKey("always")) strings("always") ?: return null else emptyList()
                Telemetry.Command.Select(keys, always, start)
            }
            obj["preset"] is String -> Telemetry.Command.Preset(obj["preset"] as String, start)
            obj["stop"] == true -> Telemetry.Command.Stop
            start -> Telemetry.Command.Start
            else -> null
        }
    }

    /** Just enough JSON for the commands: objects, arrays, strings, numbers, booleans, null. */
    private class MiniJson(val s: String) {
        var i = 0

        fun obj(): Map<String, Any?> {
            val v = value()
            skip()
            require(i == s.length)
            @Suppress("UNCHECKED_CAST")
            return v as Map<String, Any?>
        }

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skip()
            require(i < s.length)
            return when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    skip()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        skip()
                        val k = string()
                        skip(); require(s[i] == ':'); i++
                        m[k] = value()
                        skip()
                        if (s[i] == ',') { i++; continue }
                        require(s[i] == '}'); i++
                        return m
                    }
                    @Suppress("UNREACHABLE_CODE") m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    skip()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(value())
                        skip()
                        if (s[i] == ',') { i++; continue }
                        require(s[i] == ']'); i++
                        return l
                    }
                    @Suppress("UNREACHABLE_CODE") l
                }
                '"' -> string()
                't' -> { require(s.startsWith("true", i)); i += 4; true }
                'f' -> { require(s.startsWith("false", i)); i += 5; false }
                'n' -> { require(s.startsWith("null", i)); i += 4; null }
                else -> {
                    val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    require(i > st)
                    s.substring(st, i).toDouble()
                }
            }
        }

        fun string(): String {
            require(s[i] == '"'); i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}

/**
 * The socket. One writer thread per client with a bounded queue, so a client
 * that reads slowly loses lines -- counted in [dropped] -- instead of holding
 * up the thread that reads the car.
 */
class TelemetryServer(
    val requestedPort: Int,
    private val host: String,
    private val onLine: (String) -> String,
) {
    private class Client(val socket: Socket) {
        val queue = LinkedBlockingQueue<String>(QUEUE)
    }

    @Volatile
    private var server: ServerSocket? = null
    private val list = CopyOnWriteArrayList<Client>()
    private val lost = AtomicLong()

    /** Lines a new client gets before anything else. */
    @Volatile
    var greeting: () -> List<String> = { emptyList() }

    val port: Int get() = server?.localPort ?: 0
    val clients: Int get() = list.size
    val dropped: Long get() = lost.get()

    fun start() {
        if (server != null) return
        val s = ServerSocket(requestedPort, 4, InetAddress.getByName(host))
        server = s
        thread(name = "telemetry-accept", isDaemon = true) {
            while (true) {
                val socket = try { s.accept() } catch (_: Exception) { return@thread }
                socket.tcpNoDelay = true
                val c = Client(socket)
                for (line in greeting()) c.queue.offer(line)
                list.add(c)
                thread(name = "telemetry-write", isDaemon = true) { write(c) }
                thread(name = "telemetry-read", isDaemon = true) { read(c) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
        for (c in list) close(c)
    }

    fun broadcast(line: String) {
        for (c in list) {
            if (!c.queue.offer(line)) {
                // Full: drop the oldest, keep the newest. A display wants now.
                c.queue.poll()
                lost.incrementAndGet()
                c.queue.offer(line)
            }
        }
    }

    private fun write(c: Client) {
        try {
            val out = c.socket.getOutputStream().buffered(1 shl 16)
            while (true) {
                var line: String? = c.queue.take()
                // Everything already queued goes out in one flush.
                while (line != null) {
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    line = c.queue.poll()
                }
                out.flush()
            }
        } catch (_: Exception) {
            close(c)
        }
    }

    private fun read(c: Client) {
        try {
            val reader = BufferedReader(InputStreamReader(c.socket.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_LINE) break
                if (line.isBlank()) continue
                val answer = try { onLine(line) } catch (e: Exception) {
                    TelemetryProtocol.ack(false, e.message ?: e.javaClass.simpleName)
                }
                c.queue.offer(answer)
            }
        } catch (_: Exception) {
        }
        close(c)
    }

    private fun close(c: Client) {
        list.remove(c)
        try { c.socket.close() } catch (_: Exception) {}
    }

    private companion object {
        const val QUEUE = 16384
        const val MAX_LINE = 1 shl 16
    }
}
