# Host (Linux) build of the libdlm-only unit tests, for CI verification of the
# vendored C core independent of Android. The daemon-level tests (queue/package/
# ipc) live with the upstream project; their logic is re-verified on Android by
# the Kotlin scheduler parity tests (app/src/test).
#
#   cmake -S core/src/main/cpp/host-tests -B build-host && ctest --test-dir build-host
#
# Requires system libcurl, jansson, sqlite3, pcre2 and OpenSSL dev packages.
cmake_minimum_required(VERSION 3.16)
project(dlm_host_tests C)

set(CMAKE_C_STANDARD 11)
enable_testing()

set(LIBDLM "${CMAKE_CURRENT_SOURCE_DIR}/../libdlm")
set(TESTS "${CMAKE_CURRENT_SOURCE_DIR}/../tests")

find_package(PkgConfig REQUIRED)
pkg_check_modules(CURL REQUIRED libcurl)
pkg_check_modules(JANSSON REQUIRED jansson)
pkg_check_modules(SQLITE3 REQUIRED sqlite3)
pkg_check_modules(PCRE2 REQUIRED libpcre2-8)
find_package(OpenSSL REQUIRED)

# libdlm static library (same translation units the NDK build uses).
add_library(dlm STATIC
    ${LIBDLM}/util.c ${LIBDLM}/httpget.c ${LIBDLM}/download.c ${LIBDLM}/store.c
    ${LIBDLM}/verify.c ${LIBDLM}/iaauth.c ${LIBDLM}/extract.c
    ${LIBDLM}/extractors/archiveorg.c ${LIBDLM}/extractors/ytdlp.c)
target_include_directories(dlm PUBLIC
    ${LIBDLM} ${LIBDLM}/include ${LIBDLM}/extractors
    ${CURL_INCLUDE_DIRS} ${JANSSON_INCLUDE_DIRS} ${SQLITE3_INCLUDE_DIRS} ${PCRE2_INCLUDE_DIRS})
target_compile_definitions(dlm PRIVATE PCRE2_CODE_UNIT_WIDTH=8)
target_link_libraries(dlm PUBLIC
    ${CURL_LIBRARIES} ${JANSSON_LIBRARIES} ${SQLITE3_LIBRARIES} ${PCRE2_LIBRARIES} OpenSSL::Crypto)

foreach(t util store ia ytdlp)
    add_executable(test_${t} ${TESTS}/test_${t}.c)
    target_include_directories(test_${t} PRIVATE ${LIBDLM} ${LIBDLM}/include)
    target_link_libraries(test_${t} PRIVATE dlm)
    add_test(NAME ${t} COMMAND test_${t})
endforeach()
