package com.dlm.android.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StatusState { RUNNING, DONE, FAILED }

/** One background activity (e.g. setting up video support, updating yt-dlp). */
data class StatusItem(
    val key: String,
    val title: String,
    val detail: String,
    val state: StatusState,
    val progress: Float?,   // 0..1 when known, else null (indeterminate)
    val updatedAt: Long,
)

/**
 * Process-wide record of background "things happening" — runtime setup, yt-dlp
 * updates, the weekly check — so the user can see what the app is doing at start
 * and whether it succeeded. Surfaced by the Status screen.
 */
object StatusCenter {
    private const val MAX = 50
    private val _items = MutableStateFlow<List<StatusItem>>(emptyList())
    val items: StateFlow<List<StatusItem>> = _items.asStateFlow()

    val hasRunning: Boolean get() = _items.value.any { it.state == StatusState.RUNNING }

    /** Provided by the app so timestamps work without Date in shared code. */
    @Volatile var clock: () -> Long = { 0L }

    @Synchronized
    fun begin(key: String, title: String, detail: String = "") =
        upsert(key, title, detail, StatusState.RUNNING, null)

    @Synchronized
    fun progress(key: String, detail: String, progress: Float? = null) {
        val cur = _items.value.firstOrNull { it.key == key }
        upsert(key, cur?.title ?: key, detail, StatusState.RUNNING, progress)
    }

    @Synchronized
    fun finish(key: String, ok: Boolean, detail: String) {
        val cur = _items.value.firstOrNull { it.key == key }
        upsert(key, cur?.title ?: key, detail, if (ok) StatusState.DONE else StatusState.FAILED, null)
    }

    fun clearFinished() {
        _items.value = _items.value.filter { it.state == StatusState.RUNNING }
    }

    private fun upsert(key: String, title: String, detail: String, state: StatusState, progress: Float?) {
        val now = clock()
        val item = StatusItem(key, title, detail, state, progress, now)
        val rest = _items.value.filterNot { it.key == key }
        _items.value = (listOf(item) + rest).take(MAX)
    }
}
