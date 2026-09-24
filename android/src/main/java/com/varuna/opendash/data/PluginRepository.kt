package com.varuna.opendash.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
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

    /**
     * How many parameters an installed catalogue has, from its `plugin.json`.
     *
     * Reading the count out of the manifest rather than loading the tables: the
     * Opel one is 21 382 rows and a megabyte and a half of text, and a list
     * that shows five catalogues would parse all of them on every redraw.
     */
    fun size(brand: String): Int = runCatching {
        JSONObject(File(File(root, brand), "plugin.json").readText()).optInt("parameters", 0)
    }.getOrDefault(0)

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
            // `profiles.txt` is optional and small: named selections the
            // catalogue publishes ready-made. Choosing what to watch is the
            // slow part of using this — hundreds of rows, and the dozen worth
            // reading together are always the same dozen — so a catalogue that
            // ships them saves whoever installs it the first half hour.
            // `vehicles.txt` is optional too, and it is what makes the live
            // screen work on a sofa: what one car actually answered, so the
            // 21 382 rows of a marque can be cut down to the few hundred this
            // engine has without the engine being present.
            val optional = listOf(
                "variants$suffix.tsv", "modules.tsv", "dtc.tsv",
                "profiles.txt", "vehicles.txt", "actuators.tsv",
            )

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

    /** The profiles an installed catalogue publishes, as raw text. */
    fun profilesOf(brand: String): String? =
        File(File(root, brand), "profiles.txt").takeIf { it.isFile }?.readText()

    /** What a catalogue says its modules can be commanded to do. See Actuators. */
    fun actuatorsOf(brand: String): String? =
        File(File(root, brand), "actuators.tsv").takeIf { it.isFile }?.readText()

    /** The known vehicles an installed catalogue publishes, as raw text. */
    fun vehiclesOf(brand: String): String? =
        File(File(root, brand), "vehicles.txt").takeIf { it.isFile }?.readText()

    /**
     * The revision of an installed catalogue, from its own `plugin.json`.
     *
     * Null when it is not installed, or when it predates revisions — in which
     * case there is nothing to compare and the honest answer is not "up to
     * date" but "unknown".
     */
    fun installedRevision(brand: String): String? =
        runCatching {
            val text = File(File(root, brand), "plugin.json").readText()
            Regex("\"revision\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        }.getOrNull()

    /** One line of a source's index. */
    class Listing(
        val name: String,
        val parameters: Int,
        val languages: List<String>,
        /** Moves when the catalogue's contents do. Empty when the source has none. */
        val revision: String = "",
    )

    /**
     * What a source offers, from the index it publishes.
     *
     * `brands.txt`, tab-separated: name, parameter count, languages, revision.
     * Everything after the name is optional — a source that lists bare names
     * still works, it just cannot say how big anything is until it is
     * installed, nor whether what is installed is stale.
     *
     * The revision is what makes refresh mean something. Without it the index
     * says what a source holds and nothing about whether it changed, so
     * pressing refresh re-read the same list and reported no news — which is
     * what happened the first time a catalogue grew a profile.
     */
    fun discover(source: PluginSource): Result<List<Listing>> = runCatching {
        val raw = fetchAll(source, "", listOf("brands.txt")) {}["brands.txt"]
            ?: error("this source publishes no brands.txt, so its contents cannot be listed")
        String(raw, Charsets.UTF_8).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split('\t')
                Listing(
                    name = fields[0].trim(),
                    parameters = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                    languages = fields.getOrNull(2)?.split(',')?.map { it.trim() }
                        ?.filter { it.isNotEmpty() } ?: emptyList(),
                    revision = fields.getOrNull(3)?.trim().orEmpty(),
                )
            }
            .filter { it.name.isNotEmpty() }
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
