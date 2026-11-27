#include "ktserve_signals.h"

static inline int ktserve_cli_install_signals(void) {
    return ktserve_signals(KTSERVE_INSTALL_SIGNALS);
}

static inline int ktserve_cli_stop_requested(void) {
    return ktserve_signals(KTSERVE_READ_STOP);
}

static inline void ktserve_cli_restore_signals(void) {
    ktserve_signals(KTSERVE_RESTORE_SIGNALS);
}
