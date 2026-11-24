#include <arpa/inet.h>
#include <stdint.h>
#include <sys/event.h>
#include <time.h>

#define NATIVESERVE_EVENT_BATCH_SIZE 64

struct nativeserve_ready_event {
    uint64_t operation;
    int failed;
};

static inline int nativeserve_kqueue_open(void) {
    return kqueue();
}

static inline int nativeserve_kqueue_watch(int queue, int fd, int writing, uint64_t operation, int add) {
    struct kevent64_s change;
    EV_SET64(&change, fd, writing ? EVFILT_WRITE : EVFILT_READ,
             add ? EV_ADD | EV_ONESHOT : EV_DELETE, 0, 0, operation, 0, 0);
    return kevent64(queue, &change, 1, 0, 0, 0, 0);
}

static inline int nativeserve_kqueue_poll(int queue, struct nativeserve_ready_event *ready, int timeout_ms) {
    struct kevent64_s events[NATIVESERVE_EVENT_BATCH_SIZE];
    struct timespec timeout = {timeout_ms / 1000, (timeout_ms % 1000) * 1000000};
    int count = kevent64(queue, 0, 0, events, NATIVESERVE_EVENT_BATCH_SIZE, 0, &timeout);
    for (int i = 0; i < count; i++) {
        ready[i].operation = events[i].udata;
        ready[i].failed = (events[i].flags & EV_ERROR) != 0;
    }
    return count;
}

static inline uint16_t nativeserve_network_port(uint16_t port) {
    return htons(port);
}

static inline uint32_t nativeserve_network_ipv4(uint32_t address) {
    return htonl(address);
}
