// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies completed downloads from the app-private staging directory (where the
 * native engine writes with real `open()`/`rename()` + journal sidecars) into a
 * user-granted SAF tree on public storage. This confines all Storage Access
 * Framework handling to a post-completion step, so download.c stays untouched.
 */
object SafMover {

    /**
     * Copy [file] into the [treeUri] document tree, optionally inside a [subDir]
     * (created if missing). Returns true only after verifying the destination
     * received the complete file — callers rely on this before deleting the source,
     * so a partial/failed copy must never report success.
     */
    suspend fun export(context: Context, treeUri: Uri, file: File, subDir: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext false
                var tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false

                // Walk/create the nested "<host>/<title>" subfolder path, one segment
                // at a time (SAF can't create nested dirs in a single call).
                for (segment in subDir.orEmpty().split('/')) {
                    val name = sanitizeName(segment) ?: continue
                    tree = tree.findFile(name)?.takeIf { it.isDirectory }
                        ?: tree.createDirectory(name)
                        ?: return@withContext false
                }

                // Replace any existing file with the same name.
                tree.findFile(file.name)?.delete()
                val mime = mimeOf(file.name)
                val doc = tree.createFile(mime, file.name) ?: return@withContext false

                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@withContext false

                // Verify the whole file landed before the caller deletes the original.
                val expected = file.length()
                val written = doc.length()
                if (written != expected) {
                    Log.e("SafMover", "size mismatch for ${file.name}: wrote $written of $expected")
                    return@withContext false
                }
                true
            } catch (e: Exception) {
                Log.e("SafMover", "export failed for ${file.name}", e)
                false
            }
        }

    /** Make [raw] safe as a SAF directory name, or null if there's nothing usable. */
    private fun sanitizeName(raw: String?): String? {
        val cleaned = raw?.replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_")?.trim()?.trim('.')
        return cleaned?.takeIf { it.isNotEmpty() }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
