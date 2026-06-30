// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toPath
import guru.freberg.dlm.repo.AppContainer
import guru.freberg.dlm.ytdlp.YtdlpUpdateWorker

/** Builds the process-wide [AppContainer] (native init + scheduler) at startup. */
class DlmApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        container.ensureLoaded()
        // Keep yt-dlp current with an at-least-weekly background check.
        YtdlpUpdateWorker.schedule(this)
    }

    /** Coil image loader for site favicons: an explicit OkHttp network fetcher
     * (so the https fetch works regardless of classpath auto-detection) plus
     * Coil's default on-disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            // Favicons are tiny; cap the in-memory cache well below Coil's 25%-of-heap
            // default so the image loader never becomes a memory hog.
            .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
            // Persist favicons on disk (app cache dir) so they're reused across
            // launches instead of refetched every time a site icon is shown.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("favicon_cache").absolutePath.toPath())
                    .maxSizeBytes(16L * 1024 * 1024)
                    .build()
            }
            .build()
}
