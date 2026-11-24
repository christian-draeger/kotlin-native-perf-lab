#include <nghttp2/nghttp2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PROBE_HEADER 1
#define PROBE_DATA 2
#define PROBE_END 3
#define PROBE_CLOSED 4
#define PROBE_HEADERS_END 5

typedef int (*probe_event_fn)(void *, int, int32_t, const uint8_t *, size_t, const uint8_t *, size_t, uint32_t);
typedef ssize_t (*probe_read_fn)(void *, int32_t, uint8_t *, size_t, int *);

struct probe_session {
    nghttp2_session *session;
    void *context;
    probe_event_fn event;
    probe_read_fn read;
    const uint8_t *output;
    size_t pending;
};

static inline int probe_header_cb(nghttp2_session *session, const nghttp2_frame *frame,
                                  const uint8_t *name, size_t name_length, const uint8_t *value,
                                  size_t value_length, uint8_t flags, void *user) {
    (void)session;
    (void)flags;
    struct probe_session *probe = user;
    int result = probe->event(probe->context, PROBE_HEADER, frame->hd.stream_id,
                             name, name_length, value, value_length, 0);
    return result == 0 ? 0 : NGHTTP2_ERR_TEMPORAL_CALLBACK_FAILURE;
}

static inline int probe_data_cb(nghttp2_session *session, uint8_t flags, int32_t stream,
                                const uint8_t *data, size_t length, void *user) {
    (void)session;
    (void)flags;
    struct probe_session *probe = user;
    int result = probe->event(probe->context, PROBE_DATA, stream, data, length, 0, 0, 0);
    return result == 0 ? 0 : NGHTTP2_ERR_CALLBACK_FAILURE;
}

static inline int probe_frame_cb(nghttp2_session *session, const nghttp2_frame *frame, void *user) {
    (void)session;
    struct probe_session *probe = user;
    if (frame->hd.type == NGHTTP2_HEADERS) {
        int result = probe->event(probe->context, PROBE_HEADERS_END, frame->hd.stream_id, 0, 0, 0, 0, 0);
        if (result != 0) return NGHTTP2_ERR_CALLBACK_FAILURE;
    }
    if ((frame->hd.type == NGHTTP2_HEADERS || frame->hd.type == NGHTTP2_DATA) &&
        (frame->hd.flags & NGHTTP2_FLAG_END_STREAM)) {
        int result = probe->event(probe->context, PROBE_END, frame->hd.stream_id, 0, 0, 0, 0, 0);
        if (result != 0) return NGHTTP2_ERR_CALLBACK_FAILURE;
    }
    return 0;
}

static inline int probe_close_cb(nghttp2_session *session, int32_t stream, uint32_t code, void *user) {
    (void)session;
    struct probe_session *probe = user;
    int result = probe->event(probe->context, PROBE_CLOSED, stream, 0, 0, 0, 0, code);
    return result == 0 ? 0 : NGHTTP2_ERR_CALLBACK_FAILURE;
}

static inline ssize_t probe_read_cb(nghttp2_session *session, int32_t stream, uint8_t *out,
                                   size_t length, uint32_t *flags, nghttp2_data_source *source, void *user) {
    (void)session;
    (void)source;
    struct probe_session *probe = user;
    int done = 0;
    ssize_t count = probe->read(probe->context, stream, out, length, &done);
    if (count < 0) return NGHTTP2_ERR_TEMPORAL_CALLBACK_FAILURE;
    if (done) *flags |= NGHTTP2_DATA_FLAG_EOF;
    return count;
}

