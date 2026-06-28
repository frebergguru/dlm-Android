package guru.freberg.dlm.core.jni

/**
 * Progress callback invoked by the native engine on the download thread.
 * `total < 0` means unknown size; `bps` is a smoothed bytes/sec estimate.
 *
 * The JNI bridge resolves this interface and its single method by the exact
 * name/signature `onProgress(JJD)V` — keep them in sync with jni_init.c.
 */
fun interface ProgressSink {
    fun onProgress(done: Long, total: Long, bps: Double)
}
