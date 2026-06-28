// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.scheduler

import guru.freberg.dlm.core.jni.NativeEngine
import guru.freberg.dlm.core.model.StoreRow
import kotlinx.coroutines.Job

/**
 * Mutable in-memory link, the Kotlin analogue of `queue.c`'s `qitem`. Live
 * progress fields are @Volatile because the engine progress callback writes
 * them from the download thread while the scheduler reads them for snapshots.
 */
class QItem(
    val id: Long,
    var url: String,
    var outPath: String,
    var name: String?,
    var connections: Int,
    var delegate: Int,
    @Volatile var total: Long,
    @Volatile var downloaded: Long = 0,
    @Volatile var speedBps: Double = 0.0,
    var state: QState,
    var error: String? = null,
    var packageId: Long = 0,
    var priority: Int = Priority.DEFAULT,
    var enabled: Boolean = true,
    var autostart: Boolean = true,
    var force: Boolean = false,
    var list: ListKind,
    var availability: String?,
    var position: Long,
) {
    // runtime
    var job: Job? = null
    var cancelToken: Long = 0
    @Volatile var cancelRequested: Boolean = false
    var pauseRequested: Boolean = false
    var removeRequested: Boolean = false

    /** Signal the worker to stop. Engine reads the native cancel cell; the
     * yt-dlp runner polls [cancelRequested]. */
    fun requestCancel() {
        cancelRequested = true
        if (cancelToken != 0L) NativeEngine.cancel(cancelToken)
    }

    fun resetCancel() {
        cancelRequested = false
        pauseRequested = false
    }

    fun toSnap(): LinkSnap = LinkSnap(
        id = id,
        url = url,
        outPath = outPath,
        name = name ?: outPath.substringAfterLast('/'),
        connections = connections,
        total = total,
        downloaded = downloaded,
        speedBps = speedBps,
        state = state,
        error = error,
        packageId = packageId,
        priority = priority,
        enabled = enabled,
        autostart = autostart,
        force = force,
        list = list,
        availability = availability,
        delegate = delegate != 0,
    )

    companion object {
        fun fromStore(r: StoreRow): QItem = QItem(
            id = r.id,
            url = r.url,
            outPath = r.outPath,
            name = r.name,
            connections = r.connections,
            delegate = r.delegate,
            total = r.total,
            downloaded = r.downloaded,
            state = QState.fromStore(r.state),
            error = r.error,
            packageId = r.packageId,
            priority = r.priority,
            enabled = r.enabled != 0,
            autostart = r.autostart != 0,
            force = false, // force never survives a restart
            list = ListKind.fromStore(r.list),
            availability = r.availability,
            position = r.position,
        )
    }
}

/** Mutable in-memory package, the Kotlin analogue of `queue.c`'s `pkg`. */
class QPkg(
    val id: Long,
    var name: String,
    var folder: String?,
    var comment: String?,
    var list: ListKind,
    var priority: Int,
    var collapsed: Boolean,
    var position: Long,
) {
    fun toSnap(linkCount: Int): PkgSnap = PkgSnap(
        id = id,
        name = name,
        folder = folder,
        comment = comment,
        list = list,
        priority = priority,
        collapsed = collapsed,
        linkCount = linkCount,
    )
}
