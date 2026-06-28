/* SPDX-License-Identifier: GPL-3.0-or-later */
/* JNI: NativeExtract — native URL resolution (archive.org + direct files) and
 * the verbatim yt-dlp JSON parser. Anything the native side can't resolve is
 * flagged needsYtdlp so the JVM YtdlpManager takes over. */
#include "jni_common.h"
#include "dlm/dlm.h"
#include "dlm/extract.h"
#include "extractors/ytdlp.h"
#include "internal.h"

#include <stdlib.h>

static jobjectArray headers_to_jarray(JNIEnv *env, char **headers)
{
    if (!headers) return NULL;
    int n = 0;
    while (headers[n]) n++;
    if (n == 0) return NULL;
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    if (!strCls) return NULL;
    jobjectArray arr = (*env)->NewObjectArray(env, n, strCls, NULL);
    (*env)->DeleteLocalRef(env, strCls);
    if (!arr) return NULL;
    for (int i = 0; i < n; i++) {
        jstring s = jstr_new(env, headers[i]);
        if ((*env)->ExceptionCheck(env)) {
            /* jstr_new left a pending exception; stop before more JNI work. */
            (*env)->DeleteLocalRef(env, s);
            (*env)->DeleteLocalRef(env, arr);
            return NULL;
        }
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

static jobject task_to_jobject(JNIEnv *env, const dlm_task *t)
{
    jstring url = jstr_new(env, t->url);
    jstring fn = jstr_new(env, t->filename);
    jstring md5 = jstr_new(env, t->md5);
    jstring sha1 = jstr_new(env, t->sha1);
    /* If a prior jstr_new left a pending exception, skip headers_to_jarray: it
     * calls FindClass, which is unsafe to invoke with an exception pending. */
    jobjectArray hdrs = (*env)->ExceptionCheck(env) ? NULL :
        headers_to_jarray(env, t->headers);
    /* hdrs may be NULL legitimately (no headers); only a pending exception from
     * the prior allocations means we must not call NewObject. */
    jobject o = (*env)->ExceptionCheck(env) ? NULL :
        (*env)->NewObject(env, g_jni.task, g_jni.taskCtor,
            url, fn, (jlong)t->size, md5, sha1, hdrs, (jint)t->delegate);
    (*env)->DeleteLocalRef(env, url);
    (*env)->DeleteLocalRef(env, fn);
    (*env)->DeleteLocalRef(env, md5);
    (*env)->DeleteLocalRef(env, sha1);
    (*env)->DeleteLocalRef(env, hdrs);
    return o;
}

static jobject result_to_jobject(JNIEnv *env, const dlm_extract_result *r,
                                 int needs_ytdlp)
{
    jstring source = jstr_new(env, r ? r->source : NULL);
    int count = r ? r->count : 0;
    jobjectArray tasks = (*env)->NewObjectArray(env, count, g_jni.task, NULL);
    if (!tasks) {
        (*env)->DeleteLocalRef(env, source);
        return NULL;
    }
    for (int i = 0; i < count; i++) {
        jobject t = task_to_jobject(env, &r->tasks[i]);
        if (!t) {
            /* task_to_jobject failed (possibly with a pending exception);
             * stop before issuing more JNI calls. */
            (*env)->DeleteLocalRef(env, source);
            (*env)->DeleteLocalRef(env, tasks);
            return NULL;
        }
        (*env)->SetObjectArrayElement(env, tasks, i, t);
        (*env)->DeleteLocalRef(env, t);
    }
    jobject res = (*env)->ExceptionCheck(env) ? NULL :
        (*env)->NewObject(env, g_jni.extractResult,
            g_jni.extractResultCtor, source, tasks,
            (jboolean)(needs_ytdlp ? 1 : 0));
    (*env)->DeleteLocalRef(env, source);
    (*env)->DeleteLocalRef(env, tasks);
    return res;
}

/* NativeExtract.nExtract(url): try native resolution. extract_direct never
 * fails, so a non-OK result on a non-archive.org URL means it fell through to
 * the (Android-stubbed) yt-dlp branch — flag it for the JVM runtime. */
JNIEXPORT jobject JNICALL
Java_guru_freberg_dlm_core_jni_NativeExtract_nExtract(JNIEnv *env, jobject thiz, jstring url)
{
    (void)thiz;
    char *u = jstr_dup(env, url);
    dlm_extract_result res;
    memset(&res, 0, sizeof res);
    int rc = u ? dlm_extract(u, &res) : DLM_ERR_ARG;

    jobject out;
    if (rc == DLM_OK) {
        out = result_to_jobject(env, &res, 0);
        dlm_extract_result_free(&res);
    } else {
        int needs = u && !dlm_is_archiveorg_url(u);
        out = result_to_jobject(env, NULL, needs);
    }
    free(u);
    return out;
}

/* NativeExtract.nParseYtdlp(json, url): feed yt-dlp -J output through the
 * verbatim dlm_ytdlp_parse(). */
JNIEXPORT jobject JNICALL
Java_guru_freberg_dlm_core_jni_NativeExtract_nParseYtdlp(JNIEnv *env, jobject thiz,
        jstring json, jstring url)
{
    (void)thiz;
    char *j = jstr_dup(env, json);
    char *u = jstr_dup(env, url);
    dlm_extract_result res;
    memset(&res, 0, sizeof res);
    int rc = j ? dlm_ytdlp_parse(j, u, &res) : DLM_ERR_ARG;
    jobject out;
    if (rc == DLM_OK) {
        out = result_to_jobject(env, &res, 0);
        dlm_extract_result_free(&res);
    } else {
        out = result_to_jobject(env, NULL, 0);
    }
    free(j);
    free(u);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_guru_freberg_dlm_core_jni_NativeExtract_nIsArchiveOrg(JNIEnv *env, jobject thiz, jstring url)
{
    (void)thiz;
    char *u = jstr_dup(env, url);
    jboolean r = (u && dlm_is_archiveorg_url(u)) ? JNI_TRUE : JNI_FALSE;
    free(u);
    return r;
}

JNIEXPORT jstring JNICALL
Java_guru_freberg_dlm_core_jni_NativeExtract_nFilenameFromUrl(JNIEnv *env, jobject thiz, jstring url)
{
    (void)thiz;
    char *u = jstr_dup(env, url);
    char *name = dlm_filename_from_url(u);
    jstring out = jstr_new(env, name);
    free(u);
    free(name);
    return out;
}
