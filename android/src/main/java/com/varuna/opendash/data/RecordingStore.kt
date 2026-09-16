package com.varuna.opendash.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where recordings live, whether that is a folder the user picked or the app's
 * own storage.
 *
 * Android stopped letting an app write to a path someone typed several versions
 * ago, so the folder comes from the system picker and arrives as a tree URI
 * with a persisted permission attached. Everything here works the same either
 * way: the caller asks for a stream to write into, or for the list of what is
 * already there, and does not need to know which of the two it got.
 */
class RecordingStore(val context: Context, private val settings: Settings) {

    /** One saved session, from either backing store. */
    class Entry(val name: String, val bytes: Long, val modified: Long, val uri: Uri)

    /** A folder the user picked, if one is still granted to us. */
    private fun tree(): DocumentFile? {
        val raw = settings.recordingTree
        if (raw.isEmpty()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
    }

    /** Fallback: app-private, but on shared external storage so it is visible. */
    private fun internalDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "recordings").also { it.mkdirs() }
    }

    /** What to show in settings: the picked folder's name, or a fixed label. */
    fun label(): String? = tree()?.name

    /**
     * Remember a folder the picker returned, taking the permission that
     * survives a reboot. Passing null goes back to app storage.
     */
    fun useTree(uri: Uri?) {
        if (uri == null) {
            settings.recordingTree = ""
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.recordingTree = uri.toString()
    }

    /** Newest first, so the session just finished is at the top. */
    fun list(): List<Entry> {
        tree()?.let { dir ->
            return dir.listFiles()
                .filter { it.isFile }
                .map { Entry(it.name ?: "?", it.length(), it.lastModified(), it.uri) }
                .sortedByDescending { it.modified }
        }
        return internalDirectory().listFiles().orEmpty()
            .filter { it.isFile }
            .map { Entry(it.name, it.length(), it.lastModified(), Uri.fromFile(it)) }
            .sortedByDescending { it.modified }
    }

    fun read(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "the file could not be opened" }.readBytes()
        }

    /** A stream to record into, plus the name it ended up with. */
    class Sink(val name: String, val stream: OutputStream)

    fun create(label: String, compressed: Boolean): Sink {
        val name = fileName(label, compressed)
        val mime = if (compressed) "application/gzip" else "text/csv"
        tree()?.let { dir ->
            val document = dir.createFile(mime, name)
                ?: error("could not create a file in the chosen folder")
            val stream = context.contentResolver.openOutputStream(document.uri)
                ?: error("could not write to the chosen folder")
            return Sink(document.name ?: name, stream)
        }
        val file = File(internalDirectory(), name)
        return Sink(file.name, file.outputStream())
    }

    private fun fileName(label: String, compressed: Boolean): String {
        // To the second. At minute resolution two runs a minute apart share a
        // name, and the document tree then makes "live.csv (1).gz",
        // "live.csv (2).gz" and so on out of them — which is where a folder of
        // numbered near-duplicates comes from.
        val stamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.ROOT).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        return stamp + "_" + safe + if (compressed) ".csv.gz" else ".csv"
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(Locale.ROOT, "%.0f kB", bytes / 1024.0)
            else -> bytes.toString() + " B"
        }
    }
}
