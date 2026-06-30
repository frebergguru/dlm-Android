// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm

import guru.freberg.dlm.ui.util.SiteIcon
import guru.freberg.dlm.ui.util.detectUrl
import guru.freberg.dlm.ui.util.faviconModel
import guru.freberg.dlm.ui.util.hostOf
import guru.freberg.dlm.ui.util.isSafeDownloadInput
import guru.freberg.dlm.ui.util.selectIconHref
import guru.freberg.dlm.ui.util.siteLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity checks for the link helpers against the desktop GTK port's C originals
 * (gui/main.c @ 91acb67): `detect_url()`, `host_of()`, `site_label()`.
 */
class SiteGroupingTest {

    @Test fun detectUrl_acceptsSupportedSchemes() {
        assertEquals("https://a.com/x", detectUrl("https://a.com/x"))
        assertEquals("http://a.com", detectUrl("http://a.com"))
        assertEquals("ftp://a.com/f", detectUrl("ftp://a.com/f"))
        assertEquals("ftps://a.com/f", detectUrl("ftps://a.com/f"))
    }

    @Test fun detectUrl_trimsLeadingWhitespaceAndTakesFirstToken() {
        assertEquals("https://a.com", detectUrl("  \t https://a.com extra words"))
        assertEquals("https://a.com", detectUrl("https://a.com\nsecond line"))
    }

    @Test fun detectUrl_caseInsensitiveScheme() {
        assertEquals("HTTPS://A.com", detectUrl("HTTPS://A.com"))
    }

    @Test fun detectUrl_rejectsNonLinks() {
        assertNull(detectUrl(null))
        assertNull(detectUrl(""))
        assertNull(detectUrl("just some text"))
        assertNull(detectUrl("file:///etc/passwd"))
        assertNull(detectUrl("ssh://host"))
        // magnet/BitTorrent is unsupported by the yt-dlp/curl engine
        assertNull(detectUrl("magnet:?xt=urn:btih:HASH"))
    }

    @Test fun hostOf_stripsSchemePortPathAndWww() {
        assertEquals("example.com", hostOf("https://www.example.com/path?q=1#frag"))
        assertEquals("example.com", hostOf("http://example.com:8080/x"))
        assertEquals("example.com", hostOf("https://example.com"))
        assertEquals("a.b.example.com", hostOf("https://a.b.example.com/x"))
    }

    @Test fun hostOf_dropsUserinfo() {
        assertEquals("example.com", hostOf("https://user:pass@example.com/x"))
        // an '@' after the first slash is part of the path, not userinfo
        assertEquals("example.com", hostOf("https://example.com/a@b"))
    }

    @Test fun hostOf_lowercases() {
        assertEquals("example.com", hostOf("HTTPS://Example.COM/Path"))
    }

    @Test fun hostOf_schemelessInputUsesHost() {
        // No "://": the whole string is the authority up to the first '/ ? #'.
        assertEquals("example.com", hostOf("example.com/path"))
        // an '@' inside a schemeless query must not be treated as userinfo
        assertEquals("example.com", hostOf("example.com?dn=John@Doe"))
    }

    @Test fun hostOf_ignoresAtInQueryOrFragment() {
        // '@' in the query/fragment (no path) is not userinfo
        assertEquals("example.com", hostOf("https://example.com?redirect=user@host"))
        assertEquals("example.com", hostOf("https://example.com#x@y"))
    }

    @Test fun hostOf_emptyForBlank() {
        assertEquals("", hostOf(null))
        assertEquals("", hostOf(""))
    }

    @Test fun siteLabel_friendlyNames() {
        assertEquals("Internet Archive", siteLabel("archive.org"))
        assertEquals("Internet Archive", siteLabel("ia801500.us.archive.org"))
        assertEquals("YouTube", siteLabel("youtube.com"))
        assertEquals("YouTube", siteLabel("youtu.be"))
        assertEquals("YouTube", siteLabel("i.ytimg.com"))
        assertEquals("YouTube", siteLabel("r1---sn-x.googlevideo.com"))
        assertEquals("Vimeo", siteLabel("vimeo.com"))
        assertEquals("SoundCloud", siteLabel("soundcloud.com"))
        assertEquals("Other links", siteLabel(""))
        assertEquals("example.com", siteLabel("example.com"))
    }

