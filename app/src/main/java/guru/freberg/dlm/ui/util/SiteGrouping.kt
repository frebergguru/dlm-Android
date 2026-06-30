// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.util

import java.net.URI

/**
 * Pure-Kotlin mirrors of the desktop GTK port's link helpers (gui/main.c @
 * 91acb67): clipboard URL detection and site grouping. Kept free of Android
 * dependencies so they can be unit-tested for parity with the C originals.
 */

private val URL_SCHEMES = listOf("http://", "https://", "ftp://", "ftps://")
private val WS = charArrayOf(' ', '\t', '\n', '\r')

/**
 * If [text] (e.g. clipboard contents) begins with a supported URL scheme, return
 * the first whitespace-delimited token; otherwise null. Mirrors `detect_url()`:
 * leading whitespace is trimmed, the scheme check is case-insensitive, and only
 * the first token is taken (keeps the extractor off non-link clipboard text).
 */
fun detectUrl(text: String?): String? {
    if (text == null) return null
    val trimmed = text.trimStart(*WS)
    val lower = trimmed.lowercase()
    if (URL_SCHEMES.none { lower.startsWith(it) }) return null
    val token = trimmed.takeWhile { it !in WS }
    return token.ifEmpty { null }
}

// Schemes we refuse to hand to the resolver / native curl engine: local-file,
// IPC and script vectors, plus `magnet:` — neither yt-dlp nor libcurl speaks
// BitTorrent, so a magnet would only fail at download time with a generic
// network error; reject it up front instead. http(s)/ftp(s) and schemeless
// host-like input (which yt-dlp/curl resolve to https) are fine.
private val BLOCKED_SCHEMES = listOf(
    "file:", "content:", "intent:", "javascript:", "data:", "blob:", "about:", "jar:",
    "magnet:",
)

/**
 * Reject obviously-unsafe or unsupported download input before it reaches yt-dlp /
 * libcurl: option-injection (a leading '-', which yt-dlp would parse as a CLI flag
 * such as `--exec`) and non-network schemes (incl. `magnet:`, which the engine
 * can't download). Lenient on schemeless input so yt-dlp's host-only URLs keep
 * working. Defense-in-depth alongside the `--` argument separator and curl's
 * CURLOPT_PROTOCOLS restriction.
 */
fun isSafeDownloadInput(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t.isEmpty() || t.startsWith("-")) return false
    val lower = t.lowercase()
    return BLOCKED_SCHEMES.none { lower.startsWith(it) }
}

/**
 * Bare host of [url] for site grouping. Mirrors `host_of()`: strips the scheme
 * (`://`), userinfo (`user@`), then takes up to the first `/ : ? #`, lowercased,
 * dropping a leading `www.`.
 */
fun hostOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val schemeIdx = url.indexOf("://")
    val rest = if (schemeIdx >= 0) url.substring(schemeIdx + 3) else url
    // The authority ends at the first '/', '?' or '#'. A '@' is userinfo only
    // when it falls inside that authority; a '@' in a query/fragment must not be
    // mistaken for a userinfo delimiter.
    val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) rest.length else it }
    val at = rest.indexOf('@')
    val start = if (at in 0 until authEnd) at + 1 else 0
    val sb = StringBuilder()
    var i = start
    while (i < rest.length) {
        val c = rest[i]
        if (c == '/' || c == ':' || c == '?' || c == '#') break
        sb.append(c.lowercaseChar())
        i++
    }
    var host = sb.toString()
    if (host.startsWith("www.")) host = host.substring(4)
    return host
}

/** Human-friendly display name for a [host]. Mirrors `site_label()`. */
fun siteLabel(host: String): String = when {
    host.isEmpty() -> "Other links"
    host.contains("archive.org") -> "Internet Archive"
    host.contains("youtube") || host.contains("youtu.be") ||
        host.contains("ytimg") || host.contains("googlevideo") -> "YouTube"
    host.contains("vimeo") -> "Vimeo"
    host.contains("soundcloud") -> "SoundCloud"
    else -> host
}

/** Well-known sites we ship a pre-rendered PNG favicon for, under
 * assets/favicons/<domain>.png. These are bundled because many sites serve only
 * an `.ico` favicon, which Android's image decoder can't read — and so it never
 * loads a third-party favicon service at runtime (no host leaks off-device). */
