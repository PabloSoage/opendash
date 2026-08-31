package es.opendash.bridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The socket half of the ELM327 emulation.
 *
 * Phone apps open a TCP connection and speak lines terminated by a carriage
 * return, expecting a `>` prompt after every answer. That framing is the whole
 * contract; the command handling itself lives in the core.
 *
 * Binds to the loopback by default. An app on the same phone reaches it, and
 * nothing on the network does.
 */
class ElmServer(
    private val bridge: Bridge,
    val port: Int = 35000,
    private val host: String = "127.0.0.1",
) {
    @Volatile
    private var server: ServerSocket? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) return
        val s = ServerSocket(port, 4, InetAddress.getByName(host))
        server = s
        thread(name = "elm-accept", isDaemon = true) {
            while (true) {
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    return@thread          // closed
                }
                thread(name = "elm-session", isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun serve(client: Socket) {
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII))
            val out = it.getOutputStream()
            val session = ElmSession(bridge)
            prompt(out)
            while (true) {
                val line = readCommand(reader) ?: return
                val answer = session.handle(line)
                out.write(answer.toByteArray(Charsets.US_ASCII))
                out.write("\r".toByteArray())
                prompt(out)
            }
        }
    }

    /** ELM327 lines end with a carriage return, not a newline. */
    private fun readCommand(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return null
            if (c == '\r'.code) return sb.toString()
            if (c != '\n'.code) sb.append(c.toChar())
            if (sb.length > 256) return sb.toString()   // no runaway lines
        }
    }

    private fun prompt(out: OutputStream) {
        out.write(">".toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
