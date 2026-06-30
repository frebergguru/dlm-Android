# Testing

Three layers, each verifying a different part of the port.

## 1. Host C tests (no Android)

Verify the vendored core on a Linux host (CI-friendly):

```bash
cmake -S core/src/main/cpp/host-tests -B build-host
ctest --test-dir build-host --output-on-failure
```

Covers `util`, `store`, `ia`, and `ytdlp` (including the verbatim
`dlm_ytdlp_parse`) via `core/src/main/cpp/tests/test_*.c`. The desktop daemon's
listening socket is intentionally not part of this port; its queue logic is
re-verified by the Kotlin parity tests below.

Run these under ASan/UBSan in CI when possible (the upstream supports an ASan
toggle) since the core parses untrusted network input.

## 2. JVM unit tests

```bash
./gradlew :app:testDebugUnitTest
```

- **Scheduler parity** (`app/src/test/.../SchedulerParityTest.kt`) mirrors the
  upstream `test_queue.c` / `test_package.c`, using a `FakeStore` so no native
  store/JNI is needed. It locks in queue ordering, priorities, packages,
  enable/autostart/force, reorder, and clear-finished.
- **Link helpers** (`app/src/test/.../SiteGroupingTest.kt`) cover `detectUrl`,
  `hostOf`, `siteLabel`, `faviconUrl`, and the **`isSafeDownloadInput` security
  gate** (allows real links/schemeless hosts; blocks `-`-injection and
  `file:`/`content:`/`intent:`/`javascript:`/`data:` schemes).

These are pure-JVM (no device/emulator) and run in seconds.

## 3. NDK instrumentation (device/emulator)

```bash
./gradlew :core:connectedDebugAndroidTest
```

`core/src/androidTest/.../NativeSmokeTest.kt` exercises the JNI round-trip
(library load, init, a model marshalling path) on a real device/emulator.

## What to run when

| Change | Run |
|--------|-----|
| C in `libdlm/` | host C tests (+ ASan) and `:core:externalNativeBuildDebug`. |
| JNI bridge or model classes | host build + `:core:connectedDebugAndroidTest` (signature/marshalling). |
| Scheduler / repository | `:app:testDebugUnitTest`. |
| URL handling / validation | `:app:testDebugUnitTest` (SiteGroupingTest). |
| Build config / CMake | a full `:app:assembleDebug`. |

## Manual smoke checklist

- Add an archive.org item, a YouTube URL, a direct `.zip`, and an extension-less
  direct file (exercises native / yt-dlp / direct / generic-fallback paths).
- Pause/resume mid-download (journal resume), toggle the speed limit, and confirm
  a finished file auto-exports to the chosen SAF folder.
- Background the app during a download (foreground service + wakelock keep it
  alive); confirm the notification updates and the service self-stops when idle.
