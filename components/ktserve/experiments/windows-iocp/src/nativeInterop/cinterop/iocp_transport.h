#ifndef KTSERVE_IOCP_TRANSPORT_H
#define KTSERVE_IOCP_TRANSPORT_H

#include <winsock2.h>
#include <windows.h>
#include <mswsock.h>
#include <stdint.h>
#include <stdlib.h>

#define NS_INVALID_SOCKET UINT64_MAX
#define NS_IO_CANCELLED ERROR_OPERATION_ABORTED
#define NS_PEER_GONE ERROR_NETNAME_DELETED
#define NS_PEER_RESET WSAECONNRESET

typedef struct {
    HANDLE port;
    SOCKET listener;
    LPFN_ACCEPTEX accept_ex;
} ns_iocp;

enum { NS_ADDR_BYTES = sizeof(struct sockaddr_in) + 16 };

typedef struct {
    OVERLAPPED overlapped;
    WSABUF buffer;
    DWORD flags;
    DWORD initial_count;
    char addresses[2 * NS_ADDR_BYTES];
} ns_io;

typedef struct {
    ns_io *operation;
    uint32_t count;
    uint32_t error;
} ns_completion;

static inline void ns_close_socket(uint64_t socket) {
    if (socket != NS_INVALID_SOCKET) closesocket((SOCKET)socket);
}

static inline void ns_shutdown_send(uint64_t socket) {
    shutdown((SOCKET)socket, SD_SEND);
}

static inline void ns_close_listener(ns_iocp *state) {
    ns_close_socket(state->listener);
    state->listener = INVALID_SOCKET;
}

static inline void ns_close_iocp(ns_iocp *state) {
    ns_close_listener(state);
    if (state->port != NULL) CloseHandle(state->port);
    WSACleanup();
    free(state);
}

static inline uint64_t ns_open_socket(void) {
    SOCKET socket = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, NULL, 0, WSA_FLAG_OVERLAPPED);
    if (socket == INVALID_SOCKET || SetHandleInformation((HANDLE)socket, HANDLE_FLAG_INHERIT, 0)) return socket;
    const int error = GetLastError();
    closesocket(socket);
    WSASetLastError(error);
    return NS_INVALID_SOCKET;
}

