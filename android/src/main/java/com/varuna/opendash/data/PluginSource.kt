package com.varuna.opendash.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Where catalogues come from. Several can be configured at once — a shared
 * public one and a private repository of your own, say — and each keeps its
 * own credential.
 *
 * ## Why a token and not an SSH key
 *
 * SSH would mean a git client on the phone: JGit plus a Java SSH stack, several
 * megabytes of dependency, a key to generate and store, and host-key handling.
 * All of that to download a handful of text files.
 *
 * Over HTTPS the same private repository is one header. GitHub, GitLab and
 * Gitea all take a token, all can be scoped to read a single repository, and
 * all can be revoked from a web page if the phone is lost — which an SSH key
 * sitting in app storage cannot, not easily.
 *
 * So: token by default. A key pair can be added later if some forge genuinely
 * needs one, and [Kind] is where that would go.
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
    enum class Kind { GITHUB, GITLAB, GITEA, HTTPS }

    val isPrivate: Boolean get() = token.isNotEmpty()

    /** URL for one file inside the source. */
    fun urlFor(path: String): String = when (kind) {
        Kind.GITHUB ->
            "https://api.github.com/repos/" + location + "/contents/" + path + "?ref=" + ref
        Kind.GITLAB ->
            "https://gitlab.com/api/v4/projects/" + URLEncoder.encode(location, "UTF-8") +
                "/repository/files/" + URLEncoder.encode(path, "UTF-8") + "/raw?ref=" + ref
        Kind.GITEA -> location + "/raw/branch/" + ref + "/" + path
        Kind.HTTPS -> location.trimEnd('/') + "/" + path
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
