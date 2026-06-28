package guru.freberg.dlm.ui.util

/**
 * Pure-Kotlin mirrors of the desktop GTK port's link helpers (gui/main.c @
 * 91acb67): clipboard URL detection and site grouping. Kept free of Android
 * dependencies so they can be unit-tested for parity with the C originals.
 */

private val URL_SCHEMES = listOf("http://", "https://", "ftp://", "ftps://", "magnet:")
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

/**
 * Bare host of [url] for site grouping. Mirrors `host_of()`: strips the scheme
 * (`://`), userinfo (`user@`), then takes up to the first `/ : ? #`, lowercased,
 * dropping a leading `www.`. Schemeless URLs like `magnet:?…` yield `"magnet"`
 * (the scan stops at the first `:`), which [siteLabel] buckets as magnet links.
 */
fun hostOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val schemeIdx = url.indexOf("://")
    val rest = if (schemeIdx >= 0) url.substring(schemeIdx + 3) else url
    val slash = rest.indexOf('/')
    val at = rest.indexOf('@')
    val start = if (at >= 0 && (slash < 0 || at < slash)) at + 1 else 0
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
    host == "magnet" -> "Magnet links"
    else -> host
}

/** The favicon URL for a real [host], or null for blank/magnet (no real site). */
fun faviconUrl(host: String): String? =
    if (host.isEmpty() || host == "magnet") null else "https://$host/favicon.ico"
