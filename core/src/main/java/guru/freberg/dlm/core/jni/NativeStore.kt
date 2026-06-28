package guru.freberg.dlm.core.jni

import guru.freberg.dlm.core.model.PackageRow
import guru.freberg.dlm.core.model.StoreRow

/**
 * Wrapper over libdlm's sqlite3 queue store (store.c). The store is the
 * persisted source of truth for the queue: ordering, priority, grouping,
 * state and coarse progress. Not thread-safe; the scheduler confines all
 * access to a single dispatcher.
 */
class NativeStore private constructor(private val handle: Long) : AutoCloseable, QueueStore {

    @Volatile private var closed = false

    override fun add(url: String, outPath: String?, connections: Int, delegate: Int, createdAt: Long): Long =
        nAdd(handle, url, outPath, connections, delegate, createdAt)

    override fun addFull(row: StoreRow): Long = nAddFull(
        handle, row.url, row.outPath, row.connections, row.delegate, row.total,
        row.downloaded, row.state, row.error, row.createdAt, row.packageId,
        row.priority, row.enabled, row.autostart, row.list, row.name,
        row.availability, row.position, row.force,
    )

    override fun setProgress(id: Long, total: Long, downloaded: Long): Int =
        nSetProgress(handle, id, total, downloaded)

    override fun setState(id: Long, state: String, error: String?): Int =
        nSetState(handle, id, state, error)

    override fun setPriority(id: Long, priority: Int): Int = nSetPriority(handle, id, priority)
    override fun setEnabled(id: Long, enabled: Int): Int = nSetEnabled(handle, id, enabled)
    override fun setAutostart(id: Long, autostart: Int): Int = nSetAutostart(handle, id, autostart)
    override fun setForce(id: Long, force: Int): Int = nSetForce(handle, id, force)
    override fun setPosition(id: Long, position: Long): Int = nSetPosition(handle, id, position)
    override fun setList(id: Long, list: String): Int = nSetList(handle, id, list)
    override fun setPackage(id: Long, packageId: Long): Int = nSetPackage(handle, id, packageId)
    override fun delete(id: Long): Int = nDelete(handle, id)

    override fun loadAll(): List<StoreRow> = nLoadAll(handle).asList()

    override fun packageAdd(
        name: String?, folder: String?, comment: String?, list: String,
        priority: Int, position: Long, createdAt: Long,
    ): Long = nPkgAdd(handle, name, folder, comment, list, priority, position, createdAt)

    override fun packageUpdate(
        id: Long, name: String?, folder: String?, comment: String?,
        priority: Int, collapsed: Int,
    ): Int = nPkgUpdate(handle, id, name, folder, comment, priority, collapsed)

    override fun packageSetList(id: Long, list: String): Int = nPkgSetList(handle, id, list)
    override fun packageSetPosition(id: Long, position: Long): Int = nPkgSetPosition(handle, id, position)
    override fun packageDelete(id: Long): Int = nPkgDelete(handle, id)

    override fun loadPackages(): List<PackageRow> = nLoadPackages(handle).asList()

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            nClose(handle)
        }
    }

    companion object {
        /** Open (creating + migrating) the store at [path]; null on failure. */
        fun open(path: String): NativeStore? {
            val h = nOpen(path)
            return if (h != 0L) NativeStore(h) else null
        }

        @JvmStatic private external fun nOpen(path: String): Long
        @JvmStatic private external fun nClose(handle: Long)
        @JvmStatic private external fun nAdd(handle: Long, url: String, outPath: String?, conns: Int, delegate: Int, createdAt: Long): Long
        @JvmStatic private external fun nAddFull(
            handle: Long, url: String, outPath: String, conns: Int, delegate: Int,
            total: Long, downloaded: Long, state: String, error: String?, createdAt: Long,
            packageId: Long, priority: Int, enabled: Int, autostart: Int, list: String,
            name: String?, availability: String?, position: Long, force: Int,
        ): Long
        @JvmStatic private external fun nSetProgress(handle: Long, id: Long, total: Long, downloaded: Long): Int
        @JvmStatic private external fun nSetState(handle: Long, id: Long, state: String, error: String?): Int
        @JvmStatic private external fun nSetPriority(handle: Long, id: Long, v: Int): Int
        @JvmStatic private external fun nSetEnabled(handle: Long, id: Long, v: Int): Int
        @JvmStatic private external fun nSetAutostart(handle: Long, id: Long, v: Int): Int
        @JvmStatic private external fun nSetForce(handle: Long, id: Long, v: Int): Int
        @JvmStatic private external fun nSetPosition(handle: Long, id: Long, pos: Long): Int
        @JvmStatic private external fun nSetList(handle: Long, id: Long, list: String): Int
        @JvmStatic private external fun nSetPackage(handle: Long, id: Long, pkg: Long): Int
        @JvmStatic private external fun nDelete(handle: Long, id: Long): Int
        @JvmStatic private external fun nLoadAll(handle: Long): Array<StoreRow>
        @JvmStatic private external fun nPkgAdd(handle: Long, name: String?, folder: String?, comment: String?, list: String, priority: Int, position: Long, createdAt: Long): Long
        @JvmStatic private external fun nPkgUpdate(handle: Long, id: Long, name: String?, folder: String?, comment: String?, priority: Int, collapsed: Int): Int
        @JvmStatic private external fun nPkgSetList(handle: Long, id: Long, list: String): Int
        @JvmStatic private external fun nPkgSetPosition(handle: Long, id: Long, pos: Long): Int
        @JvmStatic private external fun nPkgDelete(handle: Long, id: Long): Int
        @JvmStatic private external fun nLoadPackages(handle: Long): Array<PackageRow>
    }
}
