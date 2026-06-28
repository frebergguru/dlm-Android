// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ytdlp

import android.content.Context
import android.util.Log
import guru.freberg.dlm.repo.StatusCenter
import guru.freberg.dlm.scheduler.MediaResolver
import guru.freberg.dlm.core.jni.NativeExtract
import guru.freberg.dlm.core.model.ExtractResult
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Lifecycle state of the bundled-but-fetched-on-demand yt-dlp runtime. */
enum class YtdlpState { NOT_READY, PREPARING, READY, FAILED }

/**
 * Implements [MediaResolver] on top of youtubedl-android. The Python runtime +
 * yt-dlp + ffmpeg payload is **downloaded and set up automatically on first
 * use** (per the user's "auto-download on first run" choice), then yt-dlp is
 * kept current via a background self-update.
 *
 * Extraction reuses the verbatim native parser: we run `yt-dlp -J` and feed its
 * JSON to [NativeExtract.parseYtdlp] (→ dlm_ytdlp_parse), so all the subtle
 * format/fragment/header logic ships unchanged.
 */
class YtdlpManager(private val appContext: Context) : MediaResolver {

    private val _state = MutableStateFlow(YtdlpState.NOT_READY)
    val state: StateFlow<YtdlpState> = _state.asStateFlow()

    private val initMutex = Mutex()
    // Serialises every yt-dlp self-update path (foreground ensureReady, the weekly
    // worker's checkForUpdate, and the background update) — updateYoutubeDL writes
    // runtime files and is not safe to run concurrently with itself.
    private val updateMutex = Mutex()
    // Background scope for the (network) version check so it never sits on the
    // user-facing resolve/download path.
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updating = java.util.concurrent.atomic.AtomicBoolean(false)
    private val prefs = appContext.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
    @Volatile private var lastError: String? = null
    val error: String? get() = lastError

    /**
     * Ensure the runtime is ready to use: unpack the bundled Python/yt-dlp once,
     * then make sure yt-dlp itself is current. YouTube and friends break older
     * yt-dlp versions frequently, so the first run updates before resolving, and
     * thereafter we re-check at least weekly (also enforced in the background by
     * [YtdlpUpdateWorker]). Must run off the main thread.
     */
    suspend fun ensureReady(): Boolean {
        if (!ensureInit()) return false
        if (updateDue()) runUpdate()
        return _state.value == YtdlpState.READY
    }

    /**
     * Ready enough to resolve/download *right now*: unpack the runtime (required
     * before anything can run) but never block the caller on the weekly version
     * check — fire that off in the background instead. Used on the user-facing
     * hot path so adding a link returns as soon as yt-dlp can actually run, not
     * after a network update round-trip first.
     */
    private suspend fun ensureReadyNow(): Boolean {
        if (!ensureInit()) return false
        maybeUpdateInBackground()
        return _state.value == YtdlpState.READY
    }

    /** Kick off the version check in the background if due (at most one at a time). */
    private fun maybeUpdateInBackground() {
        if (!updateDue()) return
        if (!updating.compareAndSet(false, true)) return
        bgScope.launch {
            try { runUpdate() } finally { updating.set(false) }
        }
    }

    private suspend fun ensureInit(): Boolean = initMutex.withLock {
        if (_state.value == YtdlpState.READY) return@withLock true
        _state.value = YtdlpState.PREPARING
        StatusCenter.begin(ST_INIT, "Setting up video & audio support", "Unpacking components…")
        try {
            withContext(Dispatchers.IO) {
                // Unpacks the bundled Python + yt-dlp + ffmpeg into app storage.
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
            }
            lastError = null
            _state.value = YtdlpState.READY
            StatusCenter.finish(ST_INIT, true, "Video & audio support ready")
            true
        } catch (e: Exception) {
            lastError = e.message
            _state.value = YtdlpState.FAILED
            StatusCenter.finish(ST_INIT, false, friendly(e.message) ?: "Setup failed")
            Log.e(TAG, "yt-dlp init failed", e)
            false
        }
    }

    /** Force a yt-dlp version check now (used by the weekly background worker). */
    suspend fun checkForUpdate(): Boolean {
        if (!ensureInit()) return false
        return runUpdate()
    }

