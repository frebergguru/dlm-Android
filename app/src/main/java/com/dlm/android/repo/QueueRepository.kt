package com.dlm.android.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.dlm.android.scheduler.GrabLink
import com.dlm.android.scheduler.ListKind
import com.dlm.android.scheduler.MoveDir
import com.dlm.android.scheduler.QState
import com.dlm.android.scheduler.QueueScheduler
import com.dlm.android.scheduler.QueueSnapshot
import com.dlm.android.service.DownloadService
import com.dlm.android.ytdlp.YtdlpManager
import com.dlm.core.jni.NativeAuth
import com.dlm.core.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single UI-facing facade over the scheduler, yt-dlp resolver and archive.org
 * auth. The 24 ex-IPC verbs of the daemon become suspend methods here; the
 * StateFlow replaces the daemon's `progress` broadcast.
 */
class QueueRepository(
    private val appContext: Context,
    private val scheduler: QueueScheduler,
    private val ytdlp: YtdlpManager,
    private val settings: SettingsStore,
) {
    val snapshot: StateFlow<QueueSnapshot> get() = scheduler.snapshot
    val ytdlpState get() = ytdlp.state

    private val _resolving = MutableStateFlow<List<String>>(emptyList())
    /** Hosts of links currently being resolved/crawled. Drives a live "Checking…"
     * indicator so the UI shows progress during the (sometimes slow) archive.org/
     * yt-dlp resolution instead of looking frozen. */
    val resolving: StateFlow<List<String>> = _resolving.asStateFlow()

    /** Trigger the one-time yt-dlp runtime download/setup. */
    suspend fun prepareYtdlp(): Boolean = ytdlp.ensureReady()

    // ---- adding / crawling ------------------------------------------------

    /**
     * Resolve [url] and stage the result in the linkgrabber for review
     * (JDownloader-style). archive.org/direct URLs resolve natively; anything
     * else goes through the yt-dlp runtime. Returns the new package id or -1.
     */
    suspend fun crawl(url: String): Long {
        val tasks = resolveTasks(url) ?: return -1
        if (tasks.isEmpty()) return -1
        val links = tasks.map { it.toGrabLink() }
        val name = packageNameFor(url)
        val pkgId = scheduler.grab(name, null, links)
        return pkgId
    }

    /** Add [url] straight to the download list (CLI `add` semantics). Returns the
     * new id, or -1 if the link couldn't be resolved (we never download a page
     * URL as raw HTML — a site/stream that yt-dlp can't resolve is a failure). */
    suspend fun addDirect(url: String, connections: Int = 0): Long {
        val tasks = resolveTasks(url) ?: return -1
        if (tasks.isEmpty()) return -1
        val id = if (tasks.size == 1) {
            val t = tasks[0]
            scheduler.add(t.url, t.filename, if (connections > 0) connections else 0, t.delegate)
        } else {
            // multi-file: stage as a package instead
            scheduler.grab(packageNameFor(url), null, tasks.map { it.toGrabLink() })
        }
        ensureService()
        return id
    }

    private suspend fun resolveTasks(url: String): List<Task>? {
        val label = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        _resolving.update { it + label }
        try {
            // archive.org/direct resolution hits the network in libcurl — keep it
            // off the main thread. yt-dlp resolution already runs on IO internally.
            val native = withContext(Dispatchers.IO) { com.dlm.core.jni.NativeExtract.extract(url) }
            if (!native.needsYtdlp) return native.tasks.toList()
            val res = ytdlp.resolve(url) ?: return null
            return res.tasks.toList()
        } finally {
            // Remove a single occurrence (two adds of the same host stay balanced).
            _resolving.update { list ->
                val i = list.indexOf(label)
                if (i < 0) list else list.toMutableList().also { it.removeAt(i) }
            }
        }
    }

    private fun Task.toGrabLink() = GrabLink(
        url = url, outPath = filename, name = filename,
        size = size, connections = 0, delegate = delegate,
        availability = "online",
    )

    private fun packageNameFor(url: String): String =
        Uri.parse(url).host ?: "links"

    // ---- per-link / package verbs ----------------------------------------

    suspend fun confirm(id: Long, isPackage: Boolean, start: Boolean): Int {
        val r = scheduler.confirm(id, isPackage, start)
        if (start) ensureService()
        return r
    }

    suspend fun lgRemove(id: Long, isPackage: Boolean) = scheduler.lgRemove(id, isPackage)
    suspend fun pause(id: Long) = scheduler.pause(id)
    suspend fun resume(id: Long): Int { val r = scheduler.resume(id); ensureService(); return r }
    suspend fun remove(id: Long) = scheduler.remove(id)
    suspend fun pkgRemove(id: Long) = scheduler.pkgRemove(id)
    suspend fun setPriority(id: Long, isPackage: Boolean, prio: Int) = scheduler.setPriority(id, isPackage, prio)
    suspend fun setEnabled(id: Long, isPackage: Boolean, enabled: Boolean) = scheduler.setEnabled(id, isPackage, enabled)
    suspend fun setAutostart(id: Long, isPackage: Boolean, on: Boolean) = scheduler.setAutostart(id, isPackage, on)
    suspend fun force(id: Long, isPackage: Boolean): Int { val r = scheduler.force(id, isPackage); ensureService(); return r }
    suspend fun move(id: Long, isPackage: Boolean, dir: MoveDir) = scheduler.move(id, isPackage, dir)
    suspend fun pkgUpdate(id: Long, name: String?, folder: String?, comment: String?, priority: Int, collapsed: Int) =
        scheduler.pkgUpdate(id, name, folder, comment, priority, collapsed)
    suspend fun clearFinished() = scheduler.clearFinished()

    // ---- global settings --------------------------------------------------

    suspend fun setMaxActive(v: Int) { settings.maxActive = v; scheduler.setMaxActive(v); ensureService() }
    suspend fun setMaxSpeed(v: Long) { settings.maxSpeed = v; scheduler.setMaxSpeed(v) }
    suspend fun setGlobalAutostart(on: Boolean) {
        settings.globalAutostart = on; scheduler.setGlobalAutostart(on)
        if (on) ensureService()
    }

    fun parseRate(s: String): Long = com.dlm.core.jni.NativeLib.parseRate(s)
    fun formatRate(bps: Long): String = com.dlm.core.jni.NativeLib.formatRate(bps)

    // ---- archive.org auth -------------------------------------------------

    fun authMode(): Int = NativeAuth.mode()
    fun authStatus(): String = NativeAuth.modeString()
    fun loginS3(access: String, secret: String): Boolean = NativeAuth.saveS3(access, secret) == 0
    fun loginCookie(cookie: String): Boolean = NativeAuth.saveCookie(cookie) == 0
    fun loginPassword(email: String, password: String): String? = NativeAuth.loginPassword(email, password)
    fun logout(): Boolean = NativeAuth.logout() == 0

    // ---- SAF export -------------------------------------------------------

    fun downloadTreeUri(): String? = settings.downloadTreeUri
    fun setDownloadTreeUri(uri: String?) { settings.downloadTreeUri = uri }
    var autoExport: Boolean
        get() = settings.autoExport
        set(v) { settings.autoExport = v }

    suspend fun exportToTree(outPath: String): Boolean {
        val uri = settings.downloadTreeUri ?: return false
        return SafMover.export(appContext, Uri.parse(uri), File(outPath))
    }

    // ---- service control --------------------------------------------------

    /** Start the foreground service so downloads keep running in the background. */
    fun ensureService() {
        val intent = Intent(appContext, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }
}
