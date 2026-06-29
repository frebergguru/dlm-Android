// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.scheduler

/** Coarse link state, mirror of `dlm_qstate`. */
enum class QState(val wire: String) {
    QUEUED("queued"),
    ACTIVE("active"),
    PAUSED("paused"),
    DONE("done"),
    ERROR("error");

    companion object {
        /** Mirror of `qstate_from_str`: an interrupted "active" row requeues. */
        fun fromStore(s: String?): QState = when (s) {
            "active" -> QUEUED
            "paused" -> PAUSED
            "done" -> DONE
            "error" -> ERROR
            else -> QUEUED
        }
    }
}

/** Which list a link/package lives in, mirror of `dlm_list`. */
enum class ListKind(val wire: String) {
    DOWNLOAD("download"),
    LINKGRABBER("linkgrabber");

    companion object {
        fun fromStore(s: String?): ListKind =
            if (s == "linkgrabber") LINKGRABBER else DOWNLOAD
    }
}

enum class MoveDir { UP, DOWN, TOP, BOTTOM }

object Priority {
    const val LOWEST = -3
    const val DEFAULT = 0
    const val HIGHEST = 3
    fun clamp(p: Int) = p.coerceIn(LOWEST, HIGHEST)
}

/** A crawled link staged into the linkgrabber, mirror of `dlm_grab_link`. */
data class GrabLink(
    val url: String,
    val outPath: String?,
    val name: String?,
    val size: Long = -1,
    val connections: Int = 0,
    val delegate: Int = 0,
    val availability: String? = null,
)

/** Immutable UI snapshot of one link, mirror of `dlm_qsnap`. */
data class LinkSnap(
    val id: Long,
    val url: String,
    val outPath: String,
    val name: String,
    val connections: Int,
    val total: Long,
    val downloaded: Long,
    val speedBps: Double,
    val state: QState,
    val error: String?,
    val packageId: Long,
    val priority: Int,
    val enabled: Boolean,
    val autostart: Boolean,
    val force: Boolean,
    val list: ListKind,
    val availability: String?,
    val delegate: Boolean,
) {
    val progressFraction: Float
        get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Immutable UI snapshot of one package, mirror of `dlm_psnap`. */
data class PkgSnap(
    val id: Long,
    val name: String,
    val folder: String?,
    val comment: String?,
    val list: ListKind,
    val priority: Int,
    val collapsed: Boolean,
    val linkCount: Int,
)

/** The full reactive queue state the UI observes (replaces the daemon's
 * periodic `progress` broadcast). */
data class QueueSnapshot(
    val links: List<LinkSnap> = emptyList(),
    val packages: List<PkgSnap> = emptyList(),
    val maxActive: Int = 3,
    val maxSpeed: Long = 0,
    val globalAutostart: Boolean = true,
) {
    // Computed once at construction (the snapshot is immutable and rebuilt each tick),
    // not on every access: the service and both screens read these repeatedly per
    // emit. Not constructor params, so they stay out of equals()/hashCode().
    val downloads: List<LinkSnap> = links.filter { it.list == ListKind.DOWNLOAD }
    val linkgrabber: List<LinkSnap> = links.filter { it.list == ListKind.LINKGRABBER }
    val activeCount: Int = links.count { it.state == QState.ACTIVE }
    val totalSpeedBps: Double = links.asSequence().filter { it.state == QState.ACTIVE }.sumOf { it.speedBps }
}

/**
 * Resolves and downloads media that the native engine can't handle directly
 * (HLS/DASH/site pages), implemented by the yt-dlp runtime. Cancellation is
 * cooperative: [download] must poll [isCancelled] and kill its process when set,
 * exactly as the C daemon polls the cancel flag.
 */
interface MediaResolver {
    /** Resolve a page/stream URL into one or more concrete tasks, or null on failure. */
    suspend fun resolve(url: String): guru.freberg.dlm.core.model.ExtractResult?

    /**
     * Download a delegated stream to [outPath]. @return libdlm-style result code
     * (0 == ok, -6 == cancelled, other == error).
     */
    suspend fun download(
        url: String,
        outPath: String,
        rateBytesPerSec: Long,
        sink: (done: Long, total: Long, bps: Double) -> Unit,
        isCancelled: () -> Boolean,
    ): Int
}
