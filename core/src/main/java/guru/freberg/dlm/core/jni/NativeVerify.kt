// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.core.jni

/** MD5/SHA1 verification (verify.c). Result codes match `dlm_verify_*`. */
object NativeVerify {
    const val OK = 0
    const val MISMATCH = 1
    const val ERROR = -1

    fun verifyMd5(path: String, expectedHex: String): Int = nMd5(path, expectedHex)
    fun verifySha1(path: String, expectedHex: String): Int = nSha1(path, expectedHex)

    private external fun nMd5(path: String, expected: String): Int
    private external fun nSha1(path: String, expected: String): Int
}
