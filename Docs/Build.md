# Building

## Prerequisites

- Android Studio (Koala+) **or** the Android SDK command-line tools.
- **NDK r25+** (the project pins `ndkVersion` in `app/build.gradle.kts`).
- JDK 17 (the modules target `JVM_17`).
- Host tools for the native-dependency script: autoconf, automake, libtool,
  cmake, make, perl, curl.

The SDK location is read from `local.properties` (`sdk.dir=…`) or the
`ANDROID_HOME` environment variable.

## Step 1 — Native dependencies (one-time, per ABI)

`scripts/build-deps.sh` cross-compiles OpenSSL, libcurl, jansson and pcre2 into
`nativeDeps/<abi>/{include,lib}` and fetches the CA bundle into
`app/src/main/assets/cacert.pem`:

```bash
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/<version>
./scripts/build-deps.sh
# subset while iterating:
ABIS="arm64-v8a" ./scripts/build-deps.sh
```

The CMake build **fails fast** if `nativeDeps/<abi>/lib` is missing
(`core/src/main/cpp/CMakeLists.txt`).

## Step 2 — Gradle wrapper jar

If `gradle/wrapper/gradle-wrapper.jar` is absent (binary, not always checked in),
run `gradle wrapper` once or open the project in Android Studio.

## Step 3 — Build / install

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Useful targeted tasks:

```bash
./gradlew :core:externalNativeBuildDebug   # compile libdlmcore.so only
./gradlew :app:compileDebugKotlin          # Kotlin only
./gradlew :app:testDebugUnitTest           # JVM unit tests
```

## ABIs and APK splits

`abiFilters` and `splits.abi` are set to `arm64-v8a`, `armeabi-v7a`, `x86_64`.
Per-ABI APKs (plus a universal one) keep install size down — the native deps and
the on-demand Python/yt-dlp payload add up. 32-bit builds compile with
`_FILE_OFFSET_BITS=64`/`_LARGEFILE_SOURCE` so `off_t`/`pwrite`/`ftruncate` handle
downloads larger than 2 GiB.

## Native build configuration (`core/src/main/cpp/CMakeLists.txt`)

- **Imported static libs:** `dep_crypto`, `dep_ssl`, `dep_curl`, `dep_jansson`,
  `dep_pcre2` from `nativeDeps/<abi>/lib`.
- **Compiled in:** the SQLite amalgamation (`sqlite/sqlite3.c`, built with `-w`
  and a lean option set) and pcre2 (`PCRE2_CODE_UNIT_WIDTH=8`).
- **Hardening flags** on `dlmcore` (it parses untrusted network input):
  - `-fstack-protector-strong` (all configs)
  - `-D_FORTIFY_SOURCE=2` (release/non-Debug only — FORTIFY needs optimisation)
  - `-Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack` (link)
- Link order: `curl → ssl → crypto → z`, plus `log`/`android`.

AGP injects `-O2 -DNDEBUG` and strips symbols for release, so debug symbols are
not shipped.

## Release signing

The `release` build type enables minify + resource shrinking and uses
`proguard-android-optimize.txt` + `app/proguard-rules.pro`. **There is no
`signingConfig`**, so `assembleRelease` currently produces an *unsigned* APK. To
ship, add a signing config sourced from the environment / a keystore that is **not
committed**, e.g.:

```kotlin
// app/build.gradle.kts (illustrative)
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("DLM_KEYSTORE") ?: "/dev/null")
        storePassword = System.getenv("DLM_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("DLM_KEY_ALIAS")
        keyPassword = System.getenv("DLM_KEY_PASSWORD")
    }
}
buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
```

## ProGuard / R8 keep rules

JNI-bound classes must survive shrinking because they are constructed from C by
name. `app/proguard-rules.pro` keeps the model/JNI classes, and
`core/consumer-rules.pro` keeps `guru.freberg.dlm.core.{jni,model}.**`. If you
rename those packages, update **both** files (and the `FindClass` strings in
`core/src/main/cpp/jni/jni_init.c`).

## Dependency catalog

Versions live in `gradle/libs.versions.toml`. The youtubedl-android fork
coordinates/version are pinned there; adjust if a newer release changes the API.
JitPack is scoped via `content { … }` in `settings.gradle.kts` so only the
youtubedl/GitHub groups can resolve from it (supply-chain hygiene).
