package com.varuna.opendash.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs catalogues from the configured sources and keeps them on disk.
 *
 * A catalogue is a handful of text files, so they are fetched one by one rather
 * than as an archive: no unpacking, and a partial failure names the file that
 * failed instead of leaving a half-written directory.
 *
 * Downloads land in a staging directory and move into place only once every
 * required file has arrived, so a dropped connection cannot leave half a
 * catalogue that then reads as a whole one.
 */
class PluginRepository(context: Context) {

    private val root = File(context.filesDir, "plugins")
    private val prefs = context.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    val key = SshKey(context)
    private val sftp = SftpFetcher(key, File(context.filesDir, "ssh"))

    /**
     * Observable, so the screen that adds a source redraws without being left
     * and re-entered. The preference remains the durable copy.
     */
    var sources by mutableStateOf(
        PluginSource.listFromJson(prefs.getString(KEY_SOURCES, "[]") ?: "[]")
    )
        private set

    /** Bumped when something is installed or removed. */
    var revision by mutableStateOf(0)
        private set

    private fun persist(value: List<PluginSource>) {
        sources = value
        prefs.edit().putString(KEY_SOURCES, PluginSource.listToJson(value)).apply()
    }

    fun addSource(source: PluginSource) {
        persist(sources.filterNot { it.id == source.id } + source)
    }

    fun removeSource(id: String) {
        persist(sources.filterNot { it.id == id })
    }

    /** Catalogues already on disk. */
    fun installed(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun load(brand: String, language: String): Catalogue? =
        Catalogue.load(File(root, brand), language)

    fun remove(brand: String) {
        File(root, brand).deleteRecursively()
        revision++
    }

    fun forgetSshHosts() = sftp.forgetHosts()

    /**
     * Fetch one catalogue from [source]. [onProgress] reports each file as it
     * starts, because a parameter table over a phone connection takes long
     * enough that silence looks like a hang.
     */
    fun install(
        source: PluginSource,
        brand: String,
        language: String = "en",
        onProgress: (String) -> Unit = {},
    ): Result<Int> = runCatching {
        root.mkdirs()
        val staging = File(root, ".staging-" + brand)
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val suffix = if (language == "en") "" else "." + language
            val required = listOf("plugin.json", "parameters$suffix.tsv")
            val optional = listOf("variants$suffix.tsv", "modules.tsv", "dtc.tsv")

            val fetched = fetchAll(source, brand, required + optional, onProgress)
            for (name in required) {
                val bytes = fetched[name] ?: error("the source has no $name for $brand")
                File(staging, name).writeBytes(bytes)
            }
            for (name in optional) {
                fetched[name]?.let { File(staging, name).writeBytes(it) }
            }

            val target = File(root, brand)
            target.deleteRecursively()
            if (!staging.renameTo(target)) error("could not install into " + target.name)
            revision++
            Catalogue.load(target, language)?.parameters?.size ?: 0
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Which catalogues a source offers, from its own index if it publishes one. */
    fun discover(source: PluginSource): Result<List<String>> = runCatching {
        val raw = fetchAll(source, "", listOf("brands.txt")) {}["brands.txt"]
            ?: error("this source publishes no brands.txt, so its contents cannot be listed")
        String(raw, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * One round trip per file over HTTPS; one connection for the lot over SSH,
     * where the handshake costs far more than the transfers.
     */
    private fun fetchAll(
        source: PluginSource,
        brand: String,
        names: List<String>,
        onProgress: (String) -> Unit,
    ): Map<String, ByteArray?> {
        val prefix = if (brand.isEmpty()) "" else "$brand/"
        if (source.kind == PluginSource.Kind.SSH) {
            onProgress(names.first())
            val byPath = sftp.fetch(source.location, names.map { prefix + it })
            return names.associateWith { byPath[prefix + it] }
        }
        return names.associateWith { name ->
            onProgress(name)
            fetchOverHttps(source, prefix + name)
        }
    }

    private fun fetchOverHttps(source: PluginSource, path: String): ByteArray? {
        val connection = URL(source.urlFor(path)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            for ((k, v) in source.headers()) connection.setRequestProperty(k, v)
            val code = connection.responseCode
            return when {
                code in 200..299 -> connection.inputStream.readBytes()
                code == 404 -> null
                code == 401 || code == 403 ->
                    error("the source refused the credential (HTTP $code)")
                else -> error("HTTP $code fetching $path")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_SOURCES = "plugin_sources"
    }
}
