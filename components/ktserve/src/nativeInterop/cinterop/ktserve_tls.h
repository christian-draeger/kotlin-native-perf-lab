#pragma once

#include <openssl/err.h>
#include <openssl/ssl.h>

static int ktserve_tls_no_password(char *buffer, int size, int writing, void *data) {
    (void)buffer;
    (void)size;
    (void)writing;
    (void)data;
    return 0;
}

static int ktserve_tls_alpn(SSL *ssl, const unsigned char **selected, unsigned char *selected_size,
                            const unsigned char *offered, unsigned int offered_size, void *data) {
    static const unsigned char http11[] = "\x08http/1.1";
    unsigned char *match = NULL;
    (void)ssl;
    (void)data;
    if (SSL_select_next_proto(&match, selected_size, http11, sizeof(http11) - 1,
                             offered, offered_size) != OPENSSL_NPN_NEGOTIATED) {
        return SSL_TLSEXT_ERR_ALERT_FATAL;
    }
    *selected = match;
    return SSL_TLSEXT_ERR_OK;
}

/* The caller owns the context and releases it after every session has been freed. */
static inline int ktserve_tls_context(const char *chain, const char *key, void **result) {
    ERR_clear_error();
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (ctx == NULL) return 1;
    int failure = 1;
    if (SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION) != 1) goto fail;
    SSL_CTX_set_options(ctx, SSL_OP_NO_COMPRESSION | SSL_OP_NO_RENEGOTIATION);
    SSL_CTX_set_mode(ctx, SSL_MODE_ENABLE_PARTIAL_WRITE | SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
    SSL_CTX_sess_set_cache_size(ctx, 512);
    SSL_CTX_set_alpn_select_cb(ctx, ktserve_tls_alpn, NULL);
    SSL_CTX_set_default_passwd_cb(ctx, ktserve_tls_no_password);
    failure = 2;
    if (SSL_CTX_use_certificate_chain_file(ctx, chain) != 1) goto fail;
    failure = 3;
    if (SSL_CTX_use_PrivateKey_file(ctx, key, SSL_FILETYPE_PEM) != 1 ||
        SSL_CTX_check_private_key(ctx) != 1) goto fail;
    *result = ctx;
    return 0;

fail:
    SSL_CTX_free(ctx);
    ERR_clear_error();
    return failure;
}

static inline void ktserve_tls_free_context(void *context) {
    SSL_CTX_free(context);
}

/* OpenSSL borrows a nonblocking descriptor. Free the session before closing it. */
static inline void *ktserve_tls_session(void *context, int fd) {
    ERR_clear_error();
    SSL *ssl = SSL_new(context);
    if (ssl == NULL) return NULL;
    if (SSL_set_fd(ssl, fd) == 1) return ssl;
    SSL_free(ssl);
    return NULL;
}

static inline int ktserve_tls_io_result(SSL *ssl, int result) {
    if (result > 0) return result;
    switch (SSL_get_error(ssl, result)) {
        case SSL_ERROR_WANT_READ: return -2;
        case SSL_ERROR_WANT_WRITE: return -3;
        case SSL_ERROR_ZERO_RETURN: return 0;
        default: return -1;
    }
}

static inline int ktserve_tls_handshake(void *session) {
    ERR_clear_error();
    int result = ktserve_tls_io_result(session, SSL_accept(session));
    return result == 0 ? -1 : result;
}

static inline int ktserve_tls_read(void *session, void *buffer, int length) {
    ERR_clear_error();
    return ktserve_tls_io_result(session, SSL_read(session, buffer, length));
}

static inline int ktserve_tls_write(void *session, void *buffer, int length) {
    ERR_clear_error();
    return ktserve_tls_io_result(session, SSL_write(session, buffer, length));
}

static inline int ktserve_tls_pending(void *session) {
    return SSL_pending(session);
}

static inline void ktserve_tls_close_notify(void *session) {
    ERR_clear_error();
    (void)SSL_shutdown(session);
}

static inline void ktserve_tls_free(void *session) {
    SSL_free(session);
}
