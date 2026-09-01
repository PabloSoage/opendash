package com.varuna.opendash.data

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File

/**
 * Fetches catalogue files from a host reached over SSH.
 *
 * One connection per install rather than one per file: the handshake is the
 * expensive part, and a catalogue is four small files.
 *
 * ## Host keys
 *
 * The host key is recorded the first time and checked on every connection
 * after that — trust on first use, the same bargain `ssh` itself offers when
 * it asks whether to continue connecting. A changed key fails the connection
 * rather than asking, because the answer on a phone in a car park is always
 * going to be yes and that defeats the point. The recorded key can be cleared
 * from the catalogue screen, which is the deliberate act that accepting a new
 * one should be.
 */
class SftpFetcher(private val key: SshKey, knownHostsDirectory: File) {

    private val knownHosts = File(knownHostsDirectory, "known_hosts").also {
        it.parentFile?.mkdirs()
        if (!it.exists()) it.createNewFile()
    }

    class Target(val user: String, val host: String, val port: Int, val path: String)

    /** `user@host:/path`, `user@host:2222:/path`, or a bare host with a path. */
    fun parse(location: String): Target {
        val at = location.indexOf('@')
        val user = if (at > 0) location.substring(0, at) else "git"
        val rest = location.substring(at + 1)
        val colon = rest.indexOf(':')
        require(colon > 0) { "expected user@host:/path" }
        val host = rest.substring(0, colon)
        val tail = rest.substring(colon + 1)
        val second = tail.indexOf(':')
        return if (second > 0 && tail.substring(0, second).toIntOrNull() != null) {
            Target(user, host, tail.substring(0, second).toInt(), tail.substring(second + 1))
        } else {
            Target(user, host, 22, tail)
        }
    }

    /**
     * Read the named files under the source's path. A file that is not there
     * comes back as null rather than as a failure, because some of them are
     * optional.
     */
    fun fetch(location: String, paths: List<String>): Map<String, ByteArray?> {
        val target = parse(location)
        val privateKey = key.privateKeyText()
            ?: error("no SSH key on this device: generate or import one first")

        val client = SSHClient()
        try {
            client.addHostKeyVerifier(verifier())
            client.connect(target.host, target.port)
            client.authPublickey(
                target.user,
                client.loadKeys(privateKey, key.publicKeyLine(), null),
            )
            client.newSFTPClient().use { sftp ->
                return paths.associateWith { name ->
                    read(sftp, target.path.trimEnd('/') + "/" + name)
                }
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun read(sftp: SFTPClient, path: String): ByteArray? = try {
        sftp.open(path).use { file ->
            val size = file.length().toInt()
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = file.read(read.toLong(), bytes, read, size - read)
                if (n <= 0) break
                read += n
            }
            if (read == size) bytes else bytes.copyOf(read)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Trust on first use, backed by the same `known_hosts` file format ssh
     * uses. sshj's own verifier does exactly this: unknown hosts are recorded,
     * changed keys are refused.
     */
    private fun verifier() =
        net.schmizz.sshj.transport.verification.OpenSSHKnownHosts(knownHosts)

    /** Forget every recorded host key, so the next connection records afresh. */
    fun forgetHosts() {
        knownHosts.writeText("")
    }
}
