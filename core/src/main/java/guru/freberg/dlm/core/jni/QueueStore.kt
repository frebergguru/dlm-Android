package guru.freberg.dlm.core.jni

import guru.freberg.dlm.core.model.PackageRow
import guru.freberg.dlm.core.model.StoreRow

/**
 * The persistence surface the scheduler depends on. [NativeStore] implements it
 * over libdlm's sqlite store; tests provide an in-memory fake so the scheduler
 * (the one component reimplemented from `queue.c`) can be verified on the JVM
 * without the native library.
 */
interface QueueStore {
    fun add(url: String, outPath: String?, connections: Int, delegate: Int, createdAt: Long): Long
    fun addFull(row: StoreRow): Long
    fun setProgress(id: Long, total: Long, downloaded: Long): Int
    fun setState(id: Long, state: String, error: String?): Int
    fun setPriority(id: Long, priority: Int): Int
    fun setEnabled(id: Long, enabled: Int): Int
    fun setAutostart(id: Long, autostart: Int): Int
    fun setForce(id: Long, force: Int): Int
    fun setPosition(id: Long, position: Long): Int
    fun setList(id: Long, list: String): Int
    fun setPackage(id: Long, packageId: Long): Int
    fun delete(id: Long): Int
    fun loadAll(): List<StoreRow>

    fun packageAdd(name: String?, folder: String?, comment: String?, list: String, priority: Int, position: Long, createdAt: Long): Long
    fun packageUpdate(id: Long, name: String?, folder: String?, comment: String?, priority: Int, collapsed: Int): Int
    fun packageSetList(id: Long, list: String): Int
    fun packageSetPosition(id: Long, position: Long): Int
    fun packageDelete(id: Long): Int
    fun loadPackages(): List<PackageRow>
}
