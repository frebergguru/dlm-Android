package com.dlm.core.jni

import com.dlm.core.model.DownloadOptions

/**
 * Drives the segmented/resumable download engine (download.c). [download]
 * blocks the calling thread until the transfer completes, fails or is
 * cancelled — call it from a background dispatcher (Dispatchers.IO).
 *
 * Cancellation uses a heap "cancel cell" shared with native code: allocate one
 * per active download, hand it to [download], flip it with [cancel] to abort,
 * and free it with [freeCancelToken] afterwards.
 */
object NativeEngine {

    fun newCancelToken(): Long = nAllocCancel()
    fun cancel(token: Long) = nCancel(token)
    fun freeCancelToken(token: Long) = nFreeCancel(token)

    /** @return libdlm result code (0 == DLM_OK, -6 == DLM_ERR_CANCELLED). */
    fun download(opt: DownloadOptions, cancelToken: Long, sink: ProgressSink?): Int =
        nDownload(
            opt.url, opt.outPath, opt.connections, opt.minSplitSize,
            opt.maxRetries, opt.maxSpeed, opt.headers, cancelToken, sink,
        )

    private external fun nAllocCancel(): Long
    private external fun nCancel(cell: Long)
    private external fun nFreeCancel(cell: Long)
    private external fun nDownload(
        url: String, outPath: String, connections: Int, minSplit: Long,
        maxRetries: Int, maxSpeed: Long, headers: Array<String>?,
        cancelCell: Long, sink: ProgressSink?,
    ): Int
}
