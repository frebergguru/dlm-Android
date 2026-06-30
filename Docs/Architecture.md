# Architecture

dlm for Android is a **port** of the desktop C download manager. The proven C
engine (`libdlm`) is compiled with the NDK and reused largely verbatim through a
JNI bridge; the daemon/CLI/GTK GUI are replaced by an Android foreground service
and a Jetpack Compose UI, and the daemon's `queue.c` scheduler is re-implemented
in Kotlin over the same SQLite store.

## Modules

| Module | Contents | Build output |
|--------|----------|--------------|
| `core/` | Vendored `libdlm` C (`core/src/main/cpp/libdlm`), the JNI bridge (`core/src/main/cpp/jni`), and the Kotlin `Native*` wrappers + model classes (`core/src/main/java/...`). | `libdlmcore.so` + an AAR |
| `app/`  | `QueueScheduler` (port of `queue.c`), `DownloadService`, `YtdlpManager`, `QueueRepository`, `QueueViewModel`, and the Compose UI. | the APK(s) |
| `nativeDeps/` | Prebuilt curl/openssl/jansson/pcre2 per ABI, produced by `scripts/build-deps.sh`. | static libs + headers |

## Layered data flow

```
 Compose UI ─▶ QueueViewModel ─▶ QueueRepository ─▶ QueueScheduler (Kotlin, ex-queue.c)
   (screens)     (StateFlow)        (facade)              │   single mutex + storeDispatcher
                                                          │
                       DownloadService (foreground, dataSync, wake/wifi locks)
                                                          │
        ┌──────────────────────────────┬─────────────────┴───────────┬─────────────────┐
        ▼                              ▼                              ▼                 ▼
  NativeEngine (download.c)     NativeStore (store.c)        NativeExtract        YtdlpManager
  segmented + journal resume    SQLite queue (single-        (archiveorg.c,       (youtubedl-android,
  via JNI                       thread-affine) via JNI       extract.c) via JNI   Python runtime)
        └──────────────▶ libdlmcore.so = libdlm/*.c (verbatim) + jni/*.c + curl/openssl/jansson/pcre2
```

### Resolve path (URL → tasks)

`QueueRepository.resolveTasks` is the single funnel (see [Resolvers.md](Resolvers.md)):

1. **Boundary validation** — `isSafeDownloadInput` rejects option-injection
   (leading `-`) and non-network schemes (`file:`, `content:`, …).
2. **Native extract** — `NativeExtract.extract` handles archive.org items and
   direct-file URLs; otherwise it flags `needsYtdlp`.
3. **yt-dlp** — if needed, `YtdlpManager.resolve` runs `yt-dlp -J` and feeds the
   JSON to the verbatim native parser (`dlm_ytdlp_parse`).
4. **Generic fallback** — if yt-dlp is *ready* but rejects the URL, the URL is
   downloaded as a single generic file by the segmented engine.

### Download path (task → bytes on disk)

The scheduler launches a worker coroutine per active item. Engine downloads call
`NativeEngine.download` (→ `dlm_download_file`); yt-dlp delegations call
`YtdlpManager.download`. Progress flows back through a `ProgressSink` callback and
is published on the scheduler's `StateFlow`. See [Scheduler.md](Scheduler.md) and
[NativeCore.md](NativeCore.md).

## Process & component lifecycle

- **`DlmApplication.onCreate`** builds the process-wide `AppContainer` (singleton)
  and triggers `ensureLoaded()`.
- **`AppContainer`** wires settings, the native store, the scheduler, the
  repository, and `YtdlpManager`. Native init (curl global init, CA bundle copy,
  SQLite open) happens in its constructor; `ensureLoaded()` loads the persisted
  queue exactly once (guarded by an `AtomicBoolean`).
- **`DownloadService`** (foreground, `dataSync`) is started when work is queued.
  Its tick loop (`TICK_MS = 200`) advances the scheduler, refreshes the
  notification (rate-limited to ~1s), and acquires a partial wakelock + Wi-Fi
  lock while transfers are active. It self-stops after `IDLE_TICKS_BEFORE_STOP`
  (~6 s) with no work.
- **`MainActivity`** is the only exported component; it accepts `ACTION_VIEW`/
  `ACTION_SEND` `http(s)` links and observes the scheduler's snapshot for the UI.

## Threading model

| Concern | Rule |
|---------|------|
| Queue state (`items`, `pkgs`) | Mutated only under `QueueScheduler.mutex`. |
| Native SQLite store | Single-thread-affine — every `store.*` call is confined to `storeDispatcher` (`Dispatchers.IO.limitedParallelism(1)`). |
| Native engine download | Runs on a worker coroutine; the libcurl multi-loop is single-threaded, so the cached `JNIEnv` for progress callbacks is always used on the calling thread. |
| Cancellation | Cooperative — a worker polls a heap "cancel cell" (engine) or kills the yt-dlp process by id; native blocking calls are never interrupted. |
| UI | Compose collects `StateFlow`s; blocking/native calls are dispatched to `Dispatchers.IO`. |

## Key cross-cutting decisions

- **CA bundle injection.** libcurl on Android has no usable default trust store
  and ignores `CURL_CA_BUNDLE`; the engine applies a shipped `cacert.pem` via
  `CURLOPT_CAINFO` (`dlm_ca_bundle` hook in `util.c`, set once from
  `jni_init.c`). The Android network-security-config governs only the Java/OkHttp
  stack — **not** native curl. See [Security.md](Security.md).
- **yt-dlp is not bundled.** The Python + yt-dlp + ffmpeg runtime is downloaded
  on first use and self-updates weekly. See [Resolvers.md](Resolvers.md).
- **The C core stays verbatim** wherever possible; Android-specific behaviour is
  injected at the edges (JNI, CA hook, XDG `setenv`). See
  [Contributing.md](Contributing.md).
