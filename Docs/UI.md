# UI layer

The UI is Jetpack Compose, hosted by a single exported `MainActivity`. State flows
one way: `QueueScheduler` → `QueueRepository` → `QueueViewModel` (`StateFlow`) →
composables.

## Entry points

`MainActivity` (`app/src/main/java/guru/freberg/dlm/ui/MainActivity.kt`):
- **Launcher** (`MAIN`/`LAUNCHER`).
- **Share / open** — `ACTION_SEND` (text) and `ACTION_VIEW` (`http`/`https`).
  `extractSharedUrl` reads `EXTRA_TEXT`/`dataString`; `httpUrlOrNull` validates the
  scheme. The shared URL is only read on a fresh start (`savedInstanceState ==
  null`) and cleared after the Add dialog consumes it (`onSharedConsumed`), so it
  doesn't re-pop on rotation or latch off clipboard detection.
- **Clipboard** — when the "watch clipboard" setting is on, a focus-gain handler
  (`onWindowFocusChanged`) reads a link and stages it via `autoGrab`; otherwise it
  offers it through the Add dialog. (Android blocks background clipboard reads.)

## Navigation & screens

`AppRoot` switches between top-level `Screen`s with a bottom nav:

| Screen | File | Purpose |
|--------|------|---------|
| Downloads | `screens/DownloadsScreen.kt` | Active/queued/finished list, per-item actions. |
| Review (linkgrabber) | `screens/LinkgrabberScreen.kt` | Staged links grouped by site/package; confirm/remove. |
| Settings | `screens/SettingsScreen.kt` | Concurrency, speed limit, autostart, clipboard, save folder, yt-dlp setup, IA sign-in. |
| Status | `screens/StatusScreen.kt` | Background activity log (setup, updates) from `StatusCenter`. |
| Auth | `screens/ArchiveOrgLoginScreen.kt` | archive.org sign-in (S3 keys / email+password / cookie). |

Shared building blocks live in `screens/Components.kt`; helpers in `ui/util/`
(`Format.kt` for byte/ETA/rate formatting and file-type icons, `SiteGrouping.kt`
for host/label/favicon + `isSafeDownloadInput`). `Format.formatBytes` uses
`Locale.US` to keep `.`/`,` decimal separators stable.

## ViewModel & repository

`QueueViewModel` exposes the snapshot, resolving state, yt-dlp state, clipboard
toggle, and one-shot UI messages, and forwards verbs to `QueueRepository`. The
repository is the single facade over the scheduler, the yt-dlp resolver, and IA
auth; it also owns SAF export. Long/native/disk work is dispatched off the main
thread (e.g. `authStatus()` is read under `Dispatchers.IO`).

## The Add dialog

`AddUrlDialog.kt` prefills from a shared/clipboard link (via `detectUrl`) and
offers **Add** (stage for review → `crawl`) or **Download now** (→ `addDirect`).
Both buttons are enabled only when `isSafeDownloadInput(url)` passes, and they
submit the trimmed URL. See [Resolvers.md](Resolvers.md).

## Storage Access Framework (export)

The user picks a destination tree (`OpenDocumentTree`); the activity takes a
**persistable** read/write permission. Downloads always land in app storage first;
`SafMover` copies finished files into the chosen tree (MIME inferred from
extension). With auto-export on, `DownloadService.observeForExport` copies each
finished item off the main thread (`Dispatchers.IO`), marking it exported only on
success so transient failures retry, and bounding the dedup set to current items.

## Background-status surface

`StatusCenter` is a process-wide, synchronized record of background activities
(runtime setup, yt-dlp updates, queue load) surfaced by the Status screen, so the
user can see what the app is doing and whether it succeeded.
