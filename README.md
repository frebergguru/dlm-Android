# dlm for Android

A full Android port of **dlm**, the C download manager (segmented/resumable
downloads, a SQLite-backed queue with packages/priorities/linkgrabber, native
archive.org extraction, yt-dlp delegation, MD5/SHA1 verification).

This is a **true port**: the proven C engine (`libdlm`) is compiled with the
Android NDK and reused largely **verbatim** through a JNI bridge. The daemon,
CLI and GTK GUI are replaced by an Android **Foreground Service** + **Jetpack
Compose** UI, and the `queue.c` scheduler is re-implemented in Kotlin over the
same persisted sqlite store.

## Architecture

```
 Compose UI  ──▶ QueueViewModel ──▶ QueueRepository ──▶ QueueScheduler (Kotlin, ex-queue.c)
                                                              │
                          DownloadService (foreground, dataSync, wakelock)
                                                              │
        ┌─────────────────────────────────────────────┬──────┴───────────────┐
        ▼                                               ▼                      ▼
  NativeEngine (download.c)                     NativeStore (store.c)   YtdlpManager
  segmented + resume, via JNI                   sqlite queue, via JNI   (youtubedl-android,
        │                                                               auto-downloaded)
        └──▶ libdlmcore.so  =  libdlm/*.c (verbatim)  +  jni/*.c  +  prebuilt curl/openssl/jansson/pcre2
```

| Module | Contents |
|--------|----------|
| `core/` | Android library: vendored `libdlm` C (`core/src/main/cpp/libdlm`), the JNI bridge (`core/src/main/cpp/jni`), and the Kotlin `Native*` wrappers + models. Builds `libdlmcore.so`. |
| `app/`  | The application: `QueueScheduler` (port of `queue.c`), `DownloadService`, `YtdlpManager`, `QueueRepository`, and the Compose UI. |
| `nativeDeps/` | Prebuilt curl/openssl/jansson/pcre2 per ABI (produced by `scripts/build-deps.sh`). |

What is reused **verbatim** from the C: the segmented engine + journal resume
(`download.c`), the sqlite schema/migrations/ordering (`store.c`), the
archive.org extractor (`archiveorg.c`), checksum verify (`verify.c`), the
IA-auth store (`iaauth.c`), and the subtle yt-dlp JSON parser
(`dlm_ytdlp_parse`). Only `ytdlp.c`'s subprocess path is `#ifdef`'d out for
Android (the JVM runs yt-dlp instead and feeds its JSON back into the verbatim
parser). The only other change is the documented CA-bundle injection: libcurl on
Android has no usable default trust store and ignores `CURL_CA_BUNDLE`, so the
engine applies a shipped `cacert.pem` via `CURLOPT_CAINFO` (a small `dlm_ca_bundle`
hook in `util.c`, set once from `jni_init.c`). XDG paths are handled by `setenv`.

## Building

Prerequisites: Android Studio (Koala+) or the Android SDK + **NDK r25+**, and
the host build tools used by the dependency script (autoconf/automake/libtool,
cmake, make, perl, curl).

1. **Build the native dependencies** (one-time, per ABI). This cross-compiles
   OpenSSL, libcurl, jansson and pcre2 into `nativeDeps/`, and downloads the CA
   bundle into `app/src/main/assets/cacert.pem`:

   ```bash
   export ANDROID_NDK_HOME=~/Android/Sdk/ndk/26.1.10909125
   ./scripts/build-deps.sh
   # or a subset while iterating:  ABIS="arm64-v8a" ./scripts/build-deps.sh
   ```

2. **Generate the Gradle wrapper jar** if it isn't present (binary, not checked
   in here): `gradle wrapper` once, or just open the project in Android Studio.

3. **Build / install the app**:

   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:installDebug
   ```

   Per-ABI APKs are produced (plus a universal one) to keep size down.

> The yt-dlp runtime (Python + yt-dlp + ffmpeg) is **not** bundled in the APK.
> It is **downloaded and set up automatically on first use** (or from Settings →
> "Set up / update now"), then yt-dlp self-updates in the background.

## Features (parity with desktop dlm)

Segmented resumable downloads · global/per-download speed limits · queue with
packages (group/folder/priority/collapse) · linkgrabber staging + confirm ·
priorities (−3..+3) · enable/disable · per-link & global autostart · force start
· reorder (up/down/top/bottom) · clear finished · archive.org native extraction +
sign-in (S3 keys / email+password / cookie) · yt-dlp delegation · MD5/SHA1
verification · live progress (in-app + ongoing notification).

Android specifics: shared/opened `http(s)` links are accepted via the system
Share sheet; finished files can be exported to a user-chosen folder via the
Storage Access Framework (auto-export optional).

## Testing

- **Host C tests** (verify the vendored core on Linux CI, no Android needed):

  ```bash
  cmake -S core/src/main/cpp/host-tests -B build-host
  ctest --test-dir build-host --output-on-failure
  ```

  Covers `util`, `store`, `ia`, `ytdlp` (incl. the verbatim `dlm_ytdlp_parse`).
  Note: the daemon binds a listening socket and is intentionally not part of this
  port; its queue logic is re-verified by the Kotlin parity tests below.

- **Scheduler parity tests** (JVM, mirror upstream `test_queue.c`/`test_package.c`):

  ```bash
  ./gradlew :app:testDebugUnitTest
  ```

- **NDK smoke + JNI round-trip** (emulator/device):

  ```bash
  ./gradlew :core:connectedDebugAndroidTest
  ```

## Security notes

- HTTPS only (`usesCleartextTraffic=false` + network-security-config); TLS peer
  verification stays on, pinned to a shipped CA bundle via `CURLOPT_CAINFO`.
- archive.org secrets live in app-private storage (`iaauth.c`, mode 0600);
  consider wrapping with `EncryptedFile` before shipping.
- Foreground service is `dataSync` and not exported; the engine caps response/
  capture buffers (inherited from the C core).
- Build the host C tests and NDK build under ASan/UBSan in CI (the upstream
  supports `-DDLM_ASAN=ON`) and treat warnings as errors.

## Status / known integration points

This repository is the complete port source. Two things must be provided in your
environment before a release build runs end-to-end:

1. `nativeDeps/` must be populated by `scripts/build-deps.sh` (needs the NDK).
2. `app/src/main/assets/cacert.pem` must be the real CA bundle (the script
   fetches it; a placeholder is committed so the tree is self-describing).

The youtubedl-android coordinates/version are pinned in
`gradle/libs.versions.toml`; adjust if a newer release changes the API.
