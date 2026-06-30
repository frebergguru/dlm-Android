// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import guru.freberg.dlm.scheduler.GrabLink
import guru.freberg.dlm.scheduler.ListKind
import guru.freberg.dlm.scheduler.MoveDir
import guru.freberg.dlm.scheduler.QState
import guru.freberg.dlm.scheduler.QueueScheduler
import guru.freberg.dlm.scheduler.QueueSnapshot
import guru.freberg.dlm.service.DownloadService
import guru.freberg.dlm.ytdlp.YtdlpManager
import guru.freberg.dlm.ytdlp.YtdlpState
import guru.freberg.dlm.core.jni.NativeAuth
import guru.freberg.dlm.core.jni.NativeExtract
import guru.freberg.dlm.core.model.ExtractResult
import guru.freberg.dlm.core.model.Task
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
        val res = resolveTasks(url) ?: return -1
        val tasks = res.tasks
        if (tasks.isEmpty()) return -1
        val links = tasks.map { it.toGrabLink() }
        val name = packageNameFor(res, url)
        val pkgId = scheduler.grab(name, null, links)
        return pkgId
    }

    /** Add [url] straight to the download list (CLI `add` semantics). Returns the
     * new id, or -1 if the link couldn't be resolved (we never download a page
     * URL as raw HTML — a site/stream that yt-dlp can't resolve is a failure). */
    suspend fun addDirect(url: String, connections: Int = 0): Long {
        val res = resolveTasks(url) ?: return -1
        val tasks = res.tasks
        if (tasks.isEmpty()) return -1
        val id = if (tasks.size == 1) {
            val t = tasks[0]
            scheduler.add(t.url, t.filename, if (connections > 0) connections else 0, t.delegate)
        } else {
            // multi-file: stage as a package instead
            scheduler.grab(packageNameFor(res, url), null, tasks.map { it.toGrabLink() })
        }
        ensureService()
        return id
    }

    private suspend fun resolveTasks(url: String): ExtractResult? {
        // Boundary guard: never hand option-injection / non-network-scheme input to
        // the native engine or yt-dlp, regardless of which UI path called us.
        if (!guru.freberg.dlm.ui.util.isSafeDownloadInput(url)) return null
        val label = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        // Track the resolve in both the inline banner (_resolving) and the global
        // StatusCenter, so a slow yt-dlp/playlist resolve visibly spins the Downloads
        // activity indicator and shows progress on the Activity screen — the user is
        // never left staring at a frozen-looking screen.
        val statusKey = "resolve-$label-${url.hashCode()}"
        _resolving.update { it + label }
        StatusCenter.begin(statusKey, "Checking link", label)
        var result: ExtractResult? = null
        try {
            // archive.org/direct resolution hits the network in libcurl — keep it
            // off the main thread. yt-dlp resolution already runs on IO internally.
            val native = withContext(Dispatchers.IO) { NativeExtract.extract(url) }
            result = if (!native.needsYtdlp) {
                native
            } else {
                // yt-dlp can be slow, especially expanding a playlist/season.
                StatusCenter.progress(statusKey, "Resolving $label with yt-dlp — playlists can take a moment…")
                // yt-dlp couldn't resolve it — fall back to a plain direct download so
                // generic files (unrecognised extension / unsupported site) still work.
                ytdlp.resolve(url) ?: directFallback(url)
            }
            return result
        } finally {
            // Remove a single occurrence (two adds of the same host stay balanced).
            _resolving.update { list ->
                val i = list.indexOf(label)
                if (i < 0) list else list.toMutableList().also { it.removeAt(i) }
            }
            val n = result?.tasks?.size ?: 0
            when {
                n > 1 -> StatusCenter.finish(statusKey, true, "Found $n files")
                n == 1 -> StatusCenter.finish(statusKey, true, "Ready to download")
                else -> StatusCenter.finish(statusKey, false, "Couldn’t read that link")
            }
        }
    }

    /**
     * Last-resort resolution for URLs yt-dlp rejects: treat the URL as a single
     * generic file and let the segmented engine fetch it directly (delegate = 0).
     *
     * Gated on the yt-dlp runtime being READY: a null from [YtdlpManager.resolve]
     * only means "unsupported URL" once yt-dlp actually ran. If it isn't set up
     * yet, a null is ambiguous, and blindly downloading would save a media page's
     * HTML instead of erroring — so we don't fall back in that case. http/https
     * only, since the engine probes with an HTTP HEAD/Range request.
     */
    private fun directFallback(url: String): ExtractResult? {
        if (ytdlp.state.value != YtdlpState.READY) return null
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return null
        return ExtractResult(
            source = null,
            packageName = null, // generic file: no item title, falls back to host
            tasks = arrayOf(
                Task(
                    url = url,
                    filename = NativeExtract.filenameFromUrl(url),
                    size = -1, md5 = null, sha1 = null, headers = null, delegate = 0,
                ),
            ),
            needsYtdlp = false,
        )
    }

    private fun Task.toGrabLink() = GrabLink(
        url = url, outPath = filename, name = filename,
        size = size, connections = 0, delegate = delegate,
        availability = "online",
    )

    /** Package/folder name for a resolved item: the extractor's human title when it
     * has one (archive.org / yt-dlp), else the host as a sensible fallback. */
    private fun packageNameFor(res: ExtractResult, url: String): String =
        res.packageName?.trim()?.takeIf { it.isNotEmpty() }
            ?: Uri.parse(url).host ?: "links"

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

    fun parseRate(s: String): Long = guru.freberg.dlm.core.jni.NativeLib.parseRate(s)
    fun formatRate(bps: Long): String = guru.freberg.dlm.core.jni.NativeLib.formatRate(bps)

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

    var clipboardMonitor: Boolean
        get() = settings.clipboardMonitor
        set(v) { settings.clipboardMonitor = v }

    /** Copy [outPath] into the chosen SAF tree (optionally a [subDir]), leaving the
     * local original. */
    suspend fun exportToTree(outPath: String, subDir: String? = null): Boolean {
        val uri = settings.downloadTreeUri ?: return false
        return SafMover.export(appContext, Uri.parse(uri), File(outPath), subDir)
    }

    /**
     * Like [exportToTree], but on success removes the app-private original (and any
     * leftover journal sidecars) so the finished file lives in the user's folder
     * instead of being duplicated in app storage. SafMover verifies the copy landed
     * in full before we get here, so the delete can never lose data.
     */
    suspend fun moveToTree(outPath: String, subDir: String? = null): Boolean {
        if (!exportToTree(outPath, subDir)) return false
        runCatching { File(outPath).delete() }
        runCatching { File("$outPath.dlmpart").delete() }
        runCatching { File("$outPath.dlmjson").delete() }
        return true
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
