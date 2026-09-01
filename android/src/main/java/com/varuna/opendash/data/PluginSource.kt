package com.varuna.opendash.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Where catalogues come from. Several can be configured at once — a shared
 * public one and a private repository of your own, say — and each keeps its
 * own credential.
 *
 * ## Tokens and keys
 *
 * Two credentials, because the two kinds of host want different things.
 *
 * A forge — GitHub, GitLab, Gitea — serves individual files over HTTPS with a
 * token in a header. That token can be scoped to read one repository and
 * revoked from a web page, and fetching a catalogue costs four small requests.
 * Reaching the same repository over SSH would mean the git wire protocol: a
 * packfile reader and the whole history downloaded to end up with those same
 * four files. So forges use a token.
 *
 * Any other SSH host — a home server, a NAS, another machine, a repository
 * someone else exports for you — is reached with a key over SFTP, which is
 * what [Kind.SSH] is for. See [SshKey].
 */
data class PluginSource(
    val id: String,
    val name: String,
    val kind: Kind,
    /** owner/repo for a forge, or a base URL for [Kind.HTTPS]. */
    val location: String,
    val ref: String = "main",
    /** Empty for a public repository. */
    val token: String = "",
) {
    enum class Kind { GITHUB, GITLAB, GITEA, HTTPS, SSH }

    val isPrivate: Boolean get() = token.isNotEmpty() || kind == Kind.SSH

    /** URL for one file inside the source. */
    fun urlFor(path: String): String = when (kind) {
        Kind.GITHUB ->
            "https://api.github.com/repos/" + location + "/contents/" + path + "?ref=" + ref
        Kind.GITLAB ->
            "https://gitlab.com/api/v4/projects/" + URLEncoder.encode(location, "UTF-8") +
                "/repository/files/" + URLEncoder.encode(path, "UTF-8") + "/raw?ref=" + ref
        Kind.GITEA -> location + "/raw/branch/" + ref + "/" + path
        Kind.HTTPS -> location.trimEnd('/') + "/" + path
        // Reached over SFTP; the path is joined by the fetcher, not here.
        Kind.SSH -> location
    }

    /** The header this forge wants, if any. */
    fun headers(): Map<String, String> = buildMap {
        when (kind) {
            Kind.GITHUB -> {
                put("Accept", "application/vnd.github.raw")
                if (token.isNotEmpty()) put("Authorization", "Bearer " + token)
            }
            Kind.GITLAB -> if (token.isNotEmpty()) put("PRIVATE-TOKEN", token)
            Kind.GITEA -> if (token.isNotEmpty()) put("Authorization", "token " + token)
            Kind.HTTPS -> if (token.isNotEmpty()) put("Authorization", "Bearer " + token)
            Kind.SSH -> Unit
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("location", location)
        put("ref", ref)
        put("token", token)
    }

    companion object {
        fun fromJson(o: JSONObject) = PluginSource(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = Kind.valueOf(o.optString("kind", "GITHUB")),
            location = o.getString("location"),
            ref = o.optString("ref", "main"),
            token = o.optString("token", ""),
        )

        fun listToJson(sources: List<PluginSource>): String =
            JSONArray().apply { sources.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(raw: String): List<PluginSource> = try {
            val a = JSONArray(raw)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
