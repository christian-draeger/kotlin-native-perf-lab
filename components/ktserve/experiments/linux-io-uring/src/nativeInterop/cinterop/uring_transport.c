#include "uring_transport.h"

#include <liburing.h>
#include <stdlib.h>

struct ns_ring {
    struct io_uring ring;
    unsigned features;
};

unsigned ns_ring_features(const ns_ring *state) {
    return state->features;
}

ns_ring *ns_ring_open(unsigned entries, int *error) {
    ns_ring *state = calloc(1, sizeof(*state));
    if (state == NULL) {
        *error = -ENOMEM;
        return NULL;
    }
    struct io_uring_params params = {0};
    *error = io_uring_queue_init_params(entries, &state->ring, &params);
    if (*error < 0) {
        free(state);
        return NULL;
    }
    const unsigned required = IORING_FEAT_NODROP | IORING_FEAT_SUBMIT_STABLE | IORING_FEAT_EXT_ARG;
    struct io_uring_probe *probe = io_uring_get_probe_ring(&state->ring);
    const int supported = (params.features & required) == required && probe != NULL &&
        io_uring_opcode_supported(probe, IORING_OP_ACCEPT) &&
        io_uring_opcode_supported(probe, IORING_OP_RECV) &&
        io_uring_opcode_supported(probe, IORING_OP_SEND) &&
        io_uring_opcode_supported(probe, IORING_OP_ASYNC_CANCEL);
    io_uring_free_probe(probe);
    if (!supported) {
        *error = -EOPNOTSUPP;
        io_uring_queue_exit(&state->ring);
        free(state);
        return NULL;
    }
    state->features = params.features;
    return state;
}

void ns_ring_close(ns_ring *state) {
    io_uring_queue_exit(&state->ring);
    free(state);
}

int ns_ring_accept(ns_ring *state, uint64_t token, int fd) {
    struct io_uring_sqe *entry = io_uring_get_sqe(&state->ring);
    if (entry == NULL) return 0;
    io_uring_prep_accept(entry, fd, NULL, NULL, SOCK_CLOEXEC);
    io_uring_sqe_set_data64(entry, token);
    return 1;
}

int ns_ring_transfer(ns_ring *state, uint64_t token, int fd, void *buffer, int length, int write) {
    struct io_uring_sqe *entry = io_uring_get_sqe(&state->ring);
    if (entry == NULL) return 0;
    if (write) io_uring_prep_send(entry, fd, buffer, length, MSG_NOSIGNAL);
    else io_uring_prep_recv(entry, fd, buffer, length, 0);
    io_uring_sqe_set_data64(entry, token);
    return 1;
}

int ns_ring_cancel(ns_ring *state, uint64_t token, uint64_t target) {
    struct io_uring_sqe *entry = io_uring_get_sqe(&state->ring);
    if (entry == NULL) return 0;
    io_uring_prep_cancel64(entry, target, 0);
    io_uring_sqe_set_data64(entry, token);
    return 1;
}

int ns_ring_submit(ns_ring *state) {
    while (io_uring_sq_ready(&state->ring) != 0) {
        const int result = io_uring_submit(&state->ring);
        if (result == -EINTR) continue;
        if (result < 0) return result;
        if (result == 0) return -EIO;
    }
    return 0;
}

int ns_ring_take(ns_ring *state, int millis, ns_cqe *out) {
    struct io_uring_cqe *completion = NULL;
    struct __kernel_timespec timeout = {
        .tv_sec = millis / 1000,
        .tv_nsec = (millis % 1000) * 1000000L,
    };
    const int result = millis == 0 ? io_uring_peek_cqe(&state->ring, &completion)
        : millis < 0 ? io_uring_wait_cqe(&state->ring, &completion)
        : io_uring_wait_cqe_timeout(&state->ring, &completion, &timeout);
    if (result == -EAGAIN || result == -ETIME || result == -EINTR) return 0;
    if (result < 0) return result;
    if (completion == NULL) return 0;
    out->token = completion->user_data;
    out->result = completion->res;
    out->more = (completion->flags & IORING_CQE_F_MORE) != 0;
    io_uring_cqe_seen(&state->ring, completion);
    return 1;
}
