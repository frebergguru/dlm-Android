# Native core (`libdlm`)

The C library under `core/src/main/cpp/libdlm` is reused **verbatim** from the
desktop dlm wherever possible. It is compiled into `libdlmcore.so` together with
the JNI bridge (see [JNIBridge.md](JNIBridge.md)). This doc describes each unit
and the Android-specific edges.

## File map

| File | Responsibility |
|------|----------------|
| `download.c` | Segmented, resumable HTTP(S) downloader over libcurl multi. |
| `httpget.c` | Small GET/POST-to-memory helper used by extractors and auth. |
| `store.c` | SQLite-backed queue store (schema, migrations, ordering). |
| `verify.c` | MD5/SHA-1 file checksum verification (OpenSSL EVP). |
| `iaauth.c` | Internet Archive auth + credential storage. |
| `extract.c` | URL routing: archive.org vs direct-file vs "needs yt-dlp". |
| `extractors/archiveorg.c` | archive.org item → task list (metadata API). |
| `extractors/ytdlp.c` | The verbatim `dlm_ytdlp_parse` (yt-dlp JSON → tasks). |
| `util.c` | Filename derivation, rate parse/format, human bytes, CA-bundle hook. |
| `internal.h`, `include/dlm/*.h` | Internal helpers and the public API surface. |

## Segmented downloader (`download.c`)

Strategy:

1. **Probe** (`probe`) — a `HEAD` request learns total size + range support;
   servers that reject `HEAD` fall back to a `Range: 0-0` GET and read
   `Content-Range`. A `206` marks the URL resumable; a `200` means the server
   ignored the range (its `Content-Length` is the full size).
2. **Plan** N segments over `[0, total)`. Each segment is an independent libcurl
   easy handle requesting its byte range; a shared `curl_multi` loop drives them
   concurrently. Each write lands at its absolute offset via `pwrite()` into one
   pre-allocated (sparse) `.dlmpart` file.
3. **Journal** — a JSON sidecar (`.dlmjson`) records per-segment progress so an
   interrupted transfer resumes where each segment stopped.
4. **Finish** — on success the part file is renamed to the final path and the
   journal removed.

Robustness details worth knowing:
- Header parsing copies values into bounded buffers (length-checked) because
  curl header buffers are **not** NUL-terminated.
- Negative/garbage `Content-Length`/`Content-Range` totals are filtered (`>= 0`).
- No `CURLOPT_ACCEPT_ENCODING` is set, so responses are not auto-decompressed (no
  decompression-bomb amplification); the body streams straight to disk.

### TLS & protocol posture (security-relevant)

`apply_common_opts` sets, on every handle:
- `CURLOPT_FOLLOWLOCATION = 1`, `CURLOPT_MAXREDIRS = 20`.
- `CURLOPT_PROTOCOLS_STR = "http,https,ftp,ftps"` — blocks `file://`, `gopher://`,
  `dict://`, `scp://`, `smb://`, … on the initial request **and** redirects
  (defends against local-file disclosure + SSRF from attacker URLs).
- `CURLOPT_REDIR_PROTOCOLS_STR` = `"https"` **when the request carries credential
  headers** (`Authorization:`/`Cookie:` — detected by scanning `dl->header_list`),
  else `"http,https,ftp,ftps"`. This stops a redirect from downgrading a
  credentialed archive.org fetch to cleartext.
- `CURLOPT_SSL_VERIFYPEER`/`VERIFYHOST` are **never** set, so they stay at
  libcurl's secure defaults; `CURLOPT_CAINFO` is applied from `dlm_ca_bundle()`.

See [Security.md](Security.md) for the full rationale and residual risks.

## HTTP helper (`httpget.c`)

`dlm_http_request` / `dlm_http_get` fetch a URL into memory (cap: 64 MiB) for the
extractors and auth flows. All such traffic is archive.org over HTTPS, so it sets
`CURLOPT_PROTOCOLS_STR = "http,https"` and `CURLOPT_REDIR_PROTOCOLS_STR = "https"`
(never downgrade a request that may carry the IA cookie / S3 key).

## Store (`store.c`)

SQLite queue store: links and packages with ordering (`position`), priority,
enable/autostart flags, list membership (`download` / `linkgrabber`), and state.
All queries are **parameterized** (no string-built SQL). The store is
**single-thread-affine**; the Kotlin side confines every call to a dedicated
single-thread dispatcher (see [Scheduler.md](Scheduler.md)). Schema/migrations and
row ordering are reused verbatim from the desktop port. The Kotlin mirrors of its
rows are `StoreRow`/`PackageRow` (see [JNIBridge.md](JNIBridge.md)).

## Verify (`verify.c`)

`dlm_verify_md5` / `dlm_verify_sha1` hash a file (OpenSSL EVP, 64 KiB chunks) and
compare against an expected hex digest. Both guard a NULL expected digest
(returning `DLM_VERIFY_ERROR`) — archive.org omits a checksum for some files.
**Scope:** this is corruption/integrity detection against a checksum delivered by
archive.org over the same TLS channel — *not* tamper protection from an untrusted
mirror. MD5/SHA-1 are adequate for that and must not be promoted to a security
guarantee.

## Internet Archive auth (`iaauth.c`)

Supports three credential modes: S3 keys (`Authorization: LOW <access>:<secret>`),
email+password login, and a pasted session cookie. Storage:

- Credentials are written as JSON to `<app-config>/credentials`, created
  `O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW, 0600` then atomically `rename`d — this
  defeats symlink/TOCTOU on the predictable temp path and never widens
  permissions. The temp file is `unlink`ed on any write/rename failure.
- Secrets are **plaintext on app-private storage** (mode 0600). There is **no
  at-rest encryption layer** — see [Security.md](Security.md). Freed secrets are
  wiped with `OPENSSL_cleanse` (resists dead-store elimination).
- `dlm_ia_mode_str` returns only a status string (`anonymous` / `signed in (S3
  keys)` / `signed in (cookie)`) — never secret material. Nothing logs secrets.

## Extraction (`extract.c`, `extractors/*`)

- `extract.c` routes a URL: archive.org host → `archiveorg.c`; a URL whose
  basename looks like a direct file → direct task; otherwise it sets
  `needs_ytdlp` so the JVM resolver takes over.
- `archiveorg.c` queries the item metadata API and builds one task per file, with
  URL-encoded paths, optional md5/sha1, and (when signed in) auth headers.
- `ytdlp.c` keeps `dlm_ytdlp_parse` **verbatim**; only the subprocess path is
  `#ifdef`'d out (the JVM runs yt-dlp and feeds the JSON back in). Filenames are
  built as `<sanitized title>.<sanitized ext>` — **both** parts are sanitized
  (`sanitize_filename`) so an attacker `ext` containing `/` cannot traverse.

See [Resolvers.md](Resolvers.md) for the end-to-end resolve flow.

## Utilities (`util.c`)

- `dlm_filename_from_url` derives a safe basename: strips query/fragment, takes
  the last path segment, percent-decodes, replaces separators, and neutralizes a
  leading `.`/`-` (dotfile/option-looking) — mirroring the extractor sanitizers.
- `dlm_parse_rate` / `dlm_format_rate` parse `"2M"`/`"500k"` style limits and
  format `bytes/s`. `dlm_parse_rate` is tolerant: it skips spaces and reads only
  the first suffix letter, so `"1.5 MiB"` round-trips.
- `dlm_set_ca_bundle` / `dlm_ca_bundle` hold the CA path applied as
  `CURLOPT_CAINFO`. Set once from `jni_init.c::nativeInit`.
</content>
