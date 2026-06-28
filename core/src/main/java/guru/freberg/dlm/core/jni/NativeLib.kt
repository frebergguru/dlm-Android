// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.core.jni

/**
 * Process-wide native lifecycle + shared utilities. Load and initialise once
 * (from the Service / Application) before using any other Native* wrapper.
 *
 * The `external` function names below map 1:1 to the JNI symbols in
 * jni_init.c / jni_util.c — do not rename them in isolation.
 */
object NativeLib {

    @Volatile private var initialised = false

    init {
        System.loadLibrary("dlmcore")
    }

    /**
     * Route libdlm's config/credential storage to [configDir] (an app-private
     * path) and libcurl's CA trust to [caBundlePath] (a shipped cacert.pem), then
     * run the one-time curl global init. Idempotent.
     *
     * @return libdlm result code (0 == DLM_OK).
     */
    @Synchronized
    fun ensureInit(configDir: String, caBundlePath: String): Int {
        if (initialised) return 0
        val rc = nativeInit(configDir, caBundlePath)
        if (rc == 0) initialised = true
        return rc
    }

    @Synchronized
    fun shutdown() {
        if (initialised) {
            nativeCleanup()
            initialised = false
        }
    }

    /** Library version string. */
    external fun version(): String

    /** Parse a human transfer rate ("500k", "2M", "1.5g") into bytes/sec; 0 = unlimited. */
    external fun parseRate(s: String): Long

    /** Format a bytes/sec rate into a compact human string ("1.5 MiB/s"). */
    external fun formatRate(bps: Long): String

    private external fun nativeInit(configDir: String, caBundlePath: String): Int
    private external fun nativeCleanup()
}
