package guru.freberg.dlm.repo

import android.content.Context
import android.os.Environment
import guru.freberg.dlm.scheduler.QueueScheduler
import guru.freberg.dlm.ytdlp.YtdlpManager
import guru.freberg.dlm.core.jni.NativeLib
import guru.freberg.dlm.core.jni.NativeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide singleton wiring the native core to the Kotlin scheduler. Built
 * once from [DlmApplication]; the Activity observes [scheduler]'s state directly
 * while [guru.freberg.dlm.service.DownloadService] keeps the process alive and
 * drives the scheduler tick when there is work.
 */
class AppContainer private constructor(private val appContext: Context) {

    val scope = CoroutineScope(SupervisorJob())
    val settings = SettingsStore(appContext)
    val ytdlp = YtdlpManager(appContext)

    private val store: NativeStore
    val scheduler: QueueScheduler
    val repository: QueueRepository

    @Volatile private var loaded = false

    init {
        StatusCenter.clock = { System.currentTimeMillis() }
        StatusCenter.begin("core-init", "Starting download engine")
        // Route libdlm config/credentials to app-private storage and libcurl's
        // CA trust to the shipped bundle, then init curl.
        val configDir = File(appContext.filesDir, "dlm").apply { mkdirs() }
        // Always refresh the CA bundle from assets so an app update ships a newer
        // trust store (and never leaves a stale/placeholder copy behind).
        val caBundle = File(appContext.filesDir, "cacert.pem")
        runCatching {
            appContext.assets.open("cacert.pem").use { input ->
                caBundle.outputStream().use { input.copyTo(it) }
            }
        }
        val rc = NativeLib.ensureInit(configDir.path, caBundle.path)
        check(rc == 0) { "libdlm init failed (rc=$rc)" }

        store = NativeStore.open(File(configDir, "queue.db").path)
            ?: error("cannot open queue store")

        scheduler = QueueScheduler(
            store = store,
            scope = scope,
            resolver = ytdlp,
            downloadDir = ::downloadDir,
            initialMaxActive = settings.maxActive,
            initialMaxSpeed = settings.maxSpeed,
            initialGlobalAutostart = settings.globalAutostart,
        )
        repository = QueueRepository(appContext, scheduler, ytdlp, settings)
        StatusCenter.finish("core-init", true, "Download engine ready")
    }

    /** Load persisted queue once (idempotent). */
    fun ensureLoaded() {
        if (loaded) return
        // Optimistically claim the load so two concurrent callers can't both launch;
        // reset on failure below so a later trigger retries instead of staying stuck.
        loaded = true
        scope.launch {
            StatusCenter.begin("queue-load", "Loading download queue")
            runCatching { scheduler.load() }
                .onSuccess { StatusCenter.finish("queue-load", true, "Queue loaded") }
                .onFailure { e ->
                    loaded = false
                    StatusCenter.finish("queue-load", false, "Queue load failed: ${e.message}")
                }
        }
    }

    fun downloadDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")).apply { mkdirs() }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
