#include <errno.h>
#include <signal.h>
#include <stdatomic.h>

#define REACTOR_INSTALL_SIGNALS 0
#define REACTOR_READ_STOP 1
#define REACTOR_RESTORE_SIGNALS 2

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "The signal flag must be lock-free");

static _Atomic int reactor_stop_requested;
static struct sigaction reactor_previous_int;
static struct sigaction reactor_previous_term;

static void reactor_request_stop(int signal_number) {
    (void)signal_number;
    atomic_store_explicit(&reactor_stop_requested, 1, memory_order_relaxed);
}

/* The handler and the atomic flag live in the same C interop translation unit. */
static inline int reactor_signals(int command) {
    if (command == REACTOR_READ_STOP) {
        return atomic_load_explicit(&reactor_stop_requested, memory_order_relaxed);
    }
    if (command == REACTOR_RESTORE_SIGNALS) {
        sigaction(SIGINT, &reactor_previous_int, 0);
        sigaction(SIGTERM, &reactor_previous_term, 0);
        return 0;
    }
    struct sigaction action = {0};
    action.sa_handler = reactor_request_stop;
    sigemptyset(&action.sa_mask);
    atomic_store_explicit(&reactor_stop_requested, 0, memory_order_relaxed);
    if (sigaction(SIGINT, &action, &reactor_previous_int) < 0) return -1;
    if (sigaction(SIGTERM, &action, &reactor_previous_term) < 0) {
        int failure = errno;
        sigaction(SIGINT, &reactor_previous_int, 0);
        errno = failure;
        return -1;
    }
    return 0;
}