    private fun updateDue(): Boolean {
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        return last == 0L || System.currentTimeMillis() - last >= WEEK_MS
    }

    private suspend fun runUpdate(): Boolean = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            StatusCenter.begin(ST_UPDATE, "Updating yt-dlp", "Checking for a newer version…")
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(appContext)
                prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                true
            }.onSuccess {
                StatusCenter.finish(ST_UPDATE, true, "yt-dlp is up to date")
            }.onFailure {
                StatusCenter.finish(ST_UPDATE, false, friendly(it.message) ?: "Couldn’t check for updates")
                Log.w(TAG, "yt-dlp update skipped: ${it.message}")
            }.getOrDefault(false)
        }
    }

    private fun friendly(msg: String?): String? = msg?.take(140)

    /** Logs only the host so query-string tokens/credentials never reach logcat. */
    private fun redactUrl(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "<url>"

    override suspend fun resolve(url: String): ExtractResult? {
        if (!ensureReadyNow()) return null
        return withContext(Dispatchers.IO) {
            try {
                val req = YoutubeDLRequest(url).apply {
                    addOption("-J")
                    addOption("--no-warnings")
                    addOption("--no-playlist")
                    // End-of-options marker: yt-dlp treats everything after "--" as a
                    // positional URL, so a URL beginning with "-" can't be parsed as a
                    // flag (e.g. --exec). buildCommand emits commands just before urls.
                    addCommands(listOf("--"))
                }
                val json = YoutubeDL.getInstance().execute(req).out
                if (json.isBlank()) null
                else NativeExtract.parseYtdlp(json, url)
            } catch (e: Exception) {
                // Don't pass the raw exception: youtubedl-android embeds the full
                // command line (incl. the URL and any query tokens) in its message.
                Log.e(TAG, "yt-dlp resolve failed for ${redactUrl(url)} (${e.javaClass.simpleName})")
                null
            }
        }
    }

    override suspend fun download(
        url: String,
        outPath: String,
        rateBytesPerSec: Long,
        sink: (Long, Long, Double) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        if (!ensureReadyNow()) return DLM_ERR_NET
        val processId = "dlm-" + outPath.hashCode().toUInt().toString() + "-" + url.hashCode()

        val req = YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("--no-playlist")
            addOption("--newline")
            if (rateBytesPerSec > 0) addOption("--limit-rate", rateBytesPerSec.toString())
            mergeExt(outPath)?.let { addOption("--merge-output-format", it) }
            addOption("-o", outPath)
            // End-of-options marker; see resolve(). Prevents URL arg injection.
            addCommands(listOf("--"))
        }

        return withContext(Dispatchers.IO) {
            // Watcher: poll the cooperative cancel flag and kill the process,
            // mirroring the C daemon's poll loop around yt-dlp.
            val watcher = launch {
                while (isActive) {
                    if (isCancelled()) {
                        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                        break
                    }
                    delay(200)
                }
            }
            try {
                YoutubeDL.getInstance().execute(req, processId) { progress, _, _ ->
                    // progress is a 0..100 percentage; bytes aren't exposed, so
                    // report a normalised total for the progress bar.
                    if (progress >= 0f) sink(progress.toLong(), 100L, -1.0)
                }
                if (isCancelled()) DLM_ERR_CANCELLED else DLM_OK
            } catch (e: Exception) {
                if (isCancelled() || e is YoutubeDL.CanceledException) DLM_ERR_CANCELLED
                else {
                    Log.e(TAG, "yt-dlp download failed for ${redactUrl(url)} (${e.javaClass.simpleName})")
                    DLM_ERR_NET
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun mergeExt(path: String): String? {
        val ext = File(path).extension.lowercase()
        return if (ext in MERGE_EXTS) ext else null
    }

    private companion object {
        const val TAG = "YtdlpManager"
        const val DLM_OK = 0
        const val DLM_ERR_NET = -2
        const val DLM_ERR_CANCELLED = -6
        const val KEY_LAST_UPDATE = "last_update_ms"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        const val ST_INIT = "ytdlp-init"
        const val ST_UPDATE = "ytdlp-update"
        val MERGE_EXTS = setOf("mp4", "mkv", "webm", "ogg", "flv", "mov")
    }
}
