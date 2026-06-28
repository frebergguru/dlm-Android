#!/usr/bin/env bash
#
# Cross-compile the native dependencies (OpenSSL, libcurl, jansson, pcre2) that
# libdlm needs, for each Android ABI, into ../nativeDeps/<abi>/{include,lib}.
# SQLite3 ships in the NDK sysroot and zlib (-lz) too, so they aren't built here.
#
# Also fetches the current CA bundle into app/src/main/assets/cacert.pem (libcurl
# on Android has no default CA path; the engine is pointed at it via
# CURL_CA_BUNDLE).
#
# Requirements: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) set to an NDK r25+,
# plus autoconf/automake/libtool, cmake, make, curl, tar, perl.
#
# Usage:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/26.1.10909125 scripts/build-deps.sh
#   ABIS="arm64-v8a" scripts/build-deps.sh        # subset
set -euo pipefail

API=24
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}

OPENSSL_VER=3.3.2
CURL_VER=8.10.1
JANSSON_VER=2.14
PCRE2_VER=10.44

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "Set ANDROID_NDK_HOME to your NDK path." >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/nativeDeps"
WORK="$ROOT/.deps-build"
SRC="$WORK/src"
mkdir -p "$SRC"

HOSTTAG="linux-x86_64"
case "$(uname -s)" in Darwin) HOSTTAG="darwin-x86_64";; esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOSTTAG"
export PATH="$TOOLCHAIN/bin:$PATH"

fetch() { # url outfile
  [ -f "$2" ] || curl -fsSL "$1" -o "$2"
}

triple_for() { case "$1" in
  arm64-v8a) echo aarch64-linux-android;;
  armeabi-v7a) echo armv7a-linux-androideabi;;
  x86_64) echo x86_64-linux-android;;
  x86) echo i686-linux-android;;
esac; }

openssl_target() { case "$1" in
  arm64-v8a) echo android-arm64;;
  armeabi-v7a) echo android-arm;;
  x86_64) echo android-x86_64;;
  x86) echo android-x86;;
esac; }

echo ">> Fetching sources"
fetch "https://www.openssl.org/source/openssl-$OPENSSL_VER.tar.gz" "$SRC/openssl.tar.gz"
fetch "https://curl.se/download/curl-$CURL_VER.tar.gz" "$SRC/curl.tar.gz"
fetch "https://github.com/akheron/jansson/releases/download/v$JANSSON_VER/jansson-$JANSSON_VER.tar.gz" "$SRC/jansson.tar.gz"
fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VER/pcre2-$PCRE2_VER.tar.gz" "$SRC/pcre2.tar.gz"

for ABI in $ABIS; do
  echo "==================== $ABI ===================="
  TRIPLE="$(triple_for "$ABI")"
  PREFIX="$OUT/$ABI"
  BUILD="$WORK/$ABI"
  rm -rf "$BUILD"; mkdir -p "$BUILD" "$PREFIX"

  export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export AS="$CC"
  export CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
  export LD="$TOOLCHAIN/bin/ld"

  # ---- OpenSSL (libcrypto + libssl) ----
  echo ">> [$ABI] OpenSSL"
  tar xf "$SRC/openssl.tar.gz" -C "$BUILD"
  ( cd "$BUILD/openssl-$OPENSSL_VER"
    # 32-bit ARM: the hand-written perlasm emits R_ARM_REL32 against
    # OPENSSL_armcap_P, which can't be linked into our shared lib. no-asm
    # sidesteps it (the C fallbacks are plenty fast for our use). -fPIC anyway.
    OSSL_EXTRA="-fPIC"
    [ "$ABI" = "armeabi-v7a" ] && OSSL_EXTRA="$OSSL_EXTRA no-asm"
    ANDROID_NDK_ROOT="$NDK" PATH="$TOOLCHAIN/bin:$PATH" \
      ./Configure "$(openssl_target "$ABI")" -D__ANDROID_API__=$API \
        no-shared no-tests no-apps $OSSL_EXTRA --prefix="$PREFIX" >/dev/null
    make -s -j"$(nproc)" build_libs
    make -s install_dev >/dev/null )

  # ---- libcurl (static, OpenSSL backend, http/https only) ----
  echo ">> [$ABI] libcurl"
  tar xf "$SRC/curl.tar.gz" -C "$BUILD"
  ( cd "$BUILD/curl-$CURL_VER"
    ./configure --host="$TRIPLE" --prefix="$PREFIX" \
      --with-openssl="$PREFIX" --enable-static --disable-shared \
      --disable-ldap --disable-ldaps --disable-manual --disable-libcurl-option \
      --without-libpsl --without-libidn2 --without-brotli --without-zstd \
      --enable-ipv6 --enable-http --enable-proxy \
      CPPFLAGS="-I$PREFIX/include" LDFLAGS="-L$PREFIX/lib" >/dev/null
    make -s -j"$(nproc)"
    make -s install >/dev/null )

  # ---- jansson (static) ----
  echo ">> [$ABI] jansson"
  tar xf "$SRC/jansson.tar.gz" -C "$BUILD"
  cmake -S "$BUILD/jansson-$JANSSON_VER" -B "$BUILD/jansson-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DJANSSON_BUILD_SHARED_LIBS=OFF \
    -DJANSSON_BUILD_DOCS=OFF -DJANSSON_EXAMPLES=OFF -DJANSSON_WITHOUT_TESTS=ON >/dev/null
  cmake --build "$BUILD/jansson-build" -j"$(nproc)" >/dev/null
  cmake --install "$BUILD/jansson-build" >/dev/null

  # ---- pcre2 (8-bit, static) ----
  echo ">> [$ABI] pcre2"
  tar xf "$SRC/pcre2.tar.gz" -C "$BUILD"
  cmake -S "$BUILD/pcre2-$PCRE2_VER" -B "$BUILD/pcre2-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DBUILD_SHARED_LIBS=OFF \
    -DPCRE2_BUILD_PCRE2_8=ON -DPCRE2_BUILD_TESTS=OFF \
    -DPCRE2_BUILD_PCRE2GREP=OFF >/dev/null
  cmake --build "$BUILD/pcre2-build" -j"$(nproc)" >/dev/null
  cmake --install "$BUILD/pcre2-build" >/dev/null

  # Normalise lib dir name (some installs use lib64).
  if [ -d "$PREFIX/lib64" ]; then
    cp -an "$PREFIX/lib64/." "$PREFIX/lib/" 2>/dev/null || true
  fi
  echo ">> [$ABI] done -> $PREFIX/lib"
  ls "$PREFIX/lib"/*.a
done

echo ">> Fetching CA bundle"
fetch "https://curl.se/ca/cacert.pem" "$ROOT/app/src/main/assets/cacert.pem"

echo "All ABIs built. nativeDeps/ is ready; build the app with Gradle."
