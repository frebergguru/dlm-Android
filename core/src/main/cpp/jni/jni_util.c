/* JNI: rate parsing/formatting (NativeLib) — reused so Android accepts the same
 * "500k"/"2M" syntax as the CLI. */
#include "jni_common.h"
#include "dlm/dlm.h"

JNIEXPORT jlong JNICALL
Java_com_dlm_core_jni_NativeLib_parseRate(JNIEnv *env, jobject thiz, jstring s)
{
    (void)thiz;
    char *c = jstr_dup(env, s);
    jlong r = (jlong)dlm_parse_rate(c);
    free(c);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_dlm_core_jni_NativeLib_formatRate(JNIEnv *env, jobject thiz, jlong bps)
{
    (void)thiz;
    char buf[64];
    dlm_format_rate((int64_t)bps, buf, sizeof buf);
    return jstr_new(env, buf);
}
