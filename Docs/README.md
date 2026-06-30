# dlm for Android — Documentation

This folder is the developer/maintainer reference for the dlm Android port. For a
quick project overview and build quick-start, see the top-level
[`../README.md`](../README.md).

## Contents

| Doc | What it covers |
|-----|----------------|
| [UserGuide.md](UserGuide.md) | End-user guide: every action available in the app, screen by screen. |
| [Architecture.md](Architecture.md) | Module layout, end-to-end data flow, threading model, process lifecycle. |
| [Build.md](Build.md) | Toolchain, native dependency build, ABIs/splits, CMake hardening, release signing. |
| [NativeCore.md](NativeCore.md) | The vendored C engine (`libdlm`): segmented download + resume, store, verify, IA auth, utilities. |
| [JNIBridge.md](JNIBridge.md) | The JNI layer: class/ctor caching, model marshalling, signature contract, memory rules. |
| [Scheduler.md](Scheduler.md) | The Kotlin scheduler (port of `queue.c`): queue model, states, packages, priorities, concurrency. |
| [Resolvers.md](Resolvers.md) | URL → tasks: native extraction, archive.org, yt-dlp delegation, and the generic-file fallback. |
| [UI.md](UI.md) | Compose UI, navigation, `QueueViewModel`, intent/clipboard entry points, SAF export. |
| [Security.md](Security.md) | Threat model, TLS/protocol posture, input validation, credential storage, hardening. |
| [Testing.md](Testing.md) | Host C tests, JVM parity tests, NDK instrumentation, what each layer verifies. |
| [Contributing.md](Contributing.md) | Code style, SPDX/license headers, the "verbatim core" rule, change checklist. |

## One-paragraph mental model

A user adds an `http(s)` link (share sheet, clipboard, or the Add dialog). The
link is validated, then **resolved** to one or more download *tasks*
(archive.org and direct files natively; media sites via the bundled yt-dlp
runtime; anything yt-dlp rejects falls back to a plain direct download). Tasks
are persisted in a SQLite queue and driven by a Kotlin **scheduler** that mirrors
the desktop daemon's `queue.c`. Actual transfers run in the native **segmented
engine** (`libdlm/download.c`) over libcurl, with per-segment journal resume. A
**foreground service** keeps the process alive and updates a progress
notification while work is pending; finished files can be exported to a
user-chosen folder via the Storage Access Framework.

## Conventions used in these docs

- Paths are repo-relative (e.g. `core/src/main/cpp/libdlm/download.c`).
- "Verbatim core" = C reused unchanged from the desktop dlm; see
  [Contributing.md](Contributing.md) for what may and may not be edited.
- Code is licensed **GPL-3.0-or-later**; see [`../LICENSE`](../LICENSE).
