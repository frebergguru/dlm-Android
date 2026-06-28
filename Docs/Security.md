# Security

## Threat model

The app's primary attack surface is **attacker-controlled URLs** reaching a native
curl engine and the yt-dlp runtime. URLs arrive via the exported `MainActivity`
(`ACTION_VIEW`/`ACTION_SEND` for `http(s)`), the clipboard, and the Add dialog.
Secondary surface: archive.org credentials at rest, and untrusted HTTP responses
parsed by the native extractors.

## Network / transport

- **TLS verification is never weakened.** `CURLOPT_SSL_VERIFYPEER`/`VERIFYHOST`
  are left at libcurl's secure defaults everywhere; trust is pinned to a shipped
  `cacert.pem` via `CURLOPT_CAINFO` (`dlm_ca_bundle`). The Android
  network-security-config (`usesCleartextTraffic=false`) governs only the
  Java/OkHttp stack — **not** native curl — so the native posture is enforced in C.
- **Protocol allow-list.** Every curl handle restricts protocols:
  - downloads (`download.c`): `CURLOPT_PROTOCOLS_STR = "http,https,ftp,ftps"`;
  - extractor/auth (`httpget.c`): `"http,https"`.
  This blocks `file://` (local-file disclosure), `gopher://`/`dict://`/`scp://`/
  `smb://` (protocol smuggling), and SSRF-via-redirect to non-network schemes.
- **No credential downgrade on redirect.** Requests carrying `Authorization:`/
  `Cookie:` headers set `CURLOPT_REDIR_PROTOCOLS_STR = "https"`, so a redirect
  can't forward the archive.org S3 key / session cookie over cleartext. (libcurl
  resends *custom* headers across redirects; `CURLOPT_UNRESTRICTED_AUTH` does not
  apply to them.)
- **Residual risk:** a redirect to a *different HTTPS host* still receives the
  custom auth headers. In practice archive.org redirects stay in-domain; full
  cross-host stripping would require disabling `FOLLOWLOCATION` and re-issuing
  auth per verified host. Documented, not yet implemented.

## Input validation

- `isSafeDownloadInput` (UI + repository boundary) rejects option-injection
  (leading `-`) and non-network schemes (`file:`/`content:`/`intent:`/
  `javascript:`/`data:`/…). Unit-tested in `SiteGroupingTest`.
- **yt-dlp argument injection** is neutralized at the source: both request
  builders append `--` (end-of-options) before the URL, so a `-`-leading URL can
  never become a flag (`--exec`, `--config-location`, …). The boundary check is
  belt-and-suspenders.
- **Path traversal** is defended in two layers: native filename sanitizers
  (`sanitize_filename` over *both* title and ext in `ytdlp.c`;
  `dlm_filename_from_url` neutralizes separators + leading `.`/`-`) and the Kotlin
  `confineToDownloadDir` guard in `resolveOut` (canonicalize; collapse escapes to
  a leaf name inside the download dir).

## Credentials at rest

- archive.org credentials (S3 keys / login cookie) are stored as **plaintext
  JSON** at `<app-config>/credentials`, mode `0600`, written via
  `O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW` + atomic `rename` (symlink/TOCTOU-hardened),
  temp unlinked on failure. **There is no at-rest encryption layer** — protection
  is app-private storage permissions only (normal on non-rooted devices).
- Freed secrets are wiped with `OPENSSL_cleanse` (resists dead-store elimination).
- `allowBackup="false"` keeps the credentials file out of `adb backup` / cloud
  auto-backup. **Do not** re-enable backup or add `dataExtractionRules` covering
  `filesDir` without first encrypting the file.
- **Future hardening:** wrap the file with Android Keystore / `EncryptedFile`.
  This needs a redesign (Kotlin would decrypt and pass secrets to native at
  runtime, since native currently reads the file directly). The dead
  `androidx.security:security-crypto` dependency was removed to avoid implying
  encryption that doesn't exist.

## Logging

No secrets are logged. URLs are reduced to host-only (`redactUrl`); yt-dlp failure
logs include only the host + exception class, never the raw exception (whose
message embeds the full command line and query tokens).

## Resource bounds

In-memory HTTP bodies are capped (httpget 64 MiB; yt-dlp capture 256 MiB; task
arrays clamped). Responses are not auto-decompressed (no `ACCEPT_ENCODING`), so
there is no gzip-bomb amplification; downloads stream to disk via `pwrite`.

## Components & IPC

- `MainActivity` is the only exported component; it parses intent extras only as a
  URL string (no nested-intent redirection). The `VIEW` filter matches all
  `http(s)` links — incoming URLs are treated as untrusted and validated.
- `DownloadService` is `exported="false"`, `dataSync`, started only via an
  internal explicit intent.
- `PendingIntent`s use `FLAG_IMMUTABLE` with an explicit target.

## Build hardening

`dlmcore` is built with `-fstack-protector-strong`, `_FORTIFY_SOURCE=2` (release),
and `relro`/`now`/`noexecstack` link flags. SQLite is built with a reduced option
set. See [Build.md](Build.md).

## Known low-severity items / follow-ups

- Cross-host (same-scheme) auth-header forwarding on redirect (see above).
- A third-party app can `ACTION_VIEW`-launch the activity and, with clipboard
  monitoring on, trigger a network *resolve* of clipboard contents (stages only;
  no auto-download without global autostart).
- archive.org metadata cookie values aren't CR/LF-checked (trusted TLS source).
- Generic-file fallback can save a non-media HTML page if a user forces it; gated
  on yt-dlp being READY to avoid the common accidental case.
</content>
