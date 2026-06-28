package guru.freberg.dlm.core.model

/**
 * Mirrors of the libdlm C structs. The field order and nullability of these
 * constructors MUST match the JNI bridge (jni_init.c caches their <init>
 * signatures and constructs them from C). Do not reorder without updating
 * the corresponding GetMethodID signature strings.
 */

/** One link row, mirror of `dlm_store_row`. */
data class StoreRow(
    val id: Long,
    val url: String,
    val outPath: String,
    val connections: Int,
    val delegate: Int,
    val total: Long,
    val downloaded: Long,
    val state: String,
    val error: String?,
    val createdAt: Long,
    val packageId: Long,
    val priority: Int,
    val enabled: Int,
    val autostart: Int,
    val list: String,
    val name: String?,
    val availability: String?,
    val position: Long,
    val force: Int,
)

/** One package row, mirror of `dlm_store_pkg_row`. */
data class PackageRow(
    val id: Long,
    val name: String,
    val folder: String,
    val comment: String?,
    val list: String,
    val priority: Int,
    val collapsed: Int,
    val position: Long,
    val createdAt: Long,
)

/** One downloadable file produced by an extractor, mirror of `dlm_task`. */
data class Task(
    val url: String,
    val filename: String,
    val size: Long,
    val md5: String?,
    val sha1: String?,
    val headers: Array<String>?,
    val delegate: Int,
) {
    val isDelegate: Boolean get() = delegate != 0

    // Array members break the auto-generated equals/hashCode; provide stable ones.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Task) return false
        return url == other.url && filename == other.filename && size == other.size &&
            md5 == other.md5 && sha1 == other.sha1 && delegate == other.delegate &&
            (headers?.toList() ?: emptyList<String>()) ==
            (other.headers?.toList() ?: emptyList<String>())
    }

    override fun hashCode(): Int {
        var r = url.hashCode()
        r = 31 * r + filename.hashCode()
        r = 31 * r + size.hashCode()
        r = 31 * r + (md5?.hashCode() ?: 0)
        r = 31 * r + (sha1?.hashCode() ?: 0)
        r = 31 * r + (headers?.toList()?.hashCode() ?: 0)
        r = 31 * r + delegate
        return r
    }
}

/**
 * Result of extraction. `needsYtdlp` is set when the native side could not
 * resolve the URL (not archive.org, not a direct file) and the JVM yt-dlp
 * runtime must take over.
 */
class ExtractResult(
    val source: String?,
    val tasks: Array<Task>,
    val needsYtdlp: Boolean,
)

/** Options for a single engine download, mirror of `dlm_options`. */
data class DownloadOptions(
    val url: String,
    val outPath: String,
    val connections: Int = 0,
    val minSplitSize: Long = 0,
    val maxRetries: Int = 0,
    val maxSpeed: Long = 0,
    val headers: Array<String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadOptions) return false
        return url == other.url && outPath == other.outPath &&
            connections == other.connections && minSplitSize == other.minSplitSize &&
            maxRetries == other.maxRetries && maxSpeed == other.maxSpeed &&
            (headers?.toList() ?: emptyList<String>()) ==
            (other.headers?.toList() ?: emptyList<String>())
    }

    override fun hashCode(): Int {
        var r = url.hashCode()
        r = 31 * r + outPath.hashCode()
        r = 31 * r + connections
        r = 31 * r + minSplitSize.hashCode()
        r = 31 * r + maxRetries
        r = 31 * r + maxSpeed.hashCode()
        r = 31 * r + (headers?.toList()?.hashCode() ?: 0)
        return r
    }
}
