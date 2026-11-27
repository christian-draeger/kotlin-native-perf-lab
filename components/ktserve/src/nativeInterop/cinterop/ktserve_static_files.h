#include <fcntl.h>
#include <sys/stat.h>

/* Kotlin/Native bindings expose different POSIX functions across targets. */
static inline int ktserve_static_entry_kind(int directory, const char *name) {
    struct stat entry;
    if (fstatat(directory, name, &entry, AT_SYMLINK_NOFOLLOW) < 0) {
        return -1;
    }
    return entry.st_mode & S_IFMT;
}

static inline int ktserve_static_open_child(int directory, const char *name) {
    return openat(directory, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
}

static inline int ktserve_static_duplicate(int directory) {
    return fcntl(directory, F_DUPFD_CLOEXEC, 0);
}
