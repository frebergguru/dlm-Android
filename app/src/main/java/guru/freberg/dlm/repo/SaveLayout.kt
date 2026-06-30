// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.repo

import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.ui.util.hostOf

/**
 * Relative SAF subfolder a finished [link] is saved into:
 * - a multi-file item (archive.org item, or a yt-dlp playlist/season) goes under
 *   `<host>/<item title>/`, so all of its files stay together,
 * - a single file (one video, a generic download) goes under just `<host>/`.
 *
 * [pkgName] maps package id -> package title (the resolved item title). Shared by the
 * automatic post-download move and the manual "Save to folder" action so both lay
 * files out identically.
 */
internal fun saveSubDir(link: LinkSnap, pkgName: Map<Long, String>): String? {
    val host = hostOf(link.url).takeIf { it.isNotEmpty() }
    val title = if (link.packageId > 0)
        pkgName[link.packageId]?.trim()?.takeIf { it.isNotEmpty() } else null
    return listOfNotNull(host, title).joinToString("/").ifEmpty { null }
}
