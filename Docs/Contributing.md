# Contributing

## License & SPDX headers

The project is **GPL-3.0-or-later** (full text in [`../LICENSE`](../LICENSE)).
Every first-party source file starts with an SPDX tag:

- Kotlin / Java: `// SPDX-License-Identifier: GPL-3.0-or-later`
- C / headers: `/* SPDX-License-Identifier: GPL-3.0-or-later */`

New first-party files must carry it. **Do not** add it to vendored third-party
code: `core/src/main/cpp/sqlite/`, anything under `nativeDeps/`, `.deps-build/`,
or `.cxx/`. To re-apply headers across first-party source idempotently, prepend
the tag to any file whose first three lines don't already contain
`SPDX-License-Identifier` (Kotlin under `app/src` + `core/src`; C/H under
`core/src/main/cpp/{jni,libdlm,tests}`).

## The "verbatim core" rule

`libdlm/*.c` is reused **unchanged** from the desktop dlm so its proven logic
(segmented engine, journal resume, SQLite schema/ordering, `dlm_ytdlp_parse`)
stays identical across platforms. Keep Android-specific behaviour at the **edges**:

- the JNI bridge (`core/src/main/cpp/jni/`),
- the CA-bundle hook (`dlm_set_ca_bundle`/`dlm_ca_bundle`, set from `jni_init.c`),
- XDG paths via `setenv` in `nativeInit`,
- the `#ifdef`'d-out yt-dlp subprocess path (the JVM runs yt-dlp instead).

If you must change a core file, prefer a minimal, clearly-commented edit (as the
security hardening did: protocol allow-lists, filename sanitization, NULL guards)
and add/extend a host C test for it.

## Keeping the JNI contract in sync

The model classes in `core/.../model/Models.kt` mirror C structs **positionally**.
If you change a model's field order/type/nullability, update the matching
`GetMethodID` signature in `core/src/main/cpp/jni/jni_init.c` in the same commit.
See [JNIBridge.md](JNIBridge.md).

## Code style

- Match the surrounding code's idiom, naming, and comment density. Comments
  explain *why*, not *what*.
- Kotlin: structured concurrency; never block `Dispatchers.Main`; confine native
  store access to `storeDispatcher`; prefer `StateFlow` for UI state.
- C: free on every path; bound all copies from network data; parameterize all SQL;
  wipe secrets with `OPENSSL_cleanse`.
- Formatting numbers for display: use `Locale.US`/`Locale.ROOT`.

## Change checklist

1. Build the layer you touched (`:core:externalNativeBuildDebug`,
   `:app:compileDebugKotlin`).
2. Run the relevant tests — see [Testing.md](Testing.md). At minimum
   `:app:testDebugUnitTest` for any Kotlin change.
3. New source file → SPDX header present.
4. Touched a model or JNI signature → both sides updated.
5. Touched URL handling, paths, credentials, or curl options → re-read
   [Security.md](Security.md) and add/adjust a test.
6. Renamed a package → update `app/proguard-rules.pro`,
   `core/consumer-rules.pro`, and the `FindClass` strings in `jni_init.c`.

## Commit / PR notes

- Don't commit secrets or a release keystore; signing config must read from the
  environment (see [Build.md](Build.md)).
- `nativeDeps/` is produced by `scripts/build-deps.sh`; don't hand-edit it.
</content>
