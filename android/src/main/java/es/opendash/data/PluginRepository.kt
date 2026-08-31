package es.opendash.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs catalogues from the configured sources and keeps them on disk.
 *
 * A brand is four files, so this fetches them one by one rather than pulling a
 * tarball: no archive handling, and a partial failure names the file that
 * failed instead of leaving an unpacked mess.
 *
 * Downloads land in a staging directory and move into place only once every
 * required file has arrived, so a dropped connection cannot leave half a
 * catalogue that then reads as a whole one.
 */
class PluginRepository(context: Context) {

    private val root = File(context.filesDir, "plugins")
    private val prefs = context.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    var sources: List<PluginSource>
        get() = PluginSource.listFromJson(prefs.getString(KEY_SOURCES, "[]") ?: "[]")
        set(value) {
            prefs.edit().putString(KEY_SOURCES, PluginSource.listToJson(value)).apply()
        }

    fun addSource(source: PluginSource) {
        sources = sources.filterNot { it.id == source.id } + source
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
    }

    /** Brands already on disk. */
    fun installed(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun load(brand: String, language: String): Catalogue? =
        Catalogue.load(File(root, brand), language)

    fun remove(brand: String) {
        File(root, brand).deleteRecursively()
    }

    /**
     * Fetch one brand from [source]. [onProgress] reports each file as it
     * starts, because a 1.5 MB parameter table over a phone connection takes
     * long enough that silence looks like a hang.
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
            val wanted = listOf(
                "plugin.json" to true,
                "parameters" + suffix + ".tsv" to true,
                "variants" + suffix + ".tsv" to false,
                "modules.tsv" to false,
            )
            for ((name, required) in wanted) {
                onProgress(name)
                val bytes = fetch(source, brand + "/" + name)
                if (bytes == null) {
                    if (required) error("the source has no " + name + " for " + brand)
                    continue
                }
                File(staging, name).writeBytes(bytes)
            }
            val target = File(root, brand)
            target.deleteRecursively()
            if (!staging.renameTo(target)) error("could not install into " + target.name)
            Catalogue.load(target, language)?.parameters?.size ?: 0
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Which brands a source offers, from its own index if it publishes one. */
    fun discover(source: PluginSource): Result<List<String>> = runCatching {
        val raw = fetch(source, "brands.txt")
            ?: error("this source publishes no brands.txt, so its contents cannot be listed")
        String(raw, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun fetch(source: PluginSource, path: String): ByteArray? {
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
                    error("the source refused the credential (HTTP " + code + ")")
                else -> error("HTTP " + code + " fetching " + path)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_SOURCES = "plugin_sources"
    }
}
