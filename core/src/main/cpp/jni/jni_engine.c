/* JNI: NativeEngine — drives the segmented/resumable download engine
 * (download.c) verbatim. dlm_download_file blocks on the calling thread and
 * invokes the progress callback on that same thread, so the JNIEnv handed to
 * the native method is valid for the upcall — no AttachCurrentThread needed.
 * Kotlin calls this on Dispatchers.IO. */
#include "jni_common.h"
#include "dlm/dlm.h"

#include <stdlib.h>
#include <stdint.h>

typedef struct {
    JNIEnv *env;
    jobject sink;  /* ProgressSink, valid for the duration of nDownload */
} prog_ctx;

static void prog_cb(void *ud, int64_t done, int64_t total, double bps)
{
    prog_ctx *p = ud;
    if (!p->sink) return;
    JNIEnv *env = p->env;
    (*env)->CallVoidMethod(env, p->sink, g_jni.progressOnProgress,
                           (jlong)done, (jlong)total, (jdouble)bps);
    /* A misbehaving sink must not abort the download. */
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Build a NULL-terminated "Key: Value" char** from a Java String[]. */
static char **headers_dup(JNIEnv *env, jobjectArray arr, int *out_n)
{
    if (!arr) { *out_n = 0; return NULL; }
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n <= 0) { *out_n = 0; return NULL; }
    char **h = calloc((size_t)n + 1, sizeof *h);
    if (!h) { *out_n = 0; return NULL; }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        h[i] = jstr_dup(env, s);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    *out_n = (int)n;
    return h;
}

static void headers_free(char **h, int n)
{
    if (!h) return;
    for (int i = 0; i < n; i++) free(h[i]);
    free(h);
}

/* A heap cancel cell shared with the JVM: Kotlin flips it via nCancel. */
JNIEXPORT jlong JNICALL
Java_com_dlm_core_jni_NativeEngine_nAllocCancel(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    volatile int *cell = calloc(1, sizeof *cell);
    return (jlong)(intptr_t)cell;
}

JNIEXPORT void JNICALL
Java_com_dlm_core_jni_NativeEngine_nCancel(JNIEnv *env, jobject thiz, jlong cell)
{
    (void)env; (void)thiz;
    if (cell) *(volatile int *)(intptr_t)cell = 1;
}

JNIEXPORT void JNICALL
Java_com_dlm_core_jni_NativeEngine_nFreeCancel(JNIEnv *env, jobject thiz, jlong cell)
{
    (void)env; (void)thiz;
    free((void *)(intptr_t)cell);
}

JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeEngine_nDownload(JNIEnv *env, jobject thiz,
        jstring url, jstring outPath, jint connections, jlong minSplit,
        jint maxRetries, jlong maxSpeed, jobjectArray headers, jlong cancelCell,
        jobject sink)
{
    (void)thiz;
    char *u = jstr_dup(env, url);
    char *o = jstr_dup(env, outPath);
    int hn = 0;
    char **h = headers_dup(env, headers, &hn);

    prog_ctx pc = { env, sink };

    dlm_options opt;
    memset(&opt, 0, sizeof opt);
    opt.url = u;
    opt.out_path = o;
    opt.connections = connections;
    opt.min_split_size = minSplit;
    opt.max_retries = maxRetries;
    opt.max_speed = maxSpeed;
    opt.headers = (const char *const *)h;
    opt.on_progress = sink ? prog_cb : NULL;
    opt.userdata = &pc;
    opt.cancel = cancelCell ? (volatile int *)(intptr_t)cancelCell : NULL;

    dlm_result rc = dlm_download_file(&opt);

    free(u);
    free(o);
    headers_free(h, hn);
    return (jint)rc;
}
