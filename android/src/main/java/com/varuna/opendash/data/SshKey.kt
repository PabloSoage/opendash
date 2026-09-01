package com.varuna.opendash.data

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.io.File

/**
 * An SSH identity kept on the device, for sources reached with a key rather
 * than a token.
 *
 * Two ways in. Generate one here — an RSA-3072 pair, because every Android
 * version can make one without a third-party provider and every SSH server and
 * forge still takes one — or import a private key that already exists, in
 * whatever format it came in; sshj parses OpenSSH and PEM alike, so nothing
 * needs re-encoding first.
 *
 * The private key never leaves this directory, which is app-private storage:
 * other apps cannot read it, and it goes away when the app is uninstalled.
 * What gets copied out is the public half, to be pasted into an
 * `authorized_keys` file or added to a forge as a deploy key.
 *
 * ## What this does and does not reach
 *
 * A key opens SSH hosts: a home server, a NAS, another machine, anything that
 * speaks SFTP. It does not, on its own, open a GitHub or GitLab repository,
 * because those serve files over SSH only through the git wire protocol —
 * which means a git client, a packfile reader, and downloading the whole
 * history to get four text files. For those forges a read-scoped token over
 * HTTPS fetches exactly the files wanted and is revoked from a web page. Both
 * paths are offered; neither is a substitute for the other.
 */
class SshKey(context: Context) {

    private val directory = File(context.filesDir, "ssh").also { it.mkdirs() }
    private val privateFile = File(directory, "id")
    private val publicFile = File(directory, "id.pub")

    val exists: Boolean get() = privateFile.exists()

    /** The private key as text, for handing to sshj. Null when there is none. */
    fun privateKeyText(): String? = privateFile.takeIf { it.exists() }?.readText()

    /** The `ssh-rsa AAAA…` line, ready to paste. Null when there is none. */
    fun publicKeyLine(): String? = publicFile.takeIf { it.exists() }?.readText()?.trim()

    /** `SHA256:…`, the form ssh-keygen and every forge shows. */
    fun fingerprint(): String? {
        val line = publicKeyLine() ?: return null
        val blob = runCatching { Base64.decode(line.split(' ')[1], Base64.NO_WRAP) }
            .getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Make a new pair, replacing whatever was there.
     *
     * The private half is written PKCS#8 in PEM armour, which sshj reads and
     * OpenSSH accepts; the public half in the one-line format `authorized_keys`
     * wants.
     */
    fun generate(comment: String = "opendash"): String {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val pair = generator.generateKeyPair()

        privateFile.writeText(pem(pair.private.encoded))
        privateFile.setReadable(false, false)
        privateFile.setReadable(true, true)

        val line = openSshLine(pair.public as RSAPublicKey, comment)
        publicFile.writeText(line)
        return line
    }

    /**
     * Take a key that already exists. The text is stored as given: sshj sorts
     * out the format at connect time, and re-encoding it here would be one more
     * place to get it wrong.
     *
     * The public half is derived when the key is a PKCS#8 RSA one, which is
     * what most exports are. When it is not — an OpenSSH ed25519 key, say —
     * the private key is still usable; only the display of the public line is
     * skipped, and that line can be taken from wherever the key came from.
     */
    fun import(text: String, comment: String = "opendash"): Result<Unit> = runCatching {
        require(text.contains("PRIVATE KEY")) { "that does not look like a private key" }
        privateFile.writeText(text.trim() + "\n")
        privateFile.setReadable(false, false)
        privateFile.setReadable(true, true)
        publicFile.delete()
        derivePublic(text)?.let { publicFile.writeText(openSshLine(it, comment)) }
    }

    fun delete() {
        privateFile.delete()
        publicFile.delete()
    }

    // ── encoding ──────────────────────────────────────────────────────────

    private fun derivePublic(text: String): RSAPublicKey? = runCatching {
        val body = text
            .replace(Regex("-----[A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        val spec = PKCS8EncodedKeySpec(Base64.decode(body, Base64.DEFAULT))
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(spec) as java.security.interfaces.RSAPrivateCrtKey
        factory.generatePublic(
            RSAPublicKeySpec(private.modulus, private.publicExponent)
        ) as RSAPublicKey
    }.getOrNull()

    private fun pem(der: ByteArray): String {
        val body = Base64.encodeToString(der, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            body.chunked(64).forEach { append(it).append('\n') }
            append("-----END PRIVATE KEY-----\n")
        }
    }

    /**
     * `ssh-rsa <base64> <comment>`. The blob inside is the SSH wire encoding:
     * the algorithm name, the public exponent and the modulus, each as a
     * length-prefixed big-endian integer. [BigInteger.toByteArray] already
     * produces the two's-complement form with the leading zero byte the format
     * requires, so there is nothing to pad by hand.
     */
    private fun openSshLine(key: RSAPublicKey, comment: String): String {
        val out = ByteArrayOutputStream()
        fun field(bytes: ByteArray) {
            out.write(bytes.size ushr 24)
            out.write(bytes.size ushr 16 and 0xff)
            out.write(bytes.size ushr 8 and 0xff)
            out.write(bytes.size and 0xff)
            out.write(bytes)
        }
        field("ssh-rsa".toByteArray(Charsets.US_ASCII))
        field(key.publicExponent.toByteArray())
        field(key.modulus.toByteArray())
        return "ssh-rsa " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) + " " + comment
    }
}
