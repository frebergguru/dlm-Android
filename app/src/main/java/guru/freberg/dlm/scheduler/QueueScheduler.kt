package guru.freberg.dlm.scheduler

import guru.freberg.dlm.core.jni.NativeEngine
import guru.freberg.dlm.core.jni.NativeExtract
import guru.freberg.dlm.core.jni.ProgressSink
import guru.freberg.dlm.core.jni.QueueStore
import guru.freberg.dlm.core.model.DownloadOptions
import guru.freberg.dlm.core.model.StoreRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin re-implementation of the dlmd scheduler (`daemon/queue.c`), preserving
 * its exact semantics: two lists (download + linkgrabber staging), packages,
 * priorities, enable/disable, per-link & global autostart, force, reorder and
 * clear-finished. The sqlite `store` (libdlm `store.c`) remains the persisted
 * source of truth for ordering/priority/grouping/state.
 *
 * Threading model mirrors the C original's cooperative design: all queue state
 * is mutated under a single [mutex]; each active download runs as a coroutine
 * that drives the native engine (or the yt-dlp [resolver]) and is cancelled
 * cooperatively via a flag the worker polls — never by interrupting the
 * blocking native call.
 *
 * The native [store] (libdlm `store.c`) is single-thread-affine and must never
 * be touched from `Dispatchers.Main`. Every [store] access is therefore confined
 * to [storeDispatcher], a dedicated single-thread dispatcher, by wrapping each
 * mutex-guarded critical section in `withContext(storeDispatcher)`.
 */