private val BUNDLED_FAVICONS = setOf(
    "archive.org", "wikipedia.org", "wikimedia.org", "youtube.com", "vimeo.com",
    "dailymotion.com", "soundcloud.com", "bandcamp.com", "github.com", "gitlab.com",
    "sourceforge.net", "reddit.com", "twitter.com", "x.com", "facebook.com",
    "tiktok.com", "twitch.tv", "imgur.com", "mediafire.com", "mega.nz",
    "dropbox.com", "flickr.com", "deviantart.com", "tumblr.com", "pixiv.net",
    "nrk.no", "bbc.co.uk", "bbc.com", "rumble.com", "vgtv.no",
)

/** A bundled-asset model for [host] if it (or its parent domain) is well-known. */
private fun bundledFavicon(host: String): String? {
    val h = host.lowercase()
    val key = BUNDLED_FAVICONS.firstOrNull { h == it || h.endsWith(".$it") } ?: return null
    return "file:///android_asset/favicons/$key.png"
}

/**
 * A non-bundled site whose favicon must be discovered at load time by parsing
 * its HTML for `<link rel="icon">` (falling back to /favicon.ico). Used as a
 * Coil image model so [FaviconFetcher] resolves and fetches it off the UI thread.
 */
data class SiteIcon(val host: String)

/** Coil image model for a real [host]'s site icon, or null for a blank host.
 * A well-known site uses its bundled PNG; any other host becomes a [SiteIcon]
 * so its page is parsed for the best `<link rel="icon">` at load time. */
fun faviconModel(host: String): Any? = when {
    host.isEmpty() -> null
    else -> bundledFavicon(host) ?: SiteIcon(host)
}

private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)

/** Value of a [tag]'s [name] attribute (double/single/unquoted), or null. */
private fun tagAttr(tag: String, name: String): String? {
    val m = Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", RegexOption.IGNORE_CASE)
        .find(tag) ?: return null
    return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }.ifEmpty { null }
}

/** Largest pixel dimension declared in a `sizes` attribute (e.g. "32x32 16x16"
 * -> 32), or 0 when absent, "any", or unparseable. */
private fun maxDeclaredSize(sizes: String?): Int {
    if (sizes == null) return 0
    return Regex("(\\d+)\\s*[xX]\\s*(\\d+)").findAll(sizes)
        .flatMap { sequenceOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        .maxOrNull() ?: 0
}

/** Higher = more likely to decode on Android. ICO/SVG aren't handled by the
 * platform image decoder, so they rank below raster formats. */
private fun formatRank(url: String): Int =
    when (url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()) {
        "png", "webp", "jpg", "jpeg", "gif" -> 2
        "ico", "svg" -> 0
        else -> 1
    }

/** Resolve [href] against [pageUrl] to an absolute http(s) URL, or null. */
private fun resolveUrl(pageUrl: String, href: String): String? = try {
    URI(pageUrl).resolve(URI(href)).toString()
        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
} catch (_: Exception) {
    null
}

/**
 * Pick the best icon URL declared by a page's `<link rel="icon">` tags (also
 * `shortcut icon`, `apple-touch-icon`, `mask-icon`), resolved absolute against
 * [pageUrl]. Prefers the largest declared `sizes`; among equal sizes, prefers a
 * format Android can decode (png/webp/jpg over ico/svg). Returns null when the
 * HTML declares no usable icon link.
 */
fun selectIconHref(html: String, pageUrl: String): String? {
    var best: Triple<String, Int, Int>? = null // url, declared size, format rank
    for (tag in LINK_TAG.findAll(html)) {
        val t = tag.value
        if (tagAttr(t, "rel")?.contains("icon", ignoreCase = true) != true) continue
        val href = tagAttr(t, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        if (href.startsWith("data:", ignoreCase = true)) continue
        val url = resolveUrl(pageUrl, href) ?: continue
        val size = maxDeclaredSize(tagAttr(t, "sizes"))
        val rank = formatRank(url)
        val better = best == null || if (size != best.second) size > best.second else rank > best.third
        if (better) best = Triple(url, size, rank)
    }
    return best?.first
}
