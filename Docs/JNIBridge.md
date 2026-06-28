# JNI bridge

The JNI layer (`core/src/main/cpp/jni/*.c`) connects the Kotlin `Native*`
wrappers to `libdlm`. The Kotlin side is a set of thin `object`s under
`guru.freberg.dlm.core.jni`; the model classes are under
`guru.freberg.dlm.core.model`.

## Files

| C file | Kotlin counterpart | Purpose |
|--------|--------------------|---------|
| `jni_init.c` | `NativeLib` | `JNI_OnLoad`: cache classes + ctor IDs; `nativeInit` (XDG `setenv`, CA bundle, curl global init); version. |
| `jni_engine.c` | `NativeEngine` | Start a download, alloc/flip/free the cancel cell, progress upcalls. |
| `jni_store.c` | `NativeStore` / `QueueStore` | Open the SQLite store and the queue CRUD verbs. |
| `jni_extract.c` | `NativeExtract` | `extract`, `parseYtdlp`, `isArchiveOrg`, `filenameFromUrl`. |
| `jni_verify.c` | `NativeVerify` | md5/sha1 verification. |
| `jni_auth.c` | `NativeAuth` | IA credential storage verbs. |
| `jni_util.c`, `jni_common.h` | — | String helpers, the cached-IDs struct, common macros. |

## Lifecycle & class caching (`jni_init.c`)

`JNI_OnLoad` caches **global refs** to the model classes (`StoreRow`,
`PackageRow`, `Task`, `ExtractResult`, `ProgressSink`) and their `<init>` method
IDs once. On any failure it clears the pending exception before returning
`JNI_ERR` (returning from `JNI_OnLoad` with an exception pending is undefined).

`nativeInit(configDir, caBundlePath)`:
- `setenv` `XDG_CONFIG_HOME`/`XDG_DATA_HOME`/`HOME` to the app-private config dir
  (so `iaauth.c`'s XDG paths resolve correctly);
- applies the CA bundle via `dlm_set_ca_bundle` (libcurl ignores `CURL_CA_BUNDLE`
  on Android);
- runs `dlm_global_init` (curl global init).

## The model contract (must stay in sync)

`jni_init.c` caches each model's constructor **signature string**, and the C code
constructs the objects positionally. The Kotlin data classes in
`core/src/main/java/guru/freberg/dlm/core/model/Models.kt` therefore must match
field **order, type and nullability** exactly. Models:

| Class | Mirrors | Notes |
|-------|---------|-------|
| `StoreRow` | `dlm_store_row` | 19 fields; built by `nAddFull`/load verbs. |
| `PackageRow` | `dlm_store_pkg_row` | package metadata. |
| `Task` | `dlm_task` | `url, filename, size, md5?, sha1?, headers?, delegate`. |
| `ExtractResult` | extraction result | `source?, tasks[], needsYtdlp`. |
| `DownloadOptions` | `dlm_options` | engine download parameters. |

`Task`/`DownloadOptions` override `equals`/`hashCode` because the `headers` array
breaks the data-class defaults. **Do not reorder these constructors** without
updating the matching `GetMethodID` signature in `jni_init.c`.

## Progress callbacks

`ProgressSink.onProgress(done: Long, total: Long, bps: Double)` maps to the cached
`onProgress` method id (`(JJD)V`). The engine's `curl_multi` loop is
single-threaded, so the upcall always runs on the worker thread that called
`nDownload` — the cached `JNIEnv` is valid (no cross-thread attach needed).

## Cancellation

`NativeEngine.nAllocCancel` returns a heap "cancel cell" pointer; Kotlin flips it
via `nCancel` and frees it via `nFreeCancel`. The engine polls it cooperatively
during the transfer — the blocking native call is never interrupted.

## Memory & exception rules followed here

- Every `GetStringUTFChars`/`jstr_dup` is released; every `malloc`/`strdup`/
  `calloc` is freed on all paths.
- Local refs created in loops are deleted per iteration; cached classes are
  promoted to global refs.
- `NewObject` is guarded by an `ExceptionCheck`; `FindClass` is not called with a
  pending exception (`jni_extract.c` guards `headers_to_jarray`).
- `headers_dup` (`jni_engine.c`) compacts past NULLs so a NULL element can't
  truncate the engine's NULL-terminated header walk.

## Static vs instance externals

`NativeStore`'s externals are `@JvmStatic` (the C functions receive a `jclass`);
the other wrappers declare instance externals (receiving a `jobject`). Each C
file's function naming/registration matches its Kotlin declaration — keep them
consistent if you add methods.
</content>
