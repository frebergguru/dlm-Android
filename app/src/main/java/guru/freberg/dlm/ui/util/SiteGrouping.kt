// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.util

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

/** The favicon URL for a real [host], or null for a blank host (no real site). */
fun faviconUrl(host: String): String? =
    if (host.isEmpty()) null else "https://$host/favicon.ico"