    @Test fun isSafeDownloadInput_allowsRealLinks() {
        assertTrue(isSafeDownloadInput("https://a.com/x"))
        assertTrue(isSafeDownloadInput("http://a.com"))
        assertTrue(isSafeDownloadInput("ftp://a.com/f"))
        // schemeless host-like input stays allowed (yt-dlp/curl resolve it)
        assertTrue(isSafeDownloadInput("youtube.com/watch?v=x"))
        assertTrue(isSafeDownloadInput("  https://a.com/x  "))
    }

    @Test fun isSafeDownloadInput_blocksInjectionAndBadSchemes() {
        // yt-dlp option injection
        assertFalse(isSafeDownloadInput("--exec=sh -c 'id'"))
        assertFalse(isSafeDownloadInput("-o/data/x"))
        // local-file / IPC / script schemes (case-insensitive)
        assertFalse(isSafeDownloadInput("file:///etc/passwd"))
        assertFalse(isSafeDownloadInput("FILE:///etc/passwd"))
        assertFalse(isSafeDownloadInput("content://settings/secure"))
        assertFalse(isSafeDownloadInput("intent://x#Intent;end"))
        assertFalse(isSafeDownloadInput("javascript:alert(1)"))
        assertFalse(isSafeDownloadInput("data:text/html,x"))
        // magnet/BitTorrent: not supported by the yt-dlp/curl engine
        assertFalse(isSafeDownloadInput("magnet:?xt=urn:btih:HASH"))
        assertFalse(isSafeDownloadInput("MAGNET:?xt=urn:btih:HASH"))
        // empties
        assertFalse(isSafeDownloadInput(null))
        assertFalse(isSafeDownloadInput("   "))
    }

    @Test fun faviconModel_bundledOrDiscovered() {
        // Well-known site: its bundled PNG asset.
        assertEquals("file:///android_asset/favicons/github.com.png", faviconModel("github.com"))
        // Any other host: discovered at load time from its HTML.
        assertEquals(SiteIcon("example.com"), faviconModel("example.com"))
        assertNull(faviconModel(""))
    }

    @Test fun selectIconHref_resolvesRelativeHref() {
        val html = """<link rel="icon" href="/img/icon.png">"""
        assertEquals("https://example.com/img/icon.png", selectIconHref(html, "https://example.com/page"))
    }

    @Test fun selectIconHref_resolvesProtocolRelativeHref() {
        val html = """<link rel="icon" href="//cdn.example.net/i.png">"""
        assertEquals("https://cdn.example.net/i.png", selectIconHref(html, "https://example.com/"))
    }

    @Test fun selectIconHref_prefersLargestDeclaredSize() {
        val html = """
            <link rel="icon" sizes="16x16" href="/small.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/large.png">
            <link rel="icon" sizes="32x32" href="/medium.png">
        """.trimIndent()
        assertEquals("https://example.com/large.png", selectIconHref(html, "https://example.com/"))
    }

    @Test fun selectIconHref_prefersDecodableFormatOnSizeTie() {
        val html = """
            <link rel="shortcut icon" href="/favicon.ico">
            <link rel="icon" href="/favicon.png">
        """.trimIndent()
        assertEquals("https://example.com/favicon.png", selectIconHref(html, "https://example.com/"))
    }

    @Test fun selectIconHref_ignoresNonIconAndDataLinks() {
        val html = """
            <link rel="stylesheet" href="/style.css">
            <link rel="icon" href="data:image/png;base64,AAAA">
        """.trimIndent()
        assertNull(selectIconHref(html, "https://example.com/"))
    }
}