static inline ns_iocp *ns_open_iocp(uint32_t host, int port, uint32_t *error) {
    WSADATA data;
    *error = WSAStartup(MAKEWORD(2, 2), &data);
    if (*error != 0) return NULL;

    ns_iocp *state = calloc(1, sizeof(*state));
    if (state == NULL) {
        *error = ERROR_NOT_ENOUGH_MEMORY;
        WSACleanup();
        return NULL;
    }
    state->listener = (SOCKET)ns_open_socket();
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_port = htons((u_short)port);
    address.sin_addr.s_addr = htonl(host);
    GUID accept_ex = WSAID_ACCEPTEX;
    DWORD copied;
    const int exclusive = 1;
    if (state->listener == INVALID_SOCKET ||
        setsockopt(state->listener, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
                   (const char *)&exclusive, sizeof(exclusive)) != 0 ||
        bind(state->listener, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(state->listener, SOMAXCONN) != 0 ||
        WSAIoctl(state->listener, SIO_GET_EXTENSION_FUNCTION_POINTER, &accept_ex, sizeof(accept_ex),
                 &state->accept_ex, sizeof(state->accept_ex), &copied, NULL, NULL) != 0) {
        *error = WSAGetLastError();
        ns_close_iocp(state);
        return NULL;
    }
    state->port = CreateIoCompletionPort(INVALID_HANDLE_VALUE, NULL, 0, 1);
    if (state->port == NULL ||
        CreateIoCompletionPort((HANDLE)state->listener, state->port, 1, 0) != state->port) {
        *error = GetLastError();
        ns_close_iocp(state);
        return NULL;
    }
    return state;
}

static inline ns_io *ns_new_io(int length) {
    ns_io *operation = calloc(1, sizeof(*operation) + (size_t)length);
    if (operation != NULL) {
        operation->buffer.len = (ULONG)length;
        operation->buffer.buf = (char *)(operation + 1);
    }
    return operation;
}

static inline void ns_free_io(ns_io *operation) {
    free(operation);
}

static inline char *ns_io_buffer(ns_io *operation) {
    return operation->buffer.buf;
}

static inline uint32_t ns_submit_accept(ns_iocp *state, uint64_t socket, ns_io *operation) {
    if (state->accept_ex(state->listener, (SOCKET)socket, operation->addresses,
                        0, NS_ADDR_BYTES, NS_ADDR_BYTES, &operation->initial_count, &operation->overlapped)) {
        return 0;
    }
    const int error = WSAGetLastError();
    return error == WSA_IO_PENDING ? 0 : error;
}

static inline int ns_attach_accepted(ns_iocp *state, uint64_t value) {
    const SOCKET socket = (SOCKET)value;
    const int send_buffer = 64 * 1024;
    return setsockopt(socket, SOL_SOCKET, SO_UPDATE_ACCEPT_CONTEXT,
                      (const char *)&state->listener, sizeof(state->listener)) == 0 &&
           setsockopt(socket, SOL_SOCKET, SO_SNDBUF, (const char *)&send_buffer, sizeof(send_buffer)) == 0 &&
           CreateIoCompletionPort((HANDLE)socket, state->port, 1, 0) == state->port;
}

static inline uint32_t ns_submit_io(uint64_t value, ns_io *operation, int write) {
    const SOCKET socket = (SOCKET)value;
    const int result = write
        ? WSASend(socket, &operation->buffer, 1, NULL, 0, &operation->overlapped, NULL)
        : WSARecv(socket, &operation->buffer, 1, NULL, &operation->flags, &operation->overlapped, NULL);
    if (result == 0) return 0;
    const int error = WSAGetLastError();
    return error == WSA_IO_PENDING ? 0 : error;
}

static inline void ns_cancel_io(ns_iocp *state, uint64_t socket, ns_io *operation) {
    // The completion packet remains authoritative, including ERROR_NOT_FOUND races.
    const SOCKET owner = socket == NS_INVALID_SOCKET ? state->listener : (SOCKET)socket;
    CancelIoEx((HANDLE)owner, &operation->overlapped);
}

static inline int ns_take_completion(ns_iocp *state, int millis, ns_completion *result) {
    DWORD bytes;
    ULONG_PTR key;
    OVERLAPPED *operation = NULL;
    const BOOL ok = GetQueuedCompletionStatus(state->port, &bytes, &key, &operation,
                                               millis < 0 ? INFINITE : (DWORD)millis);
    result->error = ok ? 0 : GetLastError();
    if (operation == NULL) return result->error == WAIT_TIMEOUT ? 0 : -1;
    // OVERLAPPED is the first member and has the same address as the allocated ns_io.
    result->operation = (ns_io *)operation;
    result->count = bytes;
    return 1;
}

static inline const char *ns_backend_environment(void) {
    return getenv("KTSERVE_BACKEND");
}

static inline uint64_t ns_connect_probe_socket(int port) {
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return NS_INVALID_SOCKET;
    const SOCKET socket = (SOCKET)ns_open_socket();
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_port = htons((u_short)port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (socket != INVALID_SOCKET &&
        connect(socket, (const struct sockaddr *)&address, sizeof(address)) == 0) return socket;
    ns_close_socket(socket);
    WSACleanup();
    return NS_INVALID_SOCKET;
}

static inline int ns_send_probe_byte(uint64_t socket, int8_t value) {
    const char byte = (char)value;
    return send((SOCKET)socket, &byte, 1, 0);
}

static inline void ns_close_probe_socket(uint64_t socket) {
    ns_close_socket(socket);
    WSACleanup();
}

#endif
