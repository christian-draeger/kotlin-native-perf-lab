#ifndef KTSERVE_URING_TRANSPORT_H
#define KTSERVE_URING_TRANSPORT_H

#include <stdint.h>

typedef struct ns_ring ns_ring;

typedef struct {
    uint64_t token;
    int result;
    int more;
} ns_cqe;

ns_ring *ns_ring_open(unsigned entries, int *error);
unsigned ns_ring_features(const ns_ring *state);
void ns_ring_close(ns_ring *state);
int ns_ring_accept(ns_ring *state, uint64_t token, int fd);
int ns_ring_transfer(ns_ring *state, uint64_t token, int fd, void *buffer, int length, int write);
int ns_ring_cancel(ns_ring *state, uint64_t token, uint64_t target);
int ns_ring_submit(ns_ring *state);
int ns_ring_take(ns_ring *state, int millis, ns_cqe *out);

#endif
