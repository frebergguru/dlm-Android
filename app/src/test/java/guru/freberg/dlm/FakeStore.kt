package guru.freberg.dlm

import guru.freberg.dlm.core.jni.QueueStore
import guru.freberg.dlm.core.model.PackageRow
import guru.freberg.dlm.core.model.StoreRow

/**
 * In-memory [QueueStore] for JVM unit tests, standing in for the native sqlite
 * store so the scheduler (the one component reimplemented from queue.c) can be
 * verified without the NDK library. Mirrors the store's "order by position,
 * then id" load contract.
 */
class FakeStore : QueueStore {
    private val rows = LinkedHashMap<Long, StoreRow>()
    private val pkgs = LinkedHashMap<Long, PackageRow>()
    private var rowSeq = 0L
    private var pkgSeq = 0L

    override fun add(url: String, outPath: String?, connections: Int, delegate: Int, createdAt: Long): Long {
        val id = ++rowSeq
        rows[id] = StoreRow(
            id, url, outPath ?: url, connections, delegate, -1, 0, "queued", null,
            createdAt, 0, 0, 1, 1, "download", null, "unknown", id, 0,
        )
        return id
    }

    override fun addFull(row: StoreRow): Long {
        val id = ++rowSeq
        rows[id] = row.copy(id = id)
        return id
    }

    private inline fun mutate(id: Long, f: (StoreRow) -> StoreRow): Int {
        val r = rows[id] ?: return -1
        rows[id] = f(r); return 0
    }

    override fun setProgress(id: Long, total: Long, downloaded: Long) =
        mutate(id) { it.copy(total = if (total < 0) it.total else total, downloaded = downloaded) }
    override fun setState(id: Long, state: String, error: String?) = mutate(id) { it.copy(state = state, error = error) }
    override fun setPriority(id: Long, priority: Int) = mutate(id) { it.copy(priority = priority) }
    override fun setEnabled(id: Long, enabled: Int) = mutate(id) { it.copy(enabled = enabled) }
    override fun setAutostart(id: Long, autostart: Int) = mutate(id) { it.copy(autostart = autostart) }
    override fun setForce(id: Long, force: Int) = mutate(id) { it.copy(force = force) }
    override fun setPosition(id: Long, position: Long) = mutate(id) { it.copy(position = position) }
    override fun setList(id: Long, list: String) = mutate(id) { it.copy(list = list) }
    override fun setPackage(id: Long, packageId: Long) = mutate(id) { it.copy(packageId = packageId) }
    override fun delete(id: Long): Int { return if (rows.remove(id) != null) 0 else -1 }

    override fun loadAll(): List<StoreRow> =
        rows.values.sortedWith(compareBy({ it.position }, { it.id }))

    override fun packageAdd(name: String?, folder: String?, comment: String?, list: String, priority: Int, position: Long, createdAt: Long): Long {
        val id = ++pkgSeq
        pkgs[id] = PackageRow(id, name ?: "links", folder ?: "", comment, list, priority, 0, position, createdAt)
        return id
    }

    override fun packageUpdate(id: Long, name: String?, folder: String?, comment: String?, priority: Int, collapsed: Int): Int {
        val p = pkgs[id] ?: return -1
        pkgs[id] = p.copy(
            name = name ?: p.name, folder = folder ?: p.folder, comment = comment ?: p.comment,
            priority = priority, collapsed = collapsed,
        )
        return 0
    }

    override fun packageSetList(id: Long, list: String): Int {
        val p = pkgs[id] ?: return -1; pkgs[id] = p.copy(list = list); return 0
    }

    override fun packageSetPosition(id: Long, position: Long): Int {
        val p = pkgs[id] ?: return -1; pkgs[id] = p.copy(position = position); return 0
    }

    override fun packageDelete(id: Long): Int = if (pkgs.remove(id) != null) 0 else -1

    override fun loadPackages(): List<PackageRow> =
        pkgs.values.sortedWith(compareBy({ it.position }, { it.id }))
}
