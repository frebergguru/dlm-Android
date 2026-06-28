/* JNI: NativeVerify — MD5/SHA1 checksum verification (verify.c). */
#include "jni_common.h"
#include "dlm/verify.h"

#include <stdlib.h>

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeVerify_nMd5(JNIEnv *env, jobject thiz,
        jstring path, jstring expected)
{
    (void)thiz;
    char *p = jstr_dup(env, path), *e = jstr_dup(env, expected);
    int r = (p && e) ? dlm_verify_md5(p, e) : DLM_VERIFY_ERROR;
    free(p); free(e);
    return r;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeVerify_nSha1(JNIEnv *env, jobject thiz,
        jstring path, jstring expected)
{
    (void)thiz;
    char *p = jstr_dup(env, path), *e = jstr_dup(env, expected);
    int r = (p && e) ? dlm_verify_sha1(p, e) : DLM_VERIFY_ERROR;
    free(p); free(e);
    return r;
}
