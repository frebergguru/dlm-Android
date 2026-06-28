// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.core.jni

/**
 * Internet Archive credentials (iaauth.c). Anonymous downloads work without
 * any of this; sign-in unlocks restricted items and higher rate limits.
 */
object NativeAuth {
    const val MODE_NONE = 0
    const val MODE_S3 = 1
    const val MODE_COOKIE = 2

    /** Current auth mode (one of the MODE_* constants). */
    fun mode(): Int = nMode()

    /** Human-readable description of [m] (e.g. "signed in (S3)"). */
    fun modeString(m: Int = mode()): String = nModeStr(m)

    fun saveS3(access: String, secret: String): Int = nSaveS3(access, secret)
    fun saveCookie(cookie: String): Int = nSaveCookie(cookie)
    fun logout(): Int = nLogout()

    /** @return null on success, or an error message on failure. */
    fun loginPassword(email: String, password: String): String? = nLoginPassword(email, password)

    private external fun nMode(): Int
    private external fun nModeStr(mode: Int): String
    private external fun nSaveS3(access: String, secret: String): Int
    private external fun nSaveCookie(cookie: String): Int
    private external fun nLogout(): Int
    private external fun nLoginPassword(email: String, password: String): String?
}
