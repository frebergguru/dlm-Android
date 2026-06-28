package com.dlm.core.jni

import com.dlm.core.model.ExtractResult

/**
 * URL extraction. [extract] resolves archive.org items and direct file links
 * natively; for anything else it returns [ExtractResult.needsYtdlp] = true so
 * the JVM yt-dlp runtime can resolve it and feed the JSON back through
 * [parseYtdlp] (which calls the verbatim dlm_ytdlp_parse).
 */
object NativeExtract {

    fun extract(url: String): ExtractResult = nExtract(url)

    fun parseYtdlp(json: String, url: String?): ExtractResult = nParseYtdlp(json, url)

    fun isArchiveOrg(url: String): Boolean = nIsArchiveOrg(url)

    fun filenameFromUrl(url: String): String = nFilenameFromUrl(url)

    private external fun nExtract(url: String): ExtractResult
    private external fun nParseYtdlp(json: String, url: String?): ExtractResult
    private external fun nIsArchiveOrg(url: String): Boolean
    private external fun nFilenameFromUrl(url: String): String
}