static inline struct probe_session *probe_open(int server, void *context, probe_event_fn event, probe_read_fn read) {
    struct probe_session *probe = calloc(1, sizeof(*probe));
    nghttp2_session_callbacks *callbacks = 0;
    nghttp2_option *options = 0;
    if (!probe) return 0;
    probe->context = context;
    probe->event = event;
    probe->read = read;
    if (nghttp2_session_callbacks_new(&callbacks) != 0 || nghttp2_option_new(&options) != 0) goto fail;
    nghttp2_session_callbacks_set_on_header_callback(callbacks, probe_header_cb);
    nghttp2_session_callbacks_set_on_data_chunk_recv_callback(callbacks, probe_data_cb);
    nghttp2_session_callbacks_set_on_frame_recv_callback(callbacks, probe_frame_cb);
    nghttp2_session_callbacks_set_on_stream_close_callback(callbacks, probe_close_cb);
    nghttp2_option_set_no_auto_window_update(options, 1);
    int result = server ? nghttp2_session_server_new2(&probe->session, callbacks, probe, options)
                        : nghttp2_session_client_new2(&probe->session, callbacks, probe, options);
    if (result != 0) goto fail;
    const nghttp2_settings_entry settings[] = {
        {NGHTTP2_SETTINGS_MAX_CONCURRENT_STREAMS, 8},
        {NGHTTP2_SETTINGS_MAX_HEADER_LIST_SIZE, 8192},
    };
    if (nghttp2_submit_settings(probe->session, NGHTTP2_FLAG_NONE, settings, 2) != 0) goto fail;
    nghttp2_session_callbacks_del(callbacks);
    nghttp2_option_del(options);
    return probe;
fail:
    if (probe->session) nghttp2_session_del(probe->session);
    if (options) nghttp2_option_del(options);
    if (callbacks) nghttp2_session_callbacks_del(callbacks);
    free(probe);
    return 0;
}

static inline nghttp2_nv probe_field(const char *name, const char *value) {
    return (nghttp2_nv){(uint8_t *)name, (uint8_t *)value, strlen(name), strlen(value), NGHTTP2_NV_FLAG_NONE};
}

static inline int32_t probe_get(struct probe_session *probe, const char *path) {
    nghttp2_nv fields[] = {probe_field(":method", "GET"), probe_field(":scheme", "http"),
                           probe_field(":authority", "localhost"), probe_field(":path", path)};
    return nghttp2_submit_request(probe->session, 0, fields, 4, 0, 0);
}

struct probe_field_pair {
    const char *name;
    const char *value;
};

static inline int probe_respond(struct probe_session *probe, int32_t stream, int status,
                                const char *content_type, int64_t length, int has_body,
                                const struct probe_field_pair *extras, size_t extra_count) {
    char code[4], length_text[21];
    snprintf(code, sizeof(code), "%d", status);
    snprintf(length_text, sizeof(length_text), "%lld", (long long)length);
    nghttp2_nv standard[3];
    nghttp2_nv *fields = extra_count == 0 ? standard : calloc(3 + extra_count, sizeof(*fields));
    if (!fields) return NGHTTP2_ERR_NOMEM;
    size_t count = 0;
    fields[count++] = probe_field(":status", code);
    if (content_type) fields[count++] = probe_field("content-type", content_type);
    if (length >= 0 && status != 204 && status != 304) fields[count++] = probe_field("content-length", length_text);
    for (size_t index = 0; index < extra_count; ++index) {
        fields[count++] = probe_field(extras[index].name, extras[index].value);
    }
    nghttp2_data_provider provider = {0};
    provider.read_callback = probe_read_cb;
    const int result = nghttp2_submit_response(probe->session, stream, fields, count, has_body ? &provider : 0);
    if (fields != standard) free(fields);
    return result;
}

static inline int probe_goaway(struct probe_session *probe) {
    return nghttp2_submit_goaway(probe->session, NGHTTP2_FLAG_NONE,
        nghttp2_session_get_last_proc_stream_id(probe->session), NGHTTP2_NO_ERROR, 0, 0);
}

static inline int probe_cancel(struct probe_session *probe, int32_t stream) {
    return nghttp2_submit_rst_stream(probe->session, NGHTTP2_FLAG_NONE, stream, NGHTTP2_CANCEL);
}

static inline int probe_consume(struct probe_session *probe, int32_t stream, size_t length) {
    return nghttp2_session_consume(probe->session, stream, length);
}

static inline ssize_t probe_input(struct probe_session *probe, const uint8_t *bytes, size_t length) {
    return nghttp2_session_mem_recv(probe->session, bytes, length);
}

static inline ssize_t probe_output(struct probe_session *probe, uint8_t *bytes, size_t capacity) {
    if (!probe->pending) {
        ssize_t count = nghttp2_session_mem_send(probe->session, &probe->output);
        if (count <= 0) return count;
        probe->pending = (size_t)count;
    }
    size_t count = probe->pending < capacity ? probe->pending : capacity;
    memcpy(bytes, probe->output, count);
    probe->output += count;
    probe->pending -= count;
    return (ssize_t)count;
}

static inline const char *probe_version(void) {
    return nghttp2_version(0)->version_str;
}

static inline void probe_close(struct probe_session *probe) {
    nghttp2_session_del(probe->session);
    free(probe);
}
