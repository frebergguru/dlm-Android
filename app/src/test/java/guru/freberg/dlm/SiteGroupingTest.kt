package guru.freberg.dlm

import guru.freberg.dlm.ui.util.detectUrl
import guru.freberg.dlm.ui.util.faviconUrl
import guru.freberg.dlm.ui.util.hostOf
import guru.freberg.dlm.ui.util.siteLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("magnet:?xt=urn:btih:HASH", detectUrl("magnet:?xt=urn:btih:HASH"))
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

    @Test fun hostOf_magnetYieldsMagnet() {
        assertEquals("magnet", hostOf("magnet:?xt=urn:btih:HASH&dn=name"))
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
        assertEquals("Magnet links", siteLabel("magnet"))
        assertEquals("Other links", siteLabel(""))
        assertEquals("example.com", siteLabel("example.com"))
    }

    @Test fun faviconUrl_realHostsOnly() {
        assertEquals("https://example.com/favicon.ico", faviconUrl("example.com"))
        assertNull(faviconUrl(""))
        assertNull(faviconUrl("magnet"))
    }
}
