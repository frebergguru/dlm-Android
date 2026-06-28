cmake_minimum_required(VERSION 3.22.1)
project(dlmcore C)

set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# Prebuilt native dependencies (curl, openssl, jansson, pcre2) per ABI.
#
# These are produced by ../../../../scripts/build-deps.sh and dropped under
# nativeDeps/<abi>/{include,lib}. SQLite3 ships in the NDK sysroot (-lsqlite3),
# zlib too (-lz).
# ---------------------------------------------------------------------------
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(DEPS "${PROJECT_ROOT}/nativeDeps/${ANDROID_ABI}")

if(NOT EXISTS "${DEPS}/lib")
    message(FATAL_ERROR
        "Prebuilt native deps not found for ABI ${ANDROID_ABI} at ${DEPS}.\n"
        "Run scripts/build-deps.sh first (see README).")
endif()

add_library(dep_crypto  STATIC IMPORTED)
set_target_properties(dep_crypto  PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/libcrypto.a")
add_library(dep_ssl     STATIC IMPORTED)
set_target_properties(dep_ssl     PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/libssl.a")
add_library(dep_curl    STATIC IMPORTED)
set_target_properties(dep_curl    PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/libcurl.a")
add_library(dep_jansson STATIC IMPORTED)
set_target_properties(dep_jansson PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/libjansson.a")
add_library(dep_pcre2   STATIC IMPORTED)
set_target_properties(dep_pcre2   PROPERTIES IMPORTED_LOCATION "${DEPS}/lib/libpcre2-8.a")

# ---------------------------------------------------------------------------
# libdlm core (vendored verbatim) + the JNI bridge.
# ---------------------------------------------------------------------------
set(LIBDLM "${CMAKE_CURRENT_SOURCE_DIR}/libdlm")

add_library(dlmcore SHARED
    # --- vendored core, reused unchanged (ytdlp.c has the subprocess path
    #     #ifdef'd out for Android; dlm_ytdlp_parse stays verbatim) ---
    ${LIBDLM}/util.c
    ${LIBDLM}/httpget.c
    ${LIBDLM}/download.c
    ${LIBDLM}/store.c
    ${LIBDLM}/verify.c
    ${LIBDLM}/iaauth.c
    ${LIBDLM}/extract.c
    ${LIBDLM}/extractors/archiveorg.c
    ${LIBDLM}/extractors/ytdlp.c

    # --- SQLite amalgamation (the NDK ships no sqlite3.h; vendor it so the DB
    #     engine is identical across devices) ---
    sqlite/sqlite3.c

    # --- JNI bridge (new) ---
    jni/jni_init.c
    jni/jni_store.c
    jni/jni_engine.c
    jni/jni_extract.c
    jni/jni_verify.c
    jni/jni_auth.c
    jni/jni_util.c
)

target_include_directories(dlmcore PRIVATE
    ${LIBDLM}
    ${LIBDLM}/include
    ${LIBDLM}/extractors
    ${CMAKE_CURRENT_SOURCE_DIR}/sqlite
    ${DEPS}/include
)

# Force 64-bit file offsets so off_t/pwrite/ftruncate handle >2 GiB downloads
# on 32-bit ABIs (armeabi-v7a, x86). Without these, off_t is 32-bit and large
# segment offsets silently truncate.
target_compile_definitions(dlmcore PRIVATE _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE)

# Hardening: this library parses untrusted network input (HTML/JSON via pcre2,
# the archive.org/ytdlp extractors, curl/openssl). Stack canaries + fortified
# libc calls at compile time; RELRO/BIND_NOW + non-exec stack at link time.
# _FORTIFY_SOURCE is only meaningful with optimisation, so skip it in Debug.
target_compile_options(dlmcore PRIVATE -fstack-protector-strong)
target_compile_options(dlmcore PRIVATE $<$<NOT:$<CONFIG:Debug>>:-D_FORTIFY_SOURCE=2>)
target_link_options(dlmcore PRIVATE -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)

target_compile_definitions(dlmcore PRIVATE PCRE2_CODE_UNIT_WIDTH=8)
# Keep the bundled SQLite lean; the queue store uses a small, standard subset.
set_source_files_properties(sqlite/sqlite3.c PROPERTIES COMPILE_OPTIONS "-w")
target_compile_definitions(dlmcore PRIVATE
    SQLITE_OMIT_LOAD_EXTENSION SQLITE_DEFAULT_FOREIGN_KEYS=1 SQLITE_THREADSAFE=1)

# curl(static) -> ssl -> crypto -> z; pcre2/jansson/sqlite compiled in.
target_link_libraries(dlmcore
    dep_curl
    dep_ssl
    dep_crypto
    dep_jansson
    dep_pcre2
    z
    log
    android
)
