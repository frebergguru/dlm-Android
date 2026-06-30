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

/* Throw java.lang.RuntimeException(msg) if no exception is already pending.
 * Used to turn a C-side NULL result into a proper Java exception so a Kotlin
 * non-null return type never receives an unexpected null. */
static inline void jni_throw_runtime(JNIEnv *env, const char *msg)
{
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls) {
        (*env)->ThrowNew(env, cls, msg);
        (*env)->DeleteLocalRef(env, cls);
    }
}

/* Duplicate a Java string into a freshly malloc'd, standard-UTF-8 C string
 * (NULL-safe). Caller frees. Returns NULL when js is NULL or on allocation
 * failure.
 *
 * We deliberately convert from the UTF-16 string contents rather than via
 * GetStringUTFChars(): the latter returns Java "modified UTF-8" (CESU-8),
 * where a supplementary code point comes back as a 6-byte surrogate pair and
 * U+0000 as 0xC0 0x80. The C core (curl, sqlite, filename sanitizer, JSON)
 * expects standard UTF-8, and jstr_new() on the way out rejects lone
 * surrogates — so a CESU-8 round-trip would corrupt emoji/rare-CJK text. This
 * decoder emits a real 4-byte sequence for supplementary scalars. */
static inline char *jstr_dup(JNIEnv *env, jstring js)
{
    if (!js) return NULL;
    if ((*env)->ExceptionCheck(env)) return NULL;
    jsize ulen = (*env)->GetStringLength(env, js); /* UTF-16 code units */
    const jchar *u = (*env)->GetStringChars(env, js, NULL);
    if (!u) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    /* Max 3 UTF-8 bytes per BMP unit; a surrogate pair (2 units) yields a
     * 4-byte sequence, i.e. <= 3 bytes/unit. 3*ulen + 1 is a safe bound. */
    unsigned char *out = (unsigned char *)malloc((size_t)ulen * 3 + 1);
    if (!out) {
        (*env)->ReleaseStringChars(env, js, u);
        return NULL;
    }
    size_t o = 0;
    for (jsize i = 0; i < ulen; i++) {
        unsigned int cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < ulen) {
            unsigned int lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (lo - 0xDC00));
                i++;
            } else {
                cp = 0xFFFD; /* unpaired high surrogate */
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD; /* lone surrogate */
        }
        if (cp < 0x80) {
            out[o++] = (unsigned char)cp;
        } else if (cp < 0x800) {
            out[o++] = (unsigned char)(0xC0 | (cp >> 6));
            out[o++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out[o++] = (unsigned char)(0xE0 | (cp >> 12));
            out[o++] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
            out[o++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else {
            out[o++] = (unsigned char)(0xF0 | (cp >> 18));
            out[o++] = (unsigned char)(0x80 | ((cp >> 12) & 0x3F));
            out[o++] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
            out[o++] = (unsigned char)(0x80 | (cp & 0x3F));
        }
    }
    out[o] = '\0';
    (*env)->ReleaseStringChars(env, js, u);
    return (char *)out;
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
    /* Never call NewStringUTF() with an exception already pending (undefined per
     * spec). Callers issue several jstr_new() in a row before checking; if an
     * earlier one threw OOM, bail so the batch unwinds to the caller's check. */
    if ((*env)->ExceptionCheck(env)) return NULL;

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
