# Resolvers (URL → tasks)

Every add path funnels through `QueueRepository.resolveTasks(url)`
(`app/src/main/java/guru/freberg/dlm/repo/QueueRepository.kt`). It turns a user
URL into a list of download *tasks* (`core.model.Task`).

## Pipeline

```
resolveTasks(url)
  │
  ├─ 0. isSafeDownloadInput(url)?            ── no ─▶ return null  (rejected)
  │
  ├─ 1. NativeExtract.extract(url)
  │        ├─ archive.org item   ─▶ tasks (native)        ─▶ return
  │        ├─ direct file URL     ─▶ task  (native)        ─▶ return
  │        └─ needsYtdlp = true   ─▶ continue
  │
  ├─ 2. YtdlpManager.resolve(url)            ── non-null ─▶ return tasks
  │        (yt-dlp -J → dlm_ytdlp_parse)
  │
  └─ 3. directFallback(url)                  ── generic file (see below)
```

## 0. Input validation (`isSafeDownloadInput`)

Defined in `app/src/main/java/guru/freberg/dlm/ui/util/SiteGrouping.kt`. Rejects:
- option-injection — anything starting with `-` (yt-dlp would parse it as a flag
  such as `--exec`); and
- non-network schemes — `file:`, `content:`, `intent:`, `javascript:`, `data:`,
  `blob:`, `about:`, `jar:`.

Schemeless host-like input (e.g. `youtube.com/watch?v=…`) is allowed because
yt-dlp/curl resolve it to `https`. The same check runs at the UI (`AddUrlDialog`
button-enable) and again here at the repository boundary (defense in depth). It is
unit-tested in `SiteGroupingTest`.

## 1. Native extraction (`NativeExtract.extract`)

`extract.c` routes the URL:
- **archive.org** host → `archiveorg.c` queries the item metadata API and emits
  one task per file (URL-encoded path, optional md5/sha1, auth headers if signed
  in).
- **Direct file** — if the URL basename looks like a file (recognised extension)
  → a single direct task (`delegate = 0`, downloaded by the segmented engine).
- **Otherwise** → `ExtractResult.needsYtdlp = true`.

## 2. yt-dlp delegation (`YtdlpManager`)

`app/src/main/java/guru/freberg/dlm/ytdlp/YtdlpManager.kt` wraps the
youtubedl-android runtime (Python + yt-dlp + ffmpeg), **downloaded on first use**,
not bundled.

- **States** (`YtdlpState`): `NOT_READY → PREPARING → READY | FAILED`.
- `ensureInit` unpacks the runtime once (guarded by `initMutex`).
- `resolve(url)` runs `yt-dlp -J --no-warnings --no-playlist -- <url>` and passes
  the JSON to `NativeExtract.parseYtdlp` (→ the verbatim `dlm_ytdlp_parse`), so
  all the subtle format/fragment/header logic ships unchanged.
- `download(url, out, rate, sink, isCancelled)` is used for tasks yt-dlp must
  handle itself (fragmented/merge); a watcher coroutine kills the process by id
  on cancel.
- **Argument-injection defense:** both request builders append an end-of-options
  marker via `addCommands(listOf("--"))`. yt-dlp treats everything after `--` as a
  positional URL, so a URL beginning with `-` can never be parsed as an option.
- **Updates** are serialized by `updateMutex` (and a background-dedup
  `AtomicBoolean`), so the weekly worker, a foreground `ensureReady`, and a
  background update can't run `updateYoutubeDL` concurrently. Cadence: first run
  updates before resolving; thereafter at least weekly, also enforced by
  `YtdlpUpdateWorker`.
- **Logging** never passes the raw exception (youtubedl-android embeds the full
  command line incl. URL/query tokens); only the host (`redactUrl`) + exception
  class name are logged.

## 3. Generic-file fallback (`directFallback`)

When yt-dlp **rejects** a URL, dlm falls back to downloading it as a single
generic file via the segmented engine:

```kotlin
private fun directFallback(url: String): List<Task>? {
    if (ytdlp.state.value != YtdlpState.READY) return null      // see gating
    val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
    if (scheme != "http" && scheme != "https") return null
    return listOf(Task(url, NativeExtract.filenameFromUrl(url), -1, null, null, null, 0))
}
```

**Why gate on `READY`?** A `null` from `resolve` only reliably means "unsupported
URL" once yt-dlp actually ran. If the runtime isn't installed yet, a `null` is
ambiguous, and blindly downloading would save a media page's HTML instead of
erroring — so the fallback is skipped in that case. The filename is derived by the
hardened `dlm_filename_from_url`, and `resolveOut` confines it to the download dir
(see [Scheduler.md](Scheduler.md)).

This is what makes "download any direct link, even ones yt-dlp doesn't recognise"
work: extension-less direct files (e.g. `https://host/getfile?id=5`) that the
native extractor can't classify and yt-dlp won't claim now download directly.

## Failure semantics

`resolveTasks` returns `null` (→ caller returns `-1`, UI shows "Couldn't read that
link") when input is rejected, native extraction needs yt-dlp but it isn't ready,
or the fallback doesn't apply. `crawl` stages results into the linkgrabber;
`addDirect` adds straight to the download list (single task) or stages a package
(multiple).
