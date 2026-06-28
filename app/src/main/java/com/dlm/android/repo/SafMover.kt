package com.dlm.android.repo

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

    /** Copy [file] into the [treeUri] document tree. Returns true on success. */
    suspend fun export(context: Context, treeUri: Uri, file: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext false
                val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false

                // Replace any existing file with the same name.
                tree.findFile(file.name)?.delete()
                val mime = mimeOf(file.name)
                val doc = tree.createFile(mime, file.name) ?: return@withContext false

                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@withContext false
                true
            } catch (e: Exception) {
                Log.e("SafMover", "export failed for ${file.name}", e)
                false
            }
        }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
