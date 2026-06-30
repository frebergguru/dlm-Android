// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** Compact human byte size, e.g. "12.3 MiB". */
fun formatBytes(n: Long): String {
    if (n < 0) return "—"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var v = n.toDouble(); var u = 0
    // Roll over at 1023.95, not 1024: a value like 1048575 divides to 1023.999,
    // which "%.1f" would otherwise render as the nonsensical "1024.0 KiB" instead
    // of "1.0 MiB".
    while (v >= 1023.95 && u < units.size - 1) { v /= 1024; u++ }
    return if (u == 0) "$n B" else String.format(Locale.US, "%.1f %s", v, units[u])
}

/** Remaining-time estimate, e.g. "3m 12s left" / "1h 4m left". Blank when unknown. */
fun formatEta(remainingBytes: Long, speedBps: Double): String {
    if (speedBps <= 0.5 || remainingBytes <= 0) return ""
    var s = (remainingBytes / speedBps).toLong()
    if (s <= 0) return ""
    val h = s / 3600; s %= 3600
    val m = s / 60; val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m}m left"
        m > 0 -> "${m}m ${sec}s left"
        else -> "${sec}s left"
    }
}

private val MEDIA_VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "3gp")
private val MEDIA_AUDIO = setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma", "aiff", "m4b")
private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "ico", "heic")
private val ARCHIVES = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "lz", "lzma", "cab")
private val DOCS = setOf("pdf")
private val TEXT = setOf("txt", "csv", "json", "xml", "md", "srt", "yaml", "yml", "toml", "log", "doc", "docx")
private val DISC = setOf("iso", "img", "dmg", "qcow2", "vdi", "vmdk")

/** A Material icon that hints at the file's type from its name/extension. */
fun fileTypeIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in MEDIA_VIDEO -> Icons.Filled.Movie
        in MEDIA_AUDIO -> Icons.Filled.MusicNote
        in IMAGES -> Icons.Filled.Image
        in ARCHIVES -> Icons.Filled.Archive
        in DOCS -> Icons.Filled.PictureAsPdf
        in TEXT -> Icons.Filled.Description
        in DISC -> Icons.Filled.Album
        "apk" -> Icons.Filled.Android
        "exe", "msi", "appimage", "sh", "bin" -> Icons.Filled.Terminal
        else -> Icons.Filled.InsertDriveFile
    }
}
