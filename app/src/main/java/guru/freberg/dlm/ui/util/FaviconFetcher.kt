// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.util

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** Memory/disk cache key for a [SiteIcon]: just its host, so every screen that
 * shows the same site shares one fetched icon. */
class SiteIconKeyer : Keyer<SiteIcon> {
    override fun key(data: SiteIcon, options: Options) = "siteicon:${data.host}"
}

/**
 * Coil fetcher for a [SiteIcon]: GETs the site's home page and parses its
 * `<link rel="icon">` tags for the best icon (see [selectIconHref]), then
 * fetches that. On any failure — no icon link, an undecodable/missing icon, a
 * network error — it falls back to the conventional `/favicon.ico`. Runs on the
 * IO dispatcher and hands the raw bytes to Coil's decoders.
 */
class FaviconFetcher(
    private val data: SiteIcon,
    private val options: Options,
    private val client: OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val discovered = runCatching { discoverIcon() }.getOrNull()
        val fallback = "https://${data.host}/favicon.ico"
        for (url in listOfNotNull(discovered, fallback).distinct()) {
            runCatching { load(url) }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    /** Fetch the home page and return the best declared icon URL, or null. */
    private fun discoverIcon(): String? {
        get("https://${data.host}/").use { resp ->
            val body = resp.body ?: return null
            if (!resp.isSuccessful) return null
            val limited = Buffer()
            val src = body.source()
            while (limited.size < MAX_HTML_BYTES) {
                if (src.read(limited, MAX_HTML_BYTES - limited.size) == -1L) break
            }
            return selectIconHref(limited.readUtf8(), resp.request.url.toString())
        }
    }

    /** Fetch [url]'s bytes as a Coil source, or null if it isn't a usable image. */
    private fun load(url: String): SourceFetchResult? {
        val resp = get(url)
        val body = resp.body
        if (!resp.isSuccessful || body == null) {
            resp.close()
            return null
        }
        return SourceFetchResult(
            source = ImageSource(body.source(), options.fileSystem),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
    ).execute()

    class Factory(private val client: OkHttpClient) : Fetcher.Factory<SiteIcon> {
        override fun create(data: SiteIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            FaviconFetcher(data, options, client)
    }

    private companion object {
        /** Cap the home-page read; the icon links live in <head>, near the top. */
        const val MAX_HTML_BYTES = 256L * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
