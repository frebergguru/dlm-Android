# Scheduler

`QueueScheduler` (`app/src/main/java/guru/freberg/dlm/scheduler/QueueScheduler.kt`)
is a Kotlin re-implementation of the desktop daemon's `queue.c`, preserving its
semantics. The SQLite `store` (libdlm `store.c`) remains the persisted source of
truth; the scheduler holds an in-memory mirror for fast snapshotting.

## Queue model

- **Two lists** (`ListKind`): `DOWNLOAD` (the active queue) and `LINKGRABBER`
  (staging for review/confirm, JDownloader-style).
- **Items** (`QItem`) and **packages** (`QPkg`) — packages group links with a
  name/folder/priority/collapsed flag.
- **States** (`QState`): `QUEUED → ACTIVE → DONE | ERROR`, plus paused/disabled
  via flags. Live progress fields on `QItem` are `@Volatile` (written from the
  worker, read by the snapshot).
- **Priorities** −3..+3, **enable/disable**, **per-link & global autostart**,
  **force start**, **reorder** (up/down/top/bottom), **clear finished**.

State is published as an immutable `QueueSnapshot` on a `StateFlow` consumed by
the repository/UI.

## Concurrency model

- All queue-state mutation happens under a single `Mutex`.
- The native store is single-thread-affine: every `store.*` call runs on
  `storeDispatcher = Dispatchers.IO.limitedParallelism(1)`. Mutating verbs wrap
  their critical section in `withContext(storeDispatcher) { mutex.withLock { … } }`.
- Each active download runs as a child coroutine of the scheduler scope.

## Worker lifecycle

`startItem` (caller holds the mutex) marks the item `ACTIVE`, resets the cancel
flag, allocates a native cancel token for engine downloads, and launches the
worker. The worker:

1. runs `runWorker` (engine via `NativeEngine.download`, or the yt-dlp delegate
   via the `MediaResolver`), reporting progress through a `ProgressSink`;
2. on `CancellationException`, finalizes under `NonCancellable` (frees the token,
   settles state) and rethrows — structured concurrency is preserved;
3. on any other `Throwable`, logs it and maps to `DLM_ERR_UNKNOWN` so the item
   lands in `ERROR` instead of stalling the queue;
4. always runs `finalizeWorker` so the item never stays `ACTIVE` and the native
   token is never leaked.

`tick()` (driven by `DownloadService`) promotes eligible `QUEUED` items up to
`maxActive`, honoring force/autostart/global-autostart.

## Output path safety (`resolveOut`)

`resolveOut` turns a task's filename/outPath into the on-disk path. Resolver
filenames are sanitized natively, but `resolveOut` adds a final defense-in-depth
guard, `confineToDownloadDir`: it canonicalizes the candidate and, if it escapes
the download dir (`../`, an absolute path like `/data/.../auth.json`), collapses
to the leaf name **inside** the dir. Legitimate leaf names and absolute paths that
already resolve under the dir pass through unchanged. See [Security.md](Security.md).

## Speed limits

`maxSpeed` (global) is divided across `maxActive` workers (`perRate`), matching
the desktop behaviour. `maxActive`/`maxSpeed`/`globalAutostart` are `@Volatile`
and seeded from `SettingsStore`.

## Relationship to the service

`QueueScheduler` is pure logic — it does not touch Android lifecycle.
`DownloadService` owns the cadence (`tick()` every `TICK_MS = 200`), the
foreground notification, and the wake/Wi-Fi locks, and self-stops when
`hasWork(snapshot)` is false for ~6 s. See [Architecture.md](Architecture.md).

## Parity tests

The scheduler's behaviour is verified by JVM tests that mirror the upstream
`test_queue.c` / `test_package.c` (`app/src/test/...`). See [Testing.md](Testing.md).
