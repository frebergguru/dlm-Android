/* JNI: NativeAuth — Internet Archive credential storage (iaauth.c). The
 * credentials file lives under the app-private config dir (routed via
 * XDG_CONFIG_HOME in nativeInit); the JVM additionally wraps it with
 * EncryptedFile, so secrets never sit in plaintext on disk. */
#include "jni_common.h"
#include "dlm/iaauth.h"

#include <stdlib.h>

JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeAuth_nMode(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    ia_credentials c;
    memset(&c, 0, sizeof c);
    dlm_ia_load(&c);
    ia_auth_mode m = c.mode;
    dlm_ia_credentials_free(&c);
    return (jint)m;
}

JNIEXPORT jstring JNICALL
Java_com_dlm_core_jni_NativeAuth_nModeStr(JNIEnv *env, jobject thiz, jint mode)
{
    (void)thiz;
    return jstr_new(env, dlm_ia_mode_str((ia_auth_mode)mode));
}

JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeAuth_nSaveS3(JNIEnv *env, jobject thiz,
        jstring access, jstring secret)
{
    (void)thiz;
    char *a = jstr_dup(env, access), *s = jstr_dup(env, secret);
    int r = (a && s) ? dlm_ia_save_s3(a, s) : -1;
    free(a); free(s);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeAuth_nSaveCookie(JNIEnv *env, jobject thiz, jstring cookie)
{
    (void)thiz;
    char *c = jstr_dup(env, cookie);
    int r = c ? dlm_ia_save_cookie(c) : -1;
    free(c);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeAuth_nLogout(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return dlm_ia_logout();
}

/* Returns null on success, or a human-readable error message on failure. */
JNIEXPORT jstring JNICALL
Java_com_dlm_core_jni_NativeAuth_nLoginPassword(JNIEnv *env, jobject thiz,
        jstring email, jstring password)
{
    (void)thiz;
    char *e = jstr_dup(env, email), *p = jstr_dup(env, password);
    char *err = NULL;
    int rc = (e && p) ? dlm_ia_login_password(e, p, &err) : -1;
    free(e); free(p);
    jstring out = NULL;
    if (rc != 0)
        out = jstr_new(env, err ? err : "login failed");
    free(err);
    return out;
}
