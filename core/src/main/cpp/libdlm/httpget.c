/* SPDX-License-Identifier: GPL-3.0-or-later */
/* libdlm — small HTTP GET/POST-to-memory helper used by extractors and auth. */
#define _POSIX_C_SOURCE 200809L
#include "httpget.h"
#include "internal.h"
#include "dlm/dlm.h"

#include <curl/curl.h>
#include <strings.h> /* strncasecmp */

#define HTTPGET_UA "dlm/0.1 (+segmented-downloader)"
#define HTTPGET_MAX (64 * 1024 * 1024) /* cap response bodies at 64 MiB */

typedef struct {
    char *data;
    size_t len;
} membuf;

static size_t write_mem(char *ptr, size_t size, size_t nmemb, void *userp)
{
    membuf *m = userp;
    size_t add = size * nmemb;
    if (m->len + add > HTTPGET_MAX) return 0;
    m->data = dlm_xrealloc(m->data, m->len + add + 1);
    memcpy(m->data + m->len, ptr, add);
    m->len += add;
    m->data[m->len] = '\0';
    return add;
}

int dlm_http_request(const char *url, const char *post_fields,
                     const char *const *headers, char **body, long *status)
{
    dlm_global_init();
    CURL *c = curl_easy_init();
    if (!c) return DLM_ERR_NOMEM;

    membuf m = {NULL, 0};
    /* Pull any "Cookie:" header out of the list and feed it through the cookie
     * engine instead (below): a raw Cookie header set via CURLOPT_HTTPHEADER is
     * resent verbatim to a third-party host on a cross-origin redirect, which
     * would leak the archive.org session cookie. cookie_val points into the
     * caller-owned headers array, valid for this call. */
    struct curl_slist *hl = NULL;
    const char *cookie_val = NULL;
    if (headers) {
        for (int i = 0; headers[i]; i++) {
            if (strncasecmp(headers[i], "Cookie:", 7) == 0) {
                const char *v = headers[i] + 7;
                while (*v == ' ') v++;
                cookie_val = v;
            } else {
                hl = curl_slist_append(hl, headers[i]);
            }
        }
    }

    curl_easy_setopt(c, CURLOPT_URL, url);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_MAXREDIRS, 20L);
    /* Extractor/auth traffic is archive.org over HTTPS and may carry the IA
     * session cookie / S3 key; lock protocols down and never let a redirect
     * downgrade to cleartext. */
    curl_easy_setopt(c, CURLOPT_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(c, CURLOPT_REDIR_PROTOCOLS_STR, "https");
    curl_easy_setopt(c, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(c, CURLOPT_USERAGENT, HTTPGET_UA);
    { const char *ca = dlm_ca_bundle(); if (ca) curl_easy_setopt(c, CURLOPT_CAINFO, ca); }
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 30L);
    curl_easy_setopt(c, CURLOPT_TIMEOUT, 120L);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, write_mem);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, &m);
    /* Authorization is stripped on a cross-origin redirect by libcurl's default
     * (UNRESTRICTED_AUTH off); set it explicitly to document the intent. The
     * session cookie goes through the cookie engine, which binds it to the
     * request host so it isn't forwarded off-domain on a redirect. */
    curl_easy_setopt(c, CURLOPT_UNRESTRICTED_AUTH, 0L);
    if (cookie_val && *cookie_val) {
        curl_easy_setopt(c, CURLOPT_COOKIEFILE, ""); /* enable in-memory cookie engine */
        curl_easy_setopt(c, CURLOPT_COOKIE, cookie_val);
    }
    if (hl) curl_easy_setopt(c, CURLOPT_HTTPHEADER, hl);
    if (post_fields) {
        curl_easy_setopt(c, CURLOPT_POST, 1L);
        curl_easy_setopt(c, CURLOPT_POSTFIELDS, post_fields);
    }

    CURLcode rc = curl_easy_perform(c);
    long code = 0;
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &code);
    if (status) *status = code;
    if (hl) curl_slist_free_all(hl);
    curl_easy_cleanup(c);

    if (rc != CURLE_OK) {
        DLM_ERROR("http %s failed: %s", url, curl_easy_strerror(rc));
        free(m.data);
        if (body) *body = NULL;
        return DLM_ERR_NET;
    }
    if (body) *body = m.data ? m.data : dlm_xstrdup("");
    else free(m.data);
    return DLM_OK;
}

int dlm_http_get(const char *url, const char *const *headers, char **body,
                 long *status)
{
    return dlm_http_request(url, NULL, headers, body, status);
}