class QueueScheduler(
    private val store: QueueStore,
    private val scope: CoroutineScope,
    private val resolver: MediaResolver,
    private val downloadDir: () -> File,
    initialMaxActive: Int = 3,
    initialMaxSpeed: Long = 0,
    initialGlobalAutostart: Boolean = true,
) {
    private val mutex = Mutex()
    private val items = ArrayList<QItem>()
    private val pkgs = ArrayList<QPkg>()
    private var nextPos = 1L

    /**
     * Single-thread dispatcher that serializes all native [store] I/O. The
     * libdlm store is single-thread-affine, so every `store.*` call runs here.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile var maxActive = if (initialMaxActive >= 1) initialMaxActive else 3
        private set
    @Volatile var maxSpeed = initialMaxSpeed
        private set
    @Volatile var globalAutostart = initialGlobalAutostart
        private set

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /** Load persisted rows, requeue interrupted items, clear stale force flags. */
    suspend fun load() = withContext(storeDispatcher) {
        mutex.withLock {
            for (p in store.loadPackages()) {
                pkgs += QPkg(
                    id = p.id, name = p.name, folder = p.folder, comment = p.comment,
                    list = ListKind.fromStore(p.list), priority = p.priority,
                    collapsed = p.collapsed != 0, position = p.position,
                )
                if (p.position >= nextPos) nextPos = p.position + 1
            }
            for (r in store.loadAll()) {
                items += QItem.fromStore(r)
                if (r.position >= nextPos) nextPos = r.position + 1
            }
            for (it in items) {
                if (it.state == QState.QUEUED && it.list == ListKind.DOWNLOAD)
                    store.setState(it.id, "queued", null)
                store.setForce(it.id, 0)
            }
            publishLocked()
        }
    }

    // ---- add / linkgrabber ------------------------------------------------

    suspend fun add(url: String, outPath: String?, connections: Int, delegate: Int): Long =
        withContext(storeDispatcher) {
            mutex.withLock {
                val path = resolveOut(outPath, url)
                val pos = nextPos++
                val row = StoreRow(
                    id = 0, url = url, outPath = path, connections = connections,
                    delegate = delegate, total = -1, downloaded = 0, state = "queued",
                    error = null, createdAt = nowSec(), packageId = 0,
                    priority = Priority.DEFAULT, enabled = 1, autostart = 1,
                    list = "download", name = null, availability = "unknown",
                    position = pos, force = 0,
                )
                val id = store.addFull(row)
                if (id > 0) {
                    items += QItem(
                        id = id, url = url, outPath = path, name = null,
                        connections = connections, delegate = delegate, total = -1,
                        state = QState.QUEUED, list = ListKind.DOWNLOAD,
                        availability = "unknown", position = pos,
                    )
                }
                publishLocked()
                id
            }
        }

    /** Stage a crawled set of links as a new linkgrabber package. */
    suspend fun grab(packageName: String?, folder: String?, links: List<GrabLink>): Long =
        withContext(storeDispatcher) {
            mutex.withLock {
                if (links.isEmpty()) return@withLock -1L
                val pos = nextPos++
                val pkgId = store.packageAdd(
                    packageName ?: "links", folder, null, "linkgrabber",
                    Priority.DEFAULT, pos, nowSec(),
                )
                if (pkgId <= 0) return@withLock -1L
                pkgs += QPkg(
                    id = pkgId, name = packageName ?: "links", folder = folder,
                    comment = null, list = ListKind.LINKGRABBER,
                    priority = Priority.DEFAULT, collapsed = false, position = pos,
                )
                for (l in links) {
                    val path = resolveOut(l.outPath, l.url)
                    val p = nextPos++
                    val row = StoreRow(
                        id = 0, url = l.url, outPath = path, connections = l.connections,
                        delegate = l.delegate, total = l.size, downloaded = 0,
                        state = "queued", error = null, createdAt = nowSec(),
                        packageId = pkgId, priority = Priority.DEFAULT, enabled = 1,
                        autostart = 1, list = "linkgrabber", name = l.name,
                        availability = l.availability ?: "unknown", position = p, force = 0,
                    )
                    val id = store.addFull(row)
                    if (id > 0) {
                        items += QItem(
                            id = id, url = l.url, outPath = path, name = l.name,
                            connections = l.connections, delegate = l.delegate,
                            total = if (l.size != 0L) l.size else -1,
                            state = QState.QUEUED, list = ListKind.LINKGRABBER,
                            availability = l.availability ?: "unknown",
                            packageId = pkgId, position = p,
                        )
                    }
                }
                publishLocked()
                pkgId
            }
        }

    /** Move linkgrabber content into the download list. id<=0 confirms all. */
    suspend fun confirm(id: Long, isPackage: Boolean, start: Boolean): Int =
        withContext(storeDispatcher) {
            mutex.withLock {
                var moved = 0
                when {
                    id <= 0 -> {
                        for (p in pkgs) if (p.list == ListKind.LINKGRABBER) {
                            p.list = ListKind.DOWNLOAD
                            store.packageSetList(p.id, "download")
                        }
                        for (it in items) if (it.list == ListKind.LINKGRABBER) {
                            confirmLink(it, start); moved++
                        }
                    }
                    isPackage -> {
                        val p = findPkg(id)
                        if (p == null || p.list != ListKind.LINKGRABBER) return@withLock -1
                        p.list = ListKind.DOWNLOAD
                        store.packageSetList(p.id, "download")
                        for (it in items) if (it.packageId == id && it.list == ListKind.LINKGRABBER) {
                            confirmLink(it, start); moved++
                        }
                    }
                    else -> {
                        val it = find(id)
                        if (it == null || it.list != ListKind.LINKGRABBER) return@withLock -1
                        val wasPkg = it.packageId
                        confirmLink(it, start); moved = 1
                        if (wasPkg > 0 && items.none { it2 -> it2.packageId == wasPkg && it2.list == ListKind.LINKGRABBER }) {
                            findPkg(wasPkg)?.let { p ->
                                if (p.list == ListKind.LINKGRABBER) {
                                    p.list = ListKind.DOWNLOAD
                                    store.packageSetList(p.id, "download")
                                }
                            }
                        }
                    }
                }
                publishLocked()
                moved
            }
        }

    private fun confirmLink(it: QItem, start: Boolean) {
        if (it.list != ListKind.LINKGRABBER) return
        it.list = ListKind.DOWNLOAD
        it.state = QState.QUEUED
        it.autostart = start
        store.setList(it.id, "download")
        store.setState(it.id, "queued", null)
        store.setAutostart(it.id, if (start) 1 else 0)
    }

    /** Remove linkgrabber content. id<=0 clears the whole linkgrabber. */
    suspend fun lgRemove(id: Long, isPackage: Boolean): Int = withContext(storeDispatcher) {
        mutex.withLock {
            var removed = 0
            when {
                id <= 0 -> {
                    items.removeAll { it ->
                        if (it.list == ListKind.LINKGRABBER) { store.delete(it.id); removed++; true } else false
                    }
                    pkgs.removeAll { p ->
                        if (p.list == ListKind.LINKGRABBER) { store.packageDelete(p.id); true } else false
                    }
                }
                isPackage -> {
                    val p = findPkg(id)
                    if (p == null || p.list != ListKind.LINKGRABBER) return@withLock -1
                    items.removeAll { it ->
                        if (it.packageId == id && it.list == ListKind.LINKGRABBER) { store.delete(it.id); removed++; true } else false
                    }
                    if (pkgs.remove(p)) store.packageDelete(id)
                }
                else -> {
                    val it = find(id)
                    if (it == null || it.list != ListKind.LINKGRABBER) return@withLock -1
                    val pkgId = it.packageId
                    items.remove(it); store.delete(id); removed = 1
                    dropIfEmpty(pkgId)
                }
            }
            publishLocked()
            removed
        }
    }

    // ---- per-link operations ---------------------------------------------

    suspend fun pause(id: Long): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val it = find(id) ?: return@withLock -1
            it.force = false
            when (it.state) {
                QState.ACTIVE -> { it.pauseRequested = true; it.requestCancel() }
                QState.QUEUED -> { it.state = QState.PAUSED; store.setState(id, "paused", null) }
                else -> {}
            }
            publishLocked(); 0
        }
    }

    suspend fun resume(id: Long): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val it = find(id) ?: return@withLock -1
            if (it.state == QState.PAUSED || it.state == QState.ERROR) {
                it.state = QState.QUEUED
                it.resetCancel()
                it.error = null
                store.setState(id, "queued", null)
                publishLocked(); 0
            } else -1
        }
    }

    suspend fun remove(id: Long): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val it = find(id) ?: return@withLock -1
            val pkgId = it.packageId
            if (it.job != null) {
                it.removeRequested = true
                it.requestCancel()
            } else {
                cleanupPartialFiles(it)
                store.delete(id)
                items.remove(it)
                dropIfEmpty(pkgId)
            }
            publishLocked(); 0
        }
    }

    suspend fun pkgRemove(packageId: Long): Int = withContext(storeDispatcher) {
        mutex.withLock {
            findPkg(packageId) ?: return@withLock -1
            val it = items.filter { it.packageId == packageId }
            for (item in it) {
                if (item.job != null) {
                    item.removeRequested = true
                    item.requestCancel()
                } else {
                    cleanupPartialFiles(item)
                    store.delete(item.id)
                    items.remove(item)
                }
            }
            dropIfEmpty(packageId)
            publishLocked(); 0
        }
    }

    suspend fun setPriority(id: Long, isPackage: Boolean, prio: Int): Int = withContext(storeDispatcher) {
        mutex.withLock {
            if (isPackage) findPkg(id)?.let { p ->
                p.priority = Priority.clamp(prio)
                store.packageUpdate(id, null, null, null, p.priority, if (p.collapsed) 1 else 0)
            }
            val rc = applyTo(id, isPackage) { it ->
                it.priority = Priority.clamp(prio); store.setPriority(it.id, it.priority)
            }
            publishLocked(); rc
        }
    }

    suspend fun setEnabled(id: Long, isPackage: Boolean, enabled: Boolean): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val rc = applyTo(id, isPackage) { it ->
                it.enabled = enabled; store.setEnabled(it.id, if (enabled) 1 else 0)
            }
            publishLocked(); rc
        }
    }

    suspend fun setAutostart(id: Long, isPackage: Boolean, on: Boolean): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val rc = applyTo(id, isPackage) { it ->
                it.autostart = on; store.setAutostart(it.id, if (on) 1 else 0)
            }
            publishLocked(); rc
        }
    }

    suspend fun force(id: Long, isPackage: Boolean): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val rc = applyTo(id, isPackage) { it -> applyForce(it) }
            publishLocked(); rc
        }
    }

    private fun applyForce(it: QItem) {
        if (it.list != ListKind.DOWNLOAD) return
        it.enabled = true
        it.force = true
        if (it.state == QState.PAUSED || it.state == QState.ERROR) {
            it.state = QState.QUEUED
            it.resetCancel()
            it.error = null
        }
        store.setEnabled(it.id, 1)
        store.setForce(it.id, 1)
        store.setState(it.id, "queued", null)
    }

    private inline fun applyTo(id: Long, isPackage: Boolean, fn: (QItem) -> Unit): Int {
        return if (isPackage) {
            if (findPkg(id) == null) -1
            else { items.filter { it.packageId == id }.forEach(fn); 0 }
        } else {
            val it = find(id) ?: return -1
            fn(it); 0
        }
    }

    // ---- reordering -------------------------------------------------------

    suspend fun move(id: Long, isPackage: Boolean, dir: MoveDir): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val rc = if (!isPackage) moveLink(id, dir) else movePkg(id, dir)
            if (rc == 0) publishLocked()
            rc
        }
    }

    private fun moveLink(id: Long, dir: MoveDir): Int {
        val link = find(id) ?: return -1
        // scope: same list + same package, as in queue.c
        val peers = items.filter { it !== link && it.list == link.list && it.packageId == link.packageId }
        reorder(dir, link.position, peers.map { it.position },
            swapWith = { pos ->
                val n = peers.first { it.position == pos }
                val t = link.position; link.position = n.position; n.position = t
                store.setPosition(link.id, link.position); store.setPosition(n.id, n.position)
            },
            setPos = { p -> link.position = p; store.setPosition(link.id, p) })
        return 0
    }

    private fun movePkg(id: Long, dir: MoveDir): Int {
        val pkg = findPkg(id) ?: return -1
        val peers = pkgs.filter { it !== pkg && it.list == pkg.list }
        reorder(dir, pkg.position, peers.map { it.position },
            swapWith = { pos ->
                val n = peers.first { it.position == pos }
                val t = pkg.position; pkg.position = n.position; n.position = t
                store.packageSetPosition(pkg.id, pkg.position); store.packageSetPosition(n.id, n.position)
            },
            setPos = { v -> pkg.position = v; store.packageSetPosition(id, v) })
        return 0
    }

    /** Shared up/down (swap with neighbour) / top/bottom (nudge past extreme). */
    private inline fun reorder(
        dir: MoveDir, self: Long, others: List<Long>,
        swapWith: (Long) -> Unit, setPos: (Long) -> Unit,
    ) {
        when (dir) {
            MoveDir.UP -> others.filter { it < self }.maxOrNull()?.let { swapWith(it) }
            MoveDir.DOWN -> others.filter { it > self }.minOrNull()?.let { swapWith(it) }
            MoveDir.TOP -> others.minOrNull()?.let { setPos(it - 1) }
            MoveDir.BOTTOM -> others.maxOrNull()?.let { setPos(it + 1) }
        }
    }

    suspend fun pkgUpdate(
        id: Long, name: String?, folder: String?, comment: String?,
        priority: Int, collapsed: Int,
    ): Int = withContext(storeDispatcher) {
        mutex.withLock {
            val p = findPkg(id) ?: return@withLock -1
            if (name != null) p.name = name
            if (folder != null) p.folder = folder
            if (comment != null) p.comment = comment
            if (priority >= Priority.LOWEST) p.priority = Priority.clamp(priority)
            if (collapsed >= 0) p.collapsed = collapsed != 0
            store.packageUpdate(id, name, folder, comment, p.priority, if (p.collapsed) 1 else 0)
            publishLocked(); 0
        }
    }

    // ---- global settings --------------------------------------------------

    suspend fun setMaxActive(v: Int) = mutex.withLock { maxActive = v.coerceAtLeast(1); publishLocked() }
    suspend fun setMaxSpeed(v: Long) = mutex.withLock { if (v >= 0) maxSpeed = v; publishLocked() }
    suspend fun setGlobalAutostart(on: Boolean) = mutex.withLock { globalAutostart = on; publishLocked() }

    /** Remove every finished link plus any package thereby left empty. */
    suspend fun clearFinished(): Int = withContext(storeDispatcher) {
        mutex.withLock {
            var removed = 0
            val done = items.filter { it.state == QState.DONE && it.job == null }
            for (it in done) {
                val pkgId = it.packageId
                store.delete(it.id); items.remove(it); dropIfEmpty(pkgId); removed++
            }
            publishLocked()
            removed
        }
    }

    // ---- scheduler tick ---------------------------------------------------

    /** Start eligible items. Call regularly (≈200ms) from the Service loop. */
    suspend fun tick() = withContext(storeDispatcher) {
        mutex.withLock {
            var active = items.count { it.job != null }

            // forced links start unconditionally (ignore max_active / autostart)
            for (it in items) {
                if (it.force && it.list == ListKind.DOWNLOAD && it.state == QState.QUEUED &&
                    it.job == null && it.enabled
                ) if (startItem(it)) active++
            }

            // start eligible by priority desc, then position asc, up to max_active
            if (globalAutostart) {
                while (active < maxActive) {
                    val best = items
                        .filter { eligible(it = it) }
                        .minWithOrNull(compareByDescending<QItem> { it.priority }.thenBy { it.position })
                        ?: break
                    if (startItem(best)) active++ else break
                }
            }

            // persist progress of active items
            for (it in items) if (it.job != null) store.setProgress(it.id, it.total, it.downloaded)
            publishLocked()
        }
    }

    private fun eligible(it: QItem): Boolean =
        it.list == ListKind.DOWNLOAD && it.state == QState.QUEUED &&
            it.job == null && it.enabled && it.autostart

    /** Launch a worker coroutine for [it]. Caller holds the mutex. */
    private fun startItem(it: QItem): Boolean {
        it.state = QState.ACTIVE
        it.resetCancel()
        store.setState(it.id, "active", null)

        val perRate = if (maxSpeed > 0) (maxSpeed / maxActive.coerceAtLeast(1)).coerceAtLeast(1) else 0
        if (it.delegate == 0) it.cancelToken = NativeEngine.newCancelToken()

        it.job = scope.launch {
            // finalizeWorker MUST always run so the item never stays ACTIVE and the
            // native cancel token is never leaked, even if the worker throws.
            val rc: Int = try {
                runWorker(it, perRate)
            } catch (ce: CancellationException) {
                // Scope/job cancellation: still free the token and settle state,
                // then propagate the cancellation as required by structured concurrency.
                withContext(NonCancellable) { finalizeWorker(it, DLM_ERR_CANCELLED) }
                throw ce
            } catch (t: Throwable) {
                // Worker blew up (resolver/native failure) — map to a generic error
                // rc so the item lands in ERROR instead of stalling the queue.
                DLM_ERR_UNKNOWN
            }
            finalizeWorker(it, rc)
        }
        return true
    }

    private suspend fun runWorker(it: QItem, perRate: Long): Int {
        val sink = ProgressSink { done, total, bps ->
            it.downloaded = done
            if (total >= 0) it.total = total
            it.speedBps = bps
        }
        return if (it.delegate != 0) {
            resolver.download(
                it.url, it.outPath, perRate,
                sink = { d, t, b -> sink.onProgress(d, t, b) },
                isCancelled = { it.cancelRequested },
            )
        } else {
            withContext(Dispatchers.IO) {
                NativeEngine.download(
                    DownloadOptions(
                        url = it.url, outPath = it.outPath,
                        connections = it.connections, maxSpeed = perRate,
                    ),
                    it.cancelToken, sink,
                )
            }
        }
    }

    private suspend fun finalizeWorker(it: QItem, rc: Int) = withContext(storeDispatcher) {
        mutex.withLock {
            if (it.cancelToken != 0L) { NativeEngine.freeCancelToken(it.cancelToken); it.cancelToken = 0L }
            it.speedBps = 0.0
            it.job = null
            it.force = false
            store.setForce(it.id, 0)
            store.setProgress(it.id, it.total, it.downloaded)

            if (it.removeRequested) {
                val pkgId = it.packageId
                cleanupPartialFiles(it)
                store.delete(it.id)
                items.remove(it)
                dropIfEmpty(pkgId)
                publishLocked()
                return@withLock
            }

            it.state = when (rc) {
                DLM_OK -> QState.DONE
                DLM_ERR_CANCELLED -> QState.PAUSED
                else -> { it.error = dlmStrerror(rc); QState.ERROR }
            }
            // Delegated (yt-dlp) downloads only report a 0–100 percentage; on success
            // record the real on-disk size so the UI shows e.g. "463 KiB", not "100 B".
            if (it.state == QState.DONE) {
                val len = runCatching { File(it.outPath).length() }.getOrDefault(0L)
                if (len > 0) {
                    it.total = len
                    it.downloaded = len
                } else if (it.delegate != 0) {
                    it.total = -1 // unknown size — don't let the 0–100 sentinel show as bytes
                }
                store.setProgress(it.id, it.total, it.downloaded)
            }
            it.resetCancel()
            store.setState(it.id, it.state.wire, it.error)
            publishLocked()
        }
    }

    // ---- shutdown ---------------------------------------------------------

    suspend fun stopAll() = mutex.withLock {
        for (it in items) if (it.job != null) {
            it.pauseRequested = true
            it.requestCancel()
        }
    }

    /** Active worker count, read from the published immutable snapshot (no shared-state race). */
    fun activeCount(): Int = _snapshot.value.activeCount

    // ---- helpers ----------------------------------------------------------

    private fun find(id: Long): QItem? = items.firstOrNull { it.id == id }
    private fun findPkg(id: Long): QPkg? = pkgs.firstOrNull { it.id == id }

    private fun dropIfEmpty(packageId: Long) {
        if (packageId <= 0) return
        if (items.any { it.packageId == packageId }) return
        findPkg(packageId)?.let { p -> store.packageDelete(packageId); pkgs.remove(p) }
    }

    private fun cleanupPartialFiles(it: QItem) {
        File(it.outPath + ".dlmpart").delete()
        File(it.outPath + ".dlmjson").delete()
    }

    private fun resolveOut(outPath: String?, url: String): String {
        if (!outPath.isNullOrEmpty()) {
            // Absolute paths are used verbatim; bare filenames land in the download dir.
            return if (File(outPath).isAbsolute) outPath else File(downloadDir(), outPath).path
        }
        return File(downloadDir(), NativeExtract.filenameFromUrl(url)).path
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun publishLocked() {
        val linkSnaps = items.sortedWith(compareBy<QItem> { it.position }.thenBy { it.id })
            .map { it.toSnap() }
        val pkgSnaps = pkgs.sortedWith(compareBy<QPkg> { it.position }.thenBy { it.id })
            .map { p -> p.toSnap(items.count { it.packageId == p.id }) }
        _snapshot.value = QueueSnapshot(linkSnaps, pkgSnaps, maxActive, maxSpeed, globalAutostart)
    }

    private companion object {
        const val DLM_OK = 0
        const val DLM_ERR_CANCELLED = -6
        // Generic/unknown failure rc for unexpected worker exceptions. No dedicated
        // libdlm code exists for this, so it falls through dlmStrerror's else branch.
        const val DLM_ERR_UNKNOWN = -99
        fun dlmStrerror(rc: Int): String = when (rc) {
            -1 -> "bad argument"
            -2 -> "network error"
            -3 -> "filesystem error"
            -4 -> "HTTP error"
            -5 -> "out of memory"
            -6 -> "cancelled"
            -99 -> "unexpected error"
            else -> "error ($rc)"
        }
    }
}
