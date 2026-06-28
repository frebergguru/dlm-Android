/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Shared helpers for the dlm JNI bridge. */
#ifndef DLM_JNI_COMMON_H
#define DLM_JNI_COMMON_H

#include <jni.h>
#include <stdlib.h>
#include <string.h>

/* Cached references resolved once in JNI_OnLoad (jni_init.c). */
typedef struct {
    jclass storeRow;
    jmethodID storeRowCtor;
    jclass pkgRow;
    jmethodID pkgRowCtor;
    jclass task;
    jmethodID taskCtor;
    jclass extractResult;
    jmethodID extractResultCtor;
    jclass progressSink;
    jmethodID progressOnProgress;  /* void onProgress(long done, long total, double bps) */
} dlm_jni_cache;

extern dlm_jni_cache g_jni;

/* Duplicate a Java string into a freshly malloc'd UTF-8 C string (NULL-safe).
 * Caller frees. Returns NULL when js is NULL. */
static inline char *jstr_dup(JNIEnv *env, jstring js)
{
    if (!js) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, js, NULL);
    if (!c) {
        /* GetStringUTFChars failed and left a pending OutOfMemoryError; clear it
         * so callers can safely keep making JNI calls (undefined otherwise). */
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    char *out = strdup(c);
    (*env)->ReleaseStringUTFChars(env, js, c);
    return out;
}

/* New Java string from a C string (NULL -> Java null).
 *
 * NewStringUTF() expects Java "modified UTF-8" (1-3 byte form, with U+0000 as
 * 0xC0 0x80 and supplementary code points as a surrogate PAIR each in 3-byte
 * CESU-8 form). Our inputs are standard UTF-8 (or arbitrary bytes): URLs,
 * URL-derived filenames, yt-dlp JSON, sqlite rows. A standard 4-byte UTF-8
 * sequence or any invalid byte fed straight to NewStringUTF() corrupts the
 * string or aborts the VM, so transcode here. Invalid/truncated input is
 * replaced with U+FFFD and we advance a single byte. */
static inline jstring jstr_new(JNIEnv *env, const char *s)
{
    if (!s) return NULL;

    const unsigned char *in = (const unsigned char *)s;
    size_t len = strlen(s);
    /* Worst case: a 4-byte input scalar expands to a surrogate pair, 6 bytes
     * out; every other case is <= 3 bytes out per input byte. 3*len+4 is a
     * safe upper bound (it also covers the +NUL terminator). */
    size_t cap = 3 * len + 4;
    unsigned char *out = (unsigned char *)malloc(cap);
    if (!out) return NULL;

    size_t i = 0, o = 0;
    while (i < len) {
        unsigned char b0 = in[i];
        unsigned int cp;
        int seqlen;

        if (b0 < 0x80) {
            cp = b0;
            seqlen = 1;
        } else if ((b0 & 0xE0) == 0xC0) {
            cp = b0 & 0x1F;
            seqlen = 2;
        } else if ((b0 & 0xF0) == 0xE0) {
            cp = b0 & 0x0F;
            seqlen = 3;
        } else if ((b0 & 0xF8) == 0xF0) {
            cp = b0 & 0x07;
            seqlen = 4;
        } else {
            cp = 0xFFFD;
            seqlen = -1; /* invalid lead byte */
        }

        if (seqlen > 0) {
            if (i + (size_t)seqlen > len) {
                cp = 0xFFFD;
                seqlen = -1; /* truncated */
            } else {
                int valid = 1;
                for (int k = 1; k < seqlen; k++) {
                    if ((in[i + (size_t)k] & 0xC0) != 0x80) { valid = 0; break; }
                    cp = (cp << 6) | (in[i + (size_t)k] & 0x3F);
                }
                if (!valid) { cp = 0xFFFD; seqlen = -1; }
                else {
                    /* Reject overlong encodings and out-of-range values. */
                    if ((seqlen == 2 && cp < 0x80) ||
                        (seqlen == 3 && cp < 0x800) ||
                        (seqlen == 4 && cp < 0x10000) ||
                        cp > 0x10FFFF ||
                        (cp >= 0xD800 && cp <= 0xDFFF)) {
                        cp = 0xFFFD;
                        seqlen = -1;
                    }
                }
            }
        }

        if (seqlen < 0) {
            i += 1;            /* advance one byte on error */
        } else {
            i += (size_t)seqlen;
        }

        /* Emit cp as modified UTF-8 / CESU-8. */
        if (cp == 0) {
            out[o++] = 0xC0;
            out[o++] = 0x80;
        } else if (cp < 0x80) {
            out[o++] = (unsigned char)cp;
        } else if (cp < 0x800) {
            out[o++] = (unsigned char)(0xC0 | (cp >> 6));
            out[o++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out[o++] = (unsigned char)(0xE0 | (cp >> 12));
            out[o++] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
            out[o++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else {
            /* Supplementary: emit UTF-16 surrogate pair, each as 3-byte CESU-8. */
            unsigned int v = cp - 0x10000;
            unsigned int hi = 0xD800 + (v >> 10);
            unsigned int lo = 0xDC00 + (v & 0x3FF);
            out[o++] = (unsigned char)(0xE0 | (hi >> 12));
            out[o++] = (unsigned char)(0x80 | ((hi >> 6) & 0x3F));
            out[o++] = (unsigned char)(0x80 | (hi & 0x3F));
            out[o++] = (unsigned char)(0xE0 | (lo >> 12));
            out[o++] = (unsigned char)(0x80 | ((lo >> 6) & 0x3F));
            out[o++] = (unsigned char)(0x80 | (lo & 0x3F));
        }
    }

    out[o] = '\0';
    jstring js = (*env)->NewStringUTF(env, (const char *)out);
    free(out);
    return js;
}

#endif /* DLM_JNI_COMMON_H */
