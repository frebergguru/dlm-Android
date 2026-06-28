package guru.freberg.dlm

import android.app.Application
import guru.freberg.dlm.repo.AppContainer
import guru.freberg.dlm.ytdlp.YtdlpUpdateWorker

/** Builds the process-wide [AppContainer] (native init + scheduler) at startup. */
class DlmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        container.ensureLoaded()
        // Keep yt-dlp current with an at-least-weekly background check.
        YtdlpUpdateWorker.schedule(this)
    }
}
