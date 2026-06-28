package com.dlm.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlm.android.repo.AppContainer
import com.dlm.android.scheduler.MoveDir
import com.dlm.android.ytdlp.YtdlpState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives every screen: exposes the reactive queue snapshot and forwards user
 * actions to the repository. */
class QueueViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppContainer.get(app).repository

    val snapshot: StateFlow<com.dlm.android.scheduler.QueueSnapshot> = repo.snapshot
    val ytdlpState: StateFlow<YtdlpState> = repo.ytdlpState

    /** Hosts of links currently resolving, for the live "Checking…" banner. */
    val resolving: StateFlow<List<String>> = repo.resolving

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private fun notify(msg: String) { _messages.tryEmit(msg) }

    // ---- adding -----------------------------------------------------------

    fun crawl(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        notify("Checking link…")
        val id = repo.crawl(url.trim())
        notify(if (id > 0) "Added — review it in the Review tab" else "Couldn’t read that link")
    }

    fun addDirect(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        notify("Adding…")
        val id = repo.addDirect(url.trim())
        notify(if (id > 0) "Download added" else "Couldn’t add that link")
    }

    fun setPackageCollapsed(id: Long, collapsed: Boolean) = viewModelScope.launch {
        // priority < -3 leaves importance unchanged; only the collapsed flag flips.
        repo.pkgUpdate(id, null, null, null, -100, if (collapsed) 1 else 0)
    }

    // ---- linkgrabber ------------------------------------------------------

    fun confirm(id: Long, isPackage: Boolean, start: Boolean) =
        viewModelScope.launch { repo.confirm(id, isPackage, start) }
    fun confirmAll(start: Boolean) = viewModelScope.launch { repo.confirm(-1, false, start) }
    fun lgRemove(id: Long, isPackage: Boolean) = viewModelScope.launch { repo.lgRemove(id, isPackage) }
    fun lgClear() = viewModelScope.launch { repo.lgRemove(-1, false) }

    // ---- per-link verbs ---------------------------------------------------

    fun pause(id: Long) = viewModelScope.launch { repo.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { repo.resume(id) }
    fun remove(id: Long) = viewModelScope.launch { repo.remove(id) }
    fun pkgRemove(id: Long) = viewModelScope.launch { repo.pkgRemove(id) }
    fun setPriority(id: Long, isPackage: Boolean, prio: Int) =
        viewModelScope.launch { repo.setPriority(id, isPackage, prio) }
    fun setEnabled(id: Long, isPackage: Boolean, enabled: Boolean) =
        viewModelScope.launch { repo.setEnabled(id, isPackage, enabled) }
    fun setAutostart(id: Long, isPackage: Boolean, on: Boolean) =
        viewModelScope.launch { repo.setAutostart(id, isPackage, on) }
    fun force(id: Long, isPackage: Boolean) = viewModelScope.launch { repo.force(id, isPackage) }
    fun move(id: Long, isPackage: Boolean, dir: MoveDir) =
        viewModelScope.launch { repo.move(id, isPackage, dir) }
    fun clearFinished() = viewModelScope.launch { repo.clearFinished() }
    fun export(outPath: String) = viewModelScope.launch {
        notify(if (repo.exportToTree(outPath)) "Exported" else "Export failed (set a folder in Settings)")
    }

    // ---- global -----------------------------------------------------------

    fun setMaxActive(v: Int) = viewModelScope.launch { repo.setMaxActive(v) }
    fun setMaxSpeed(bytesPerSec: Long) = viewModelScope.launch { repo.setMaxSpeed(bytesPerSec) }
    fun setGlobalAutostart(on: Boolean) = viewModelScope.launch { repo.setGlobalAutostart(on) }
    fun parseRate(s: String) = repo.parseRate(s)
    fun formatRate(bps: Long) = repo.formatRate(bps)

    fun prepareYtdlp() = viewModelScope.launch {
        notify("Setting up video & audio support…")
        notify(if (repo.prepareYtdlp()) "Ready to download videos" else "Setup didn’t finish — try again")
    }

    fun repository() = repo
}
