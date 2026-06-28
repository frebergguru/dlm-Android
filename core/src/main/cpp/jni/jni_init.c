/* JNI lifecycle: cache model classes, route XDG/CA config into the C core. */
#include "jni_common.h"
#include "dlm/dlm.h"

#include <stdlib.h>

/* From libdlm util.c — set the TLS CA bundle path (libcurl has no usable
 * default on Android and does not read the CURL_CA_BUNDLE env var). */
void dlm_set_ca_bundle(const char *path);

dlm_jni_cache g_jni;

static jclass global_ref(JNIEnv *env, const char *name)
{
    jclass local = (*env)->FindClass(env, name);
    if (!local) return NULL;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return global;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    g_jni.storeRow = global_ref(env, "com/dlm/core/model/StoreRow");
    g_jni.pkgRow = global_ref(env, "com/dlm/core/model/PackageRow");
    g_jni.task = global_ref(env, "com/dlm/core/model/Task");
    g_jni.extractResult = global_ref(env, "com/dlm/core/model/ExtractResult");
    g_jni.progressSink = global_ref(env, "com/dlm/core/jni/ProgressSink");
    if (!g_jni.storeRow || !g_jni.pkgRow || !g_jni.task ||
        !g_jni.extractResult || !g_jni.progressSink)
        return JNI_ERR;

    g_jni.storeRowCtor = (*env)->GetMethodID(env, g_jni.storeRow, "<init>",
        "(JLjava/lang/String;Ljava/lang/String;IIJJLjava/lang/String;"
        "Ljava/lang/String;JJIIILjava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;JI)V");
    g_jni.pkgRowCtor = (*env)->GetMethodID(env, g_jni.pkgRow, "<init>",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;IIJJ)V");
    g_jni.taskCtor = (*env)->GetMethodID(env, g_jni.task, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;"
        "Ljava/lang/String;[Ljava/lang/String;I)V");
    g_jni.extractResultCtor = (*env)->GetMethodID(env, g_jni.extractResult, "<init>",
        "(Ljava/lang/String;[Lcom/dlm/core/model/Task;Z)V");
    g_jni.progressOnProgress = (*env)->GetMethodID(env, g_jni.progressSink,
        "onProgress", "(JJD)V");
    if (!g_jni.storeRowCtor || !g_jni.pkgRowCtor || !g_jni.taskCtor ||
        !g_jni.extractResultCtor || !g_jni.progressOnProgress)
        return JNI_ERR;

    return JNI_VERSION_1_6;
}

/* NativeLib.nativeInit(configDir, caBundlePath): point libdlm's XDG/HOME and
 * libcurl's CA bundle at app-private locations, then run the curl global init.
 * iaauth.c reads XDG_CONFIG_HOME; libcurl honours CURL_CA_BUNDLE at handle
 * creation, so neither vendored file needs editing. */
JNIEXPORT jint JNICALL
Java_com_dlm_core_jni_NativeLib_nativeInit(JNIEnv *env, jobject thiz,
                                           jstring configDir, jstring caBundle)
{
    (void)thiz;
    char *cfg = jstr_dup(env, configDir);
    char *ca = jstr_dup(env, caBundle);
    if (cfg && *cfg) {
        setenv("XDG_CONFIG_HOME", cfg, 1);
        setenv("XDG_DATA_HOME", cfg, 1);
        setenv("HOME", cfg, 1);
    }
    // Apply the CA bundle as CURLOPT_CAINFO on every handle (env var is ignored
    // by libcurl itself).
    dlm_set_ca_bundle(ca);
    free(cfg);
    free(ca);
    return (jint)dlm_global_init();
}

JNIEXPORT void JNICALL
Java_com_dlm_core_jni_NativeLib_nativeCleanup(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    dlm_global_cleanup();
}

JNIEXPORT jstring JNICALL
Java_com_dlm_core_jni_NativeLib_version(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return jstr_new(env, dlm_version());
}
