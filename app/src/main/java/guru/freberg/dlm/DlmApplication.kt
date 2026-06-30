// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Cache
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import guru.freberg.dlm.repo.AppContainer
import guru.freberg.dlm.ui.util.FaviconFetcher
import guru.freberg.dlm.ui.util.SiteIcon
import guru.freberg.dlm.ui.util.SiteIconKeyer
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

    /** Coil image loader for site favicons. [FaviconFetcher] resolves a
     * non-bundled host by parsing its page for `<link rel="icon">` (largest
     * declared size wins) with a /favicon.ico fallback; a shared OkHttp client
     * (HTTP-cached on disk) serves both that and any plain https image, so the
     * fetch works regardless of classpath auto-detection. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .cache(Cache(context.cacheDir.resolve("favicon_http_cache"), 8L * 1024 * 1024))
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(SiteIconKeyer(), SiteIcon::class)
                add(FaviconFetcher.Factory(client), SiteIcon::class)
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
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
}
