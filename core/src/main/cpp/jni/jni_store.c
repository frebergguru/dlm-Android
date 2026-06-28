/* SPDX-License-Identifier: GPL-3.0-or-later */
/* JNI: NativeStore — thin wrapper over libdlm's sqlite3 queue store (store.c).
 * The store remains the persisted source of truth; the Kotlin scheduler reads
 * rows through loadAll and mutates them through the per-field setters. */
#include "jni_common.h"
#include "dlm/store.h"

#include <stdlib.h>
#include <stdint.h>

#define ST(h) ((dlm_store *)(intptr_t)(h))

JNIEXPORT jlong JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nOpen(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    char *p = jstr_dup(env, path);
    dlm_store *s = dlm_store_open(p ? p : "");
    free(p);
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nClose(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    if (h) dlm_store_close(ST(h));
}

JNIEXPORT jlong JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nAdd(JNIEnv *env, jclass cls, jlong h,
        jstring url, jstring outPath, jint conns, jint delegate, jlong createdAt)
{
    (void)cls;
    char *u = jstr_dup(env, url), *o = jstr_dup(env, outPath);
    int64_t id = dlm_store_add(ST(h), u, o, conns, delegate, createdAt);
    free(u); free(o);
    return (jlong)id;
}

JNIEXPORT jlong JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nAddFull(JNIEnv *env, jclass cls, jlong h,
        jstring url, jstring outPath, jint conns, jint delegate, jlong total,
        jlong downloaded, jstring state, jstring error, jlong createdAt,
        jlong packageId, jint priority, jint enabled, jint autostart,
        jstring list, jstring name, jstring availability, jlong position,
        jint force)
{
    (void)cls;
    dlm_store_row row;
    memset(&row, 0, sizeof row);
    row.url = jstr_dup(env, url);
    row.out_path = jstr_dup(env, outPath);
    row.connections = conns;
    row.delegate = delegate;
    row.total = total;
    row.downloaded = downloaded;
    row.state = jstr_dup(env, state);
    row.error = jstr_dup(env, error);
    row.created_at = createdAt;
    row.package_id = packageId;
    row.priority = priority;
    row.enabled = enabled;
    row.autostart = autostart;
    row.list = jstr_dup(env, list);
    row.name = jstr_dup(env, name);
    row.availability = jstr_dup(env, availability);
    row.position = position;
    row.force = force;

    int64_t id = dlm_store_add_full(ST(h), &row);

    free((void *)row.url); free((void *)row.out_path); free((void *)row.state);
    free((void *)row.error); free((void *)row.list); free((void *)row.name);
    free((void *)row.availability);
    return (jlong)id;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nSetProgress(JNIEnv *env, jclass cls, jlong h,
        jlong id, jlong total, jlong downloaded)
{
    (void)env; (void)cls;
    return dlm_store_set_progress(ST(h), id, total, downloaded);
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nSetState(JNIEnv *env, jclass cls, jlong h,
        jlong id, jstring state, jstring error)
{
    (void)cls;
    char *s = jstr_dup(env, state), *e = jstr_dup(env, error);
    int r = dlm_store_set_state(ST(h), id, s ? s : "queued", e);
    free(s); free(e);
    return r;
}

#define SETTER_INT(Name, fn) \
JNIEXPORT jint JNICALL \
Java_guru_freberg_dlm_core_jni_NativeStore_##Name(JNIEnv *env, jclass cls, jlong h, \
        jlong id, jint v) { (void)env; (void)cls; return fn(ST(h), id, v); }

SETTER_INT(nSetPriority, dlm_store_set_priority)
SETTER_INT(nSetEnabled, dlm_store_set_enabled)
SETTER_INT(nSetAutostart, dlm_store_set_autostart)
SETTER_INT(nSetForce, dlm_store_set_force)

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nSetPosition(JNIEnv *env, jclass cls, jlong h,
        jlong id, jlong pos)
{
    (void)env; (void)cls;
    return dlm_store_set_position(ST(h), id, pos);
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nSetPackage(JNIEnv *env, jclass cls, jlong h,
        jlong id, jlong pkg)
{
    (void)env; (void)cls;
    return dlm_store_set_package(ST(h), id, pkg);
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nSetList(JNIEnv *env, jclass cls, jlong h,
        jlong id, jstring list)
{
    (void)cls;
    char *l = jstr_dup(env, list);
    int r = dlm_store_set_list(ST(h), id, l ? l : "download");
    free(l);
    return r;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nDelete(JNIEnv *env, jclass cls, jlong h, jlong id)
{
    (void)env; (void)cls;
    return dlm_store_delete(ST(h), id);
}

/* ---- loadAll ---------------------------------------------------------- */

typedef struct {
    JNIEnv *env;
    jobject *items;
    int count, cap;
    int failed;
} row_ctx;

/* Release every global ref accumulated so far and the backing array. */
static void row_ctx_release(JNIEnv *env, row_ctx *c)
{
    for (int i = 0; i < c->count; i++)
        (*env)->DeleteGlobalRef(env, c->items[i]);
    free(c->items);
    c->items = NULL;
    c->count = c->cap = 0;
}

static void row_cb(void *ud, const dlm_store_row *r)
{
    row_ctx *c = ud;
    JNIEnv *env = c->env;
    if (c->failed) return;
    if (c->count == c->cap) {
        int ncap = c->cap ? c->cap * 2 : 32;
        jobject *ni = realloc(c->items, (size_t)ncap * sizeof *ni);
        if (!ni) { c->failed = 1; return; }
        c->items = ni;
        c->cap = ncap;
    }

    jstring url = jstr_new(env, r->url);
    jstring out = jstr_new(env, r->out_path);
    jstring st = jstr_new(env, r->state);
    jstring err = jstr_new(env, r->error);
    jstring lst = jstr_new(env, r->list);
    jstring nm = jstr_new(env, r->name);
    jstring av = jstr_new(env, r->availability);

    /* A failed jstr_new may have left a pending exception (e.g. OOM); don't
     * issue NewObject on top of it. */
    jobject o = (*env)->ExceptionCheck(env) ? NULL :
        (*env)->NewObject(env, g_jni.storeRow, g_jni.storeRowCtor,
            (jlong)r->id, url, out, (jint)r->connections, (jint)r->delegate,
            (jlong)r->total, (jlong)r->downloaded, st, err, (jlong)r->created_at,
            (jlong)r->package_id, (jint)r->priority, (jint)r->enabled,
            (jint)r->autostart, lst, nm, av, (jlong)r->position, (jint)r->force);

    /* The constructed object holds its own refs; drop the transient locals.
     * DeleteLocalRef(NULL) is a no-op, so unconditional calls are safe. */
    (*env)->DeleteLocalRef(env, url);
    (*env)->DeleteLocalRef(env, out);
    (*env)->DeleteLocalRef(env, st);
    (*env)->DeleteLocalRef(env, err);
    (*env)->DeleteLocalRef(env, lst);
    (*env)->DeleteLocalRef(env, nm);
    (*env)->DeleteLocalRef(env, av);

    if (!o) { c->failed = 1; return; }

    /* Promote to a global ref and drop the local so the per-row locals never
     * accumulate and overflow ART's local-reference table on large queues. */
    jobject g = (*env)->NewGlobalRef(env, o);
    (*env)->DeleteLocalRef(env, o);
    if (!g) { c->failed = 1; return; }
    c->items[c->count++] = g;
}

JNIEXPORT jobjectArray JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nLoadAll(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    row_ctx ctx = { env, NULL, 0, 0, 0 };
    int rc = dlm_store_load_all(ST(h), row_cb, &ctx);

    /* A partial/aborted scan (rc != 0) must not be presented as the complete
     * queue: returning the rows collected so far would make the scheduler treat
     * a truncated view as authoritative. Fail the whole load instead. */
    if (ctx.failed || rc != 0) {
        row_ctx_release(env, &ctx);
        return NULL;
    }

    jobjectArray arr = (*env)->NewObjectArray(env, ctx.count, g_jni.storeRow, NULL);
    if (arr) {
        for (int i = 0; i < ctx.count; i++) {
            (*env)->SetObjectArrayElement(env, arr, i, ctx.items[i]);
            (*env)->DeleteGlobalRef(env, ctx.items[i]);
        }
        free(ctx.items);
    } else {
        /* Array allocation failed; release the globals we still own. */
        row_ctx_release(env, &ctx);
    }
    return arr;
}

/* ---- packages --------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nPkgAdd(JNIEnv *env, jclass cls, jlong h,
        jstring name, jstring folder, jstring comment, jstring list,
        jint priority, jlong position, jlong createdAt)
{
    (void)cls;
    char *n = jstr_dup(env, name), *f = jstr_dup(env, folder),
         *cm = jstr_dup(env, comment), *l = jstr_dup(env, list);
    int64_t id = dlm_store_pkg_add(ST(h), n, f, cm, l ? l : "download",
                                   priority, position, createdAt);
    free(n); free(f); free(cm); free(l);
    return (jlong)id;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nPkgUpdate(JNIEnv *env, jclass cls, jlong h,
        jlong id, jstring name, jstring folder, jstring comment, jint priority,
        jint collapsed)
{
    (void)cls;
    char *n = jstr_dup(env, name), *f = jstr_dup(env, folder),
         *cm = jstr_dup(env, comment);
    int r = dlm_store_pkg_update(ST(h), id, n, f, cm, priority, collapsed);
    free(n); free(f); free(cm);
    return r;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nPkgSetList(JNIEnv *env, jclass cls, jlong h,
        jlong id, jstring list)
{
    (void)cls;
    char *l = jstr_dup(env, list);
    int r = dlm_store_pkg_set_list(ST(h), id, l ? l : "download");
    free(l);
    return r;
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nPkgSetPosition(JNIEnv *env, jclass cls,
        jlong h, jlong id, jlong pos)
{
    (void)env; (void)cls;
    return dlm_store_pkg_set_position(ST(h), id, pos);
}

JNIEXPORT jint JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nPkgDelete(JNIEnv *env, jclass cls, jlong h,
        jlong id)
{
    (void)env; (void)cls;
    return dlm_store_pkg_delete(ST(h), id);
}

typedef struct {
    JNIEnv *env;
    jobject *items;
    int count, cap;
    int failed;
} pkg_ctx;

/* Release every global ref accumulated so far and the backing array. */
static void pkg_ctx_release(JNIEnv *env, pkg_ctx *c)
{
    for (int i = 0; i < c->count; i++)
        (*env)->DeleteGlobalRef(env, c->items[i]);
    free(c->items);
    c->items = NULL;
    c->count = c->cap = 0;
}

static void pkg_cb(void *ud, const dlm_store_pkg_row *r)
{
    pkg_ctx *c = ud;
    JNIEnv *env = c->env;
    if (c->failed) return;
    if (c->count == c->cap) {
        int ncap = c->cap ? c->cap * 2 : 16;
        jobject *ni = realloc(c->items, (size_t)ncap * sizeof *ni);
        if (!ni) { c->failed = 1; return; }
        c->items = ni;
        c->cap = ncap;
    }
    jstring nm = jstr_new(env, r->name);
    jstring fo = jstr_new(env, r->folder);
    jstring cm = jstr_new(env, r->comment);
    jstring ls = jstr_new(env, r->list);

    /* Don't issue NewObject if a jstr_new left a pending exception. */
    jobject o = (*env)->ExceptionCheck(env) ? NULL :
        (*env)->NewObject(env, g_jni.pkgRow, g_jni.pkgRowCtor,
            (jlong)r->id, nm, fo, cm, ls, (jint)r->priority, (jint)r->collapsed,
            (jlong)r->position, (jlong)r->created_at);

    /* DeleteLocalRef(NULL) is a no-op, so unconditional calls are safe. */
    (*env)->DeleteLocalRef(env, nm);
    (*env)->DeleteLocalRef(env, fo);
    (*env)->DeleteLocalRef(env, cm);
    (*env)->DeleteLocalRef(env, ls);

    if (!o) { c->failed = 1; return; }

    /* Promote to a global ref so per-row locals never accumulate. */
    jobject g = (*env)->NewGlobalRef(env, o);
    (*env)->DeleteLocalRef(env, o);
    if (!g) { c->failed = 1; return; }
    c->items[c->count++] = g;
}

JNIEXPORT jobjectArray JNICALL
Java_guru_freberg_dlm_core_jni_NativeStore_nLoadPackages(JNIEnv *env, jclass cls, jlong h)
{
    (void)cls;
    pkg_ctx ctx = { env, NULL, 0, 0, 0 };
    int rc = dlm_store_pkg_load_all(ST(h), pkg_cb, &ctx);

    /* As in nLoadAll: a partial scan must fail rather than masquerade as the
     * full package list. */
    if (ctx.failed || rc != 0) {
        pkg_ctx_release(env, &ctx);
        return NULL;
    }

    jobjectArray arr = (*env)->NewObjectArray(env, ctx.count, g_jni.pkgRow, NULL);
    if (arr) {
        for (int i = 0; i < ctx.count; i++) {
            (*env)->SetObjectArrayElement(env, arr, i, ctx.items[i]);
            (*env)->DeleteGlobalRef(env, ctx.items[i]);
        }
        free(ctx.items);
    } else {
        pkg_ctx_release(env, &ctx);
    }
    return arr;
}
