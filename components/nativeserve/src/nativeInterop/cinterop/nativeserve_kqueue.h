#include <sys/event.h>
#include <time.h>

static inline int nativeserve_kqueue_poll(int queue, struct kevent64_s *events, int count, int timeout_ms) {
    struct timespec timeout = {timeout_ms / 1000, (timeout_ms % 1000) * 1000000};
    return kevent64(queue, 0, 0, events, count, 0, &timeout);
}
